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

package google.registry.reporting.icann;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.testing.AppEngineExtension;
import org.joda.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ActivityReportingQueryBuilder}. */
class ActivityReportingQueryBuilderTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withCloudSql().withLocalModules().withTaskQueue().build();

  private final YearMonth yearMonth = new YearMonth(2017, 9);

  private ActivityReportingQueryBuilder createQueryBuilder(String datasetName) {
    return new ActivityReportingQueryBuilder(
        "domain-registry-alpha",
        datasetName,
        new BasicDnsCountQueryCoordinator(
            new BasicDnsCountQueryCoordinator.Params(null, "domain-registry-alpha", datasetName)));
  }

  @Test
  void testAggregateQueryMatch_cloudSql() {
    ActivityReportingQueryBuilder queryBuilder = createQueryBuilder("cloud_sql_icann_reporting");
    assertThat(queryBuilder.getReportQuery(yearMonth))
        .isEqualTo(
            "#standardSQL\n"
                + "SELECT * FROM "
                + "`domain-registry-alpha.cloud_sql_icann_reporting.activity_report_aggregation_201709`");
  }

  @Test
  void testIntermediaryQueryMatch_cloud_sql() {
    ImmutableList<String> expectedQueryNames =
        ImmutableList.of(
            ActivityReportingQueryBuilder.REGISTRAR_OPERATING_STATUS,
            ActivityReportingQueryBuilder.MONTHLY_LOGS,
            ActivityReportingQueryBuilder.DNS_COUNTS,
            ActivityReportingQueryBuilder.EPP_METRICS,
            ActivityReportingQueryBuilder.WHOIS_COUNTS,
            ActivityReportingQueryBuilder.ACTIVITY_REPORT_AGGREGATION);

    ActivityReportingQueryBuilder queryBuilder = createQueryBuilder("cloud_sql_icann_reporting");
    ImmutableMap<String, String> actualQueries = queryBuilder.getViewQueryMap(yearMonth);
    for (String queryName : expectedQueryNames) {
      String actualTableName = String.format("%s_201709", queryName);
      String testFilename = String.format("%s_test_cloud_sql.sql", queryName);
      assertThat(actualQueries.get(actualTableName))
          .isEqualTo(ReportingTestData.loadFile(testFilename));
    }
  }
}
