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

package google.registry.reporting.billing;

import static google.registry.beam.BeamUtils.createJobName;
import static google.registry.model.common.Cursor.CursorType.RECURRING_BILLING;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.POST;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.api.services.dataflow.Dataflow;
import com.google.api.services.dataflow.model.LaunchFlexTemplateParameter;
import com.google.api.services.dataflow.model.LaunchFlexTemplateRequest;
import com.google.api.services.dataflow.model.LaunchFlexTemplateResponse;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryEnvironment;
import google.registry.model.common.Cursor;
import google.registry.persistence.PersistenceModule;
import google.registry.reporting.ReportingModule;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.util.CloudTasksUtils;
import java.io.IOException;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.YearMonth;

/**
 * Invokes the {@code InvoicingPipeline} beam template via the REST api, and enqueues the {@link
 * PublishInvoicesAction} to publish the subsequent output.
 *
 * <p>This action runs the {@link google.registry.beam.invoicing.InvoicingPipeline} beam flex
 * template. The pipeline then generates invoices for the month and stores them on GCS.
 */
@Action(
    service = Action.Service.BACKEND,
    path = GenerateInvoicesAction.PATH,
    method = POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class GenerateInvoicesAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final String PATH = "/_dr/task/generateInvoices";
  static final String PIPELINE_NAME = "invoicing_pipeline";

  private final String projectId;
  private final String jobRegion;
  private final String stagingBucketUrl;
  private final String billingBucketUrl;
  private final String invoiceFilePrefix;
  private final boolean shouldPublish;
  private final YearMonth yearMonth;
  private final BillingEmailUtils emailUtils;
  private final Clock clock;
  private final Response response;
  private final Dataflow dataflow;
  private final CloudTasksUtils cloudTasksUtils;

  @Inject
  GenerateInvoicesAction(
      @Config("projectId") String projectId,
      @Config("defaultJobRegion") String jobRegion,
      @Config("beamStagingBucketUrl") String stagingBucketUrl,
      @Config("billingBucketUrl") String billingBucketUrl,
      @Config("invoiceFilePrefix") String invoiceFilePrefix,
      @Parameter(BillingModule.PARAM_SHOULD_PUBLISH) boolean shouldPublish,
      YearMonth yearMonth,
      BillingEmailUtils emailUtils,
      CloudTasksUtils cloudTasksUtils,
      Clock clock,
      Response response,
      Dataflow dataflow) {
    this.projectId = projectId;
    this.jobRegion = jobRegion;
    this.stagingBucketUrl = stagingBucketUrl;
    this.billingBucketUrl = billingBucketUrl;
    this.invoiceFilePrefix = invoiceFilePrefix;
    this.shouldPublish = shouldPublish;
    this.yearMonth = yearMonth;
    this.emailUtils = emailUtils;
    this.cloudTasksUtils = cloudTasksUtils;
    this.clock = clock;
    this.response = response;
    this.dataflow = dataflow;
  }

  @Override
  public void run() {
    response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
    logger.atInfo().log("Launching invoicing pipeline for %s.", yearMonth);
    try {
      DateTime currentCursorTime =
          tm().transact(
                  () ->
                      tm().loadByKeyIfPresent(Cursor.createGlobalVKey(RECURRING_BILLING))
                          .orElse(Cursor.createGlobal(RECURRING_BILLING, START_OF_TIME))
                          .getCursorTime());

      if (yearMonth.getMonthOfYear() >= currentCursorTime.getMonthOfYear()) {
        throw new IllegalStateException(
            "Latest billing events expansion cycle hasn't finished yet, terminating invoicing"
                + " pipeline");
      }

      LaunchFlexTemplateParameter parameter =
          new LaunchFlexTemplateParameter()
              .setJobName(createJobName("invoicing", clock))
              .setContainerSpecGcsPath(
                  String.format("%s/%s_metadata.json", stagingBucketUrl, PIPELINE_NAME))
              .setParameters(
                  new ImmutableMap.Builder<String, String>()
                      .put("yearMonth", yearMonth.toString("yyyy-MM"))
                      .put("invoiceFilePrefix", invoiceFilePrefix)
                      .put("billingBucketUrl", billingBucketUrl)
                      .put("registryEnvironment", RegistryEnvironment.get().name())
                      .put(
                          "jpaTransactionManagerType",
                          PersistenceModule.JpaTransactionManagerType.READ_ONLY_REPLICA.toString())
                      .build());
      LaunchFlexTemplateResponse launchResponse =
          dataflow
              .projects()
              .locations()
              .flexTemplates()
              .launch(
                  projectId,
                  jobRegion,
                  new LaunchFlexTemplateRequest().setLaunchParameter(parameter))
              .execute();
      logger.atInfo().log("Got response: %s", launchResponse.getJob().toPrettyString());
      String jobId = launchResponse.getJob().getId();
      if (shouldPublish) {
        cloudTasksUtils.enqueue(
            ReportingModule.BEAM_QUEUE,
            cloudTasksUtils.createPostTaskWithDelay(
                PublishInvoicesAction.PATH,
                Service.BACKEND.toString(),
                ImmutableMultimap.of(
                    ReportingModule.PARAM_JOB_ID,
                    jobId,
                    ReportingModule.PARAM_YEAR_MONTH,
                    yearMonth.toString()),
                Duration.standardMinutes(ReportingModule.ENQUEUE_DELAY_MINUTES)));
      }
      response.setStatus(SC_OK);
      response.setPayload(String.format("Launched invoicing pipeline: %s", jobId));
    } catch (IOException | IllegalStateException e) {
      logger.atWarning().withCause(e).log("Template Launch failed.");
      emailUtils.sendAlertEmail(String.format("Pipeline Launch failed due to %s", e.getMessage()));
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setPayload(String.format("Pipeline launch failed: %s", e.getMessage()));
    }
  }
}
