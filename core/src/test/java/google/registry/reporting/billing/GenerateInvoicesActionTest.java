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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.Cursor.CursorType.RECURRING_BILLING;
import static google.registry.testing.DatabaseHelper.persistResource;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.cloud.tasks.v2.HttpMethod;
import com.google.common.net.MediaType;
import google.registry.beam.BeamActionTestBase;
import google.registry.model.common.Cursor;
import google.registry.reporting.ReportingModule;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.CloudTasksHelper.TaskMatcher;
import google.registry.testing.FakeClock;
import google.registry.util.CloudTasksUtils;
import java.io.IOException;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link google.registry.reporting.billing.GenerateInvoicesAction}. */
class GenerateInvoicesActionTest extends BeamActionTestBase {

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder().withCloudSql().withTaskQueue().build();

  private final BillingEmailUtils emailUtils = mock(BillingEmailUtils.class);
  private FakeClock clock = new FakeClock();
  private CloudTasksHelper cloudTasksHelper = new CloudTasksHelper();
  private CloudTasksUtils cloudTasksUtils = cloudTasksHelper.getTestCloudTasksUtils();
  private GenerateInvoicesAction action;

  @BeforeEach
  @Override
  protected void beforeEach() throws Exception {
    super.beforeEach();
    persistResource(Cursor.createGlobal(RECURRING_BILLING, DateTime.parse("2017-11-30TZ")));
  }

  @Test
  void testLaunchTemplateJob_withPublish() throws Exception {
    action =
        new GenerateInvoicesAction(
            "test-project",
            "test-region",
            "staging_bucket",
            "billing_bucket",
            "REG-INV",
            true,
            new YearMonth(2017, 10),
            emailUtils,
            cloudTasksUtils,
            clock,
            response,
            dataflow);
    action.run();
    assertThat(response.getContentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).isEqualTo("Launched invoicing pipeline: jobid");

    cloudTasksHelper.assertTasksEnqueued(
        "beam-reporting",
        new TaskMatcher()
            .url("/_dr/task/publishInvoices")
            .method(HttpMethod.POST)
            .param("jobId", "jobid")
            .param("yearMonth", "2017-10")
            .scheduleTime(
                clock
                    .nowUtc()
                    .plus(Duration.standardMinutes(ReportingModule.ENQUEUE_DELAY_MINUTES))));
  }

  @Test
  void testLaunchTemplateJob_withoutPublish() throws Exception {
    action =
        new GenerateInvoicesAction(
            "test-project",
            "test-region",
            "staging_bucket",
            "billing_bucket",
            "REG-INV",
            false,
            new YearMonth(2017, 10),
            emailUtils,
            cloudTasksUtils,
            clock,
            response,
            dataflow);
    action.run();
    assertThat(response.getContentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).isEqualTo("Launched invoicing pipeline: jobid");
    cloudTasksHelper.assertNoTasksEnqueued("beam-reporting");
  }

  @Test
  void testCaughtIOException() throws IOException {
    when(launch.execute()).thenThrow(new IOException("Pipeline error"));
    action =
        new GenerateInvoicesAction(
            "test-project",
            "test-region",
            "staging_bucket",
            "billing_bucket",
            "REG-INV",
            false,
            new YearMonth(2017, 10),
            emailUtils,
            cloudTasksUtils,
            clock,
            response,
            dataflow);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
    assertThat(response.getPayload()).isEqualTo("Pipeline launch failed: Pipeline error");
    verify(emailUtils).sendAlertEmail("Pipeline Launch failed due to Pipeline error");
    cloudTasksHelper.assertNoTasksEnqueued("beam-reporting");
  }

  @Test
  void testFailsToGenerateInvoicesNotExpandedBillingEvents() throws Exception {
    persistResource(Cursor.createGlobal(RECURRING_BILLING, DateTime.parse("2017-10-30TZ")));
    action =
        new GenerateInvoicesAction(
            "test-project",
            "test-region",
            "staging_bucket",
            "billing_bucket",
            "REG-INV",
            false,
            new YearMonth(2017, 10),
            emailUtils,
            cloudTasksUtils,
            clock,
            response,
            dataflow);
    action.run();
    assertThat(response.getContentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
    assertThat(response.getPayload())
        .isEqualTo(
            "Pipeline launch failed: Latest billing events expansion cycle hasn't finished yet,"
                + " terminating invoicing pipeline");
    cloudTasksHelper.assertNoTasksEnqueued("beam-reporting");
  }
}
