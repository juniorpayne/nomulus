// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.reporting.spec11;

import static google.registry.beam.BeamUtils.createJobName;
import static google.registry.request.Action.Method.POST;
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
import google.registry.keyring.api.KeyModule.Key;
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
import org.joda.time.Duration;
import org.joda.time.LocalDate;

/**
 * Invokes the {@code Spec11Pipeline} Beam template via the REST api.
 *
 * <p>This action runs the {@link google.registry.beam.spec11.Spec11Pipeline} template, which
 * generates the specified month's Spec11 report and stores it on GCS.
 */
@Action(
    service = Action.Service.BACKEND,
    path = GenerateSpec11ReportAction.PATH,
    method = POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class GenerateSpec11ReportAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final String PATH = "/_dr/task/generateSpec11";
  static final String PIPELINE_NAME = "spec11_pipeline";

  private final String projectId;
  private final String jobRegion;
  private final String stagingBucketUrl;
  private final String reportingBucketUrl;
  private final String apiKey;
  private final LocalDate date;
  private final Clock clock;
  private final Response response;
  private final Dataflow dataflow;
  private final boolean sendEmail;
  private final CloudTasksUtils cloudTasksUtils;

  @Inject
  GenerateSpec11ReportAction(
      @Config("projectId") String projectId,
      @Config("defaultJobRegion") String jobRegion,
      @Config("beamStagingBucketUrl") String stagingBucketUrl,
      @Config("reportingBucketUrl") String reportingBucketUrl,
      @Key("safeBrowsingAPIKey") String apiKey,
      @Parameter(ReportingModule.PARAM_DATE) LocalDate date,
      @Parameter(ReportingModule.SEND_EMAIL) boolean sendEmail,
      Clock clock,
      Response response,
      Dataflow dataflow,
      CloudTasksUtils cloudTasksUtils) {
    this.projectId = projectId;
    this.jobRegion = jobRegion;
    this.stagingBucketUrl = stagingBucketUrl;
    this.reportingBucketUrl = reportingBucketUrl;
    this.apiKey = apiKey;
    this.date = date;
    this.clock = clock;
    this.response = response;
    this.dataflow = dataflow;
    this.sendEmail = sendEmail;
    this.cloudTasksUtils = cloudTasksUtils;
  }

  @Override
  public void run() {
    response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
    try {
      LaunchFlexTemplateParameter parameter =
          new LaunchFlexTemplateParameter()
              .setJobName(createJobName("spec11", clock))
              .setContainerSpecGcsPath(
                  String.format("%s/%s_metadata.json", stagingBucketUrl, PIPELINE_NAME))
              .setParameters(
                  ImmutableMap.of(
                      "safeBrowsingApiKey",
                      apiKey,
                      ReportingModule.PARAM_DATE,
                      date.toString(),
                      "reportingBucketUrl",
                      reportingBucketUrl,
                      "registryEnvironment",
                      RegistryEnvironment.get().name()));
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
      if (sendEmail) {
        cloudTasksUtils.enqueue(
            ReportingModule.BEAM_QUEUE,
            cloudTasksUtils.createPostTaskWithDelay(
                PublishSpec11ReportAction.PATH,
                Service.BACKEND.toString(),
                ImmutableMultimap.of(
                    ReportingModule.PARAM_JOB_ID,
                    jobId,
                    ReportingModule.PARAM_DATE,
                    date.toString()),
                Duration.standardMinutes(ReportingModule.ENQUEUE_DELAY_MINUTES)));
      }
      response.setStatus(SC_OK);
      response.setPayload(String.format("Launched Spec11 pipeline: %s", jobId));
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Template Launch failed.");
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setPayload(String.format("Pipeline launch failed: %s", e.getMessage()));
    }
  }
}
