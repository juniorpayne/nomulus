// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.bsa.persistence;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.bsa.DownloadStage.CHECKSUMS_NOT_MATCH;
import static google.registry.bsa.DownloadStage.DONE;
import static google.registry.bsa.DownloadStage.DOWNLOAD;
import static google.registry.bsa.DownloadStage.MAKE_DIFF;
import static google.registry.bsa.DownloadStage.NOP;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.joda.time.DateTimeZone.UTC;
import static org.joda.time.Duration.standardDays;
import static org.joda.time.Duration.standardMinutes;
import static org.joda.time.Duration.standardSeconds;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.bsa.BlockList;
import google.registry.bsa.DownloadStage;
import google.registry.bsa.persistence.DownloadSchedule.CompletedJob;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.testing.FakeClock;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link DownloadScheduler} */
public class DownloadSchedulerTest {

  static final Duration DOWNLOAD_INTERVAL = standardMinutes(30);
  static final Duration MAX_NOP_INTERVAL = standardDays(1);

  protected FakeClock fakeClock = new FakeClock(DateTime.now(UTC));

  @RegisterExtension
  final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  private DownloadScheduler scheduler;

  @BeforeEach
  void setup() {
    scheduler = new DownloadScheduler(DOWNLOAD_INTERVAL, MAX_NOP_INTERVAL, fakeClock);
  }

  @AfterEach
  void dbCheck() {
    ImmutableSet<DownloadStage> terminalStages = ImmutableSet.of(DONE, NOP, CHECKSUMS_NOT_MATCH);
    assertThat(
            tm().transact(
                    () ->
                        tm().getEntityManager()
                            .createQuery("FROM BsaDownload", BsaDownload.class)
                            .getResultStream()
                            .filter(job -> !terminalStages.contains(job.getStage()))
                            .count()))
        .isAtMost(1);
  }

  @Test
  void firstJobEver() {
    Optional<DownloadSchedule> scheduleOptional = scheduler.schedule();
    assertThat(scheduleOptional).isPresent();
    DownloadSchedule schedule = scheduleOptional.get();
    assertThat(schedule.latestCompleted()).isEmpty();
    assertThat(schedule.jobName()).isEqualTo(fakeClock.nowUtc().toString());
    assertThat(schedule.stage()).isEqualTo(DownloadStage.DOWNLOAD);
    assertThat(schedule.alwaysDownload()).isTrue();
  }

  @Test
  void oneInProgressJob() {
    BsaDownload inProgressJob = insertOneJobAndAdvanceClock(MAKE_DIFF);
    Optional<DownloadSchedule> scheduleOptional = scheduler.schedule();
    assertThat(scheduleOptional).isPresent();
    DownloadSchedule schedule = scheduleOptional.get();
    assertThat(schedule.jobId()).isEqualTo(inProgressJob.jobId);
    assertThat(schedule.jobName()).isEqualTo(inProgressJob.getJobName());
    assertThat(schedule.stage()).isEqualTo(MAKE_DIFF);
    assertThat(schedule.latestCompleted()).isEmpty();
    assertThat(schedule.alwaysDownload()).isTrue();
  }

  @Test
  void oneInProgressJobOneCompletedJob() {
    BsaDownload completed = insertOneJobAndAdvanceClock(DONE);
    BsaDownload inProgressJob = insertOneJobAndAdvanceClock(MAKE_DIFF);
    Optional<DownloadSchedule> scheduleOptional = scheduler.schedule();
    assertThat(scheduleOptional).isPresent();
    DownloadSchedule schedule = scheduleOptional.get();
    assertThat(schedule.jobId()).isEqualTo(inProgressJob.jobId);
    assertThat(schedule.jobName()).isEqualTo(inProgressJob.getJobName());
    assertThat(schedule.stage()).isEqualTo(MAKE_DIFF);
    assertThat(schedule.alwaysDownload()).isFalse();
    assertThat(schedule.latestCompleted()).isPresent();
    CompletedJob lastCompleted = schedule.latestCompleted().get();
    assertThat(lastCompleted.jobName()).isEqualTo(completed.getJobName());
    assertThat(lastCompleted.checksums()).isEqualTo(completed.getChecksums());
  }

  @Test
  void doneJob_noNewSchedule() {
    insertOneJobAndAdvanceClock(DONE);
    assertThat(scheduler.schedule()).isEmpty();
  }

