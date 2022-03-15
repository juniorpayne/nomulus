// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.backup;

import static google.registry.backup.ExportCommitLogDiffAction.LOWER_CHECKPOINT_TIME_PARAM;
import static google.registry.backup.ExportCommitLogDiffAction.UPPER_CHECKPOINT_TIME_PARAM;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.flogger.FluentLogger;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.ofy.CommitLogCheckpoint;
import google.registry.model.ofy.CommitLogCheckpointRoot;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.auth.Auth;
import google.registry.util.CloudTasksUtils;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Action that saves commit log checkpoints to Datastore and kicks off a diff export task.
 *
 * <p>We separate computing and saving the checkpoint from exporting it because the export to GCS is
 * retryable but should not require the computation of a new checkpoint. Saving the checkpoint and
 * enqueuing the export task are done transactionally, so any checkpoint that is saved will be
 * exported to GCS very soon.
 *
 * <p>This action's supported method is GET rather than POST because it gets invoked via cron.
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/cron/commitLogCheckpoint",
    method = Action.Method.GET,
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
@DeleteAfterMigration
public final class CommitLogCheckpointAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String QUEUE_NAME = "export-commits";

  /**
   * The amount of time enqueueing should be delayed.
   *
   * <p>The {@link ExportCommitLogDiffAction} is enqueued in {@link CommitLogCheckpointAction},
   * which is inside a Datastore transaction that persists the checkpoint to be exported. After the
   * switch to CloudTasks API, the task may be invoked before the Datastore transaction commits.
   * When this happens, the checkpoint is not found which leads to {@link
   * com.google.common.base.VerifyException}.
   *
   * <p>In order to invoke the task after the transaction commits, a reasonable delay should be
   * added to each task. The latency of the request is mostly in the range of 4-6 seconds; Choosing
   * a value 30% greater than the upper bound should solve the issue invoking a task before the
   * transaction commits.
   */
  static final Duration ENQUEUE_DELAY_SECONDS = Duration.standardSeconds(8);

  @Inject CommitLogCheckpointStrategy strategy;
  @Inject CloudTasksUtils cloudTasksUtils;

  @Inject CommitLogCheckpointAction() {}

  @Override
  public void run() {
    createCheckPointAndStartAsyncExport();
  }

  /**
   * Creates a {@link CommitLogCheckpoint} and initiates an asynchronous export task.
   *
   * @return the {@code CommitLogCheckpoint} to be exported
   */
  public Optional<CommitLogCheckpoint> createCheckPointAndStartAsyncExport() {
    final CommitLogCheckpoint checkpoint = strategy.computeCheckpoint();
    logger.atInfo().log(
        "Generated candidate checkpoint for time: %s", checkpoint.getCheckpointTime());
    boolean isCheckPointPersisted =
        ofyTm()
            .transact(
                () -> {
                  DateTime lastWrittenTime =
                      CommitLogCheckpointRoot.loadRoot().getLastWrittenTime();
                  if (isBeforeOrAt(checkpoint.getCheckpointTime(), lastWrittenTime)) {
                    logger.atInfo().log(
                        "Newer checkpoint already written at time: %s", lastWrittenTime);
                    return false;
                  }
                  auditedOfy()
                      .saveIgnoringReadOnlyWithoutBackup()
                      .entities(
                          checkpoint,
                          CommitLogCheckpointRoot.create(checkpoint.getCheckpointTime()));
                  // Enqueue a diff task between previous and current checkpoints.
                  cloudTasksUtils.enqueue(
                      QUEUE_NAME,
                      cloudTasksUtils.createPostTaskWithDelay(
                          ExportCommitLogDiffAction.PATH,
                          Service.BACKEND.toString(),
                          ImmutableMultimap.of(
                              LOWER_CHECKPOINT_TIME_PARAM,
                              lastWrittenTime.toString(),
                              UPPER_CHECKPOINT_TIME_PARAM,
                              checkpoint.getCheckpointTime().toString()),
                          ENQUEUE_DELAY_SECONDS));
                  return true;
                });
    return isCheckPointPersisted ? Optional.of(checkpoint) : Optional.empty();
  }
}