  @Test
  void doneJob_newSchedule() {
    BsaDownload completed = insertOneJobAndAdvanceClock(DONE);
    fakeClock.advanceBy(DOWNLOAD_INTERVAL);
    Optional<DownloadSchedule> scheduleOptional = scheduler.schedule();
    assertThat(scheduleOptional).isPresent();
    DownloadSchedule schedule = scheduleOptional.get();
    assertThat(schedule.stage()).isEqualTo(DOWNLOAD);
    assertThat(schedule.alwaysDownload()).isFalse();
    assertThat(schedule.latestCompleted()).isPresent();
    CompletedJob completedJob = schedule.latestCompleted().get();
    assertThat(completedJob.jobName()).isEqualTo(completed.getJobName());
    assertThat(completedJob.checksums()).isEqualTo(completedJob.checksums());
  }

  @Test
  void doneJob_newSchedule_alwaysDownload() {
    insertOneJobAndAdvanceClock(DONE);
    fakeClock.advanceBy(MAX_NOP_INTERVAL);
    Optional<DownloadSchedule> scheduleOptional = scheduler.schedule();
    assertThat(scheduleOptional).isPresent();
    DownloadSchedule schedule = scheduleOptional.get();
    assertThat(schedule.alwaysDownload()).isTrue();
  }

  @Test
  void doneJob_cronEarlyWithJitter_newSchedule() {
    insertOneJobAndAdvanceClock(DONE);
    fakeClock.advanceBy(DOWNLOAD_INTERVAL.minus(standardSeconds(5)));
    assertThat(scheduler.schedule()).isPresent();
  }

  @Test
  void doneJob_cronEarlyMoreThanJitter_newSchedule() {
    insertOneJobAndAdvanceClock(DONE);
    fakeClock.advanceBy(DOWNLOAD_INTERVAL.minus(standardSeconds(6)));
    assertThat(scheduler.schedule()).isEmpty();
  }

  @Test
  void loadRecentProcessedJobs_noneExists() {
    assertThat(tm().transact(() -> scheduler.loadRecentProcessedJobs())).isEmpty();
  }

  @Test
  void loadRecentProcessedJobs_nopJobsOnly() {
    insertOneJobAndAdvanceClock(DownloadStage.NOP);
    insertOneJobAndAdvanceClock(DownloadStage.CHECKSUMS_NOT_MATCH);
    assertThat(tm().transact(() -> scheduler.loadRecentProcessedJobs())).isEmpty();
  }

  @Test
  void loadRecentProcessedJobs_oneInProgressJob() {
    BsaDownload job = insertOneJobAndAdvanceClock(MAKE_DIFF);
    assertThat(tm().transact(() -> scheduler.loadRecentProcessedJobs())).containsExactly(job);
  }

  @Test
  void loadRecentProcessedJobs_oneDoneJob() {
    BsaDownload job = insertOneJobAndAdvanceClock(DONE);
    assertThat(tm().transact(() -> scheduler.loadRecentProcessedJobs())).containsExactly(job);
  }

  @Test
  void loadRecentProcessedJobs_multipleJobs() {
    insertOneJobAndAdvanceClock(DownloadStage.DONE);
    insertOneJobAndAdvanceClock(DownloadStage.DONE);
    BsaDownload completed = insertOneJobAndAdvanceClock(DownloadStage.DONE);
    insertOneJobAndAdvanceClock(DownloadStage.NOP);
    insertOneJobAndAdvanceClock(DownloadStage.CHECKSUMS_NOT_MATCH);
    BsaDownload inprogress = insertOneJobAndAdvanceClock(DownloadStage.APPLY_DIFF);
    assertThat(tm().transact(() -> scheduler.loadRecentProcessedJobs()))
        .containsExactly(inprogress, completed)
        .inOrder();
  }

  private BsaDownload insertOneJobAndAdvanceClock(DownloadStage stage) {
    BsaDownload job = new BsaDownload();
    job.setStage(stage);
    job.setChecksums(ImmutableMap.of(BlockList.BLOCK, "1", BlockList.BLOCK_PLUS, "2"));
    tm().transact(() -> tm().insert(job));
    fakeClock.advanceOneMilli();
    return job;
  }
}
