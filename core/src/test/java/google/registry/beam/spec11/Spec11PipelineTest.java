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

package google.registry.beam.spec11;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.AppEngineExtension.makeRegistrar1;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.google.common.truth.Correspondence;
import com.google.common.truth.Correspondence.BinaryPredicate;
import google.registry.beam.TestPipelineExtension;
import google.registry.beam.spec11.SafeBrowsingTransforms.EvaluateSafeBrowsingFn;
import google.registry.beam.spec11.SafeBrowsingTransformsTest.HttpResponder;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.registrar.Registrar;
import google.registry.model.reporting.Spec11ThreatMatch;
import google.registry.model.reporting.Spec11ThreatMatch.ThreatType;
import google.registry.model.reporting.Spec11ThreatMatchDao;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationTestExtension;
import google.registry.persistence.transaction.TransactionManager;
import google.registry.persistence.transaction.TransactionManagerFactory;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.util.ResourceUtils;
import google.registry.util.Retrier;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link Spec11Pipeline}.
 *
 * <p>Unfortunately there is no emulator for BigQuery like that for Datastore or App Engine.
 * Therefore we cannot fully test the pipeline but only test the two separate sink IO functions,
 * assuming that date is sourcede correctly the {@code BigQueryIO}.
 */
class Spec11PipelineTest {

  private static final DateTime START_TIME = DateTime.parse("2020-01-27T00:00:00.0Z");
  private final FakeClock fakeClock = new FakeClock(START_TIME);

  private static final String DATE = "2020-01-27";
  private static final String SAFE_BROWSING_API_KEY = "api-key";
  private static final String REPORTING_BUCKET_URL = "reporting_bucket";
  private final CloseableHttpClient mockHttpClient =
      mock(CloseableHttpClient.class, withSettings().serializable());

  private static final ImmutableList<Subdomain> SUBDOMAINS =
      ImmutableList.of(
          Subdomain.create("111.com", "123456789-COM", "hello-registrar", "email@hello.net"),
          Subdomain.create("party-night.net", "2244AABBC-NET", "kitty-registrar", "contact@kit.ty"),
          Subdomain.create("bitcoin.bank", "1C3D5E7F9-BANK", "hello-registrar", "email@hello.net"),
          Subdomain.create("no-email.com", "2A4BA9BBC-COM", "kitty-registrar", "contact@kit.ty"),
          Subdomain.create(
              "anti-anti-anti-virus.dev", "555666888-DEV", "cool-registrar", "cool@aid.net"));

  private static final ImmutableList<ThreatMatch> THREAT_MATCHES =
      ImmutableList.of(
          ThreatMatch.create("MALWARE", "111.com"),
          ThreatMatch.create("SOCIAL_ENGINEERING", "party-night.net"),
          ThreatMatch.create("POTENTIALLY_HARMFUL_APPLICATION", "bitcoin.bank"),
          ThreatMatch.create("THREAT_TYPE_UNSPECIFIED", "no-eamil.com"),
          ThreatMatch.create("UNWANTED_SOFTWARE", "anti-anti-anti-virus.dev"));

  // This extension is only needed because Spec11ThreatMatch uses Ofy to generate the ID. Can be
  // removed after the SQL migration.
  @RegisterExtension
  @Order(Order.DEFAULT - 1)
  final transient DatastoreEntityExtension datastore = new DatastoreEntityExtension();

  @TempDir Path tmpDir;

  @RegisterExtension
  final TestPipelineExtension pipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  @RegisterExtension
  final JpaIntegrationTestExtension database =
      new JpaTestRules.Builder().withClock(new FakeClock()).buildIntegrationTestRule();

  private final Spec11PipelineOptions options =
      PipelineOptionsFactory.create().as(Spec11PipelineOptions.class);

  private File reportingBucketUrl;
  private PCollection<KV<Subdomain, ThreatMatch>> threatMatches;

  ImmutableSet<Spec11ThreatMatch> sqlThreatMatches;
  TransactionManager tm;

  @BeforeEach
  void beforeEach() throws Exception {
    tm = tm();
    TransactionManagerFactory.setTm(jpaTm());
    reportingBucketUrl = Files.createDirectory(tmpDir.resolve(REPORTING_BUCKET_URL)).toFile();
    options.setDate(DATE);
    options.setSafeBrowsingApiKey(SAFE_BROWSING_API_KEY);
    options.setReportingBucketUrl(reportingBucketUrl.getAbsolutePath());
    options.setDatabase("DATASTORE");
    threatMatches =
        pipeline.apply(
            Create.of(
                    Streams.zip(SUBDOMAINS.stream(), THREAT_MATCHES.stream(), KV::of)
                        .collect(toImmutableList()))
                .withCoder(
                    KvCoder.of(
                        SerializableCoder.of(Subdomain.class),
                        SerializableCoder.of(ThreatMatch.class))));

    sqlThreatMatches =
        ImmutableSet.of(
            new Spec11ThreatMatch.Builder()
                .setDomainName("111.com")
                .setDomainRepoId("123456789-COM")
                .setRegistrarId("hello-registrar")
                .setCheckDate(new LocalDate(2020, 1, 27))
                .setThreatTypes(ImmutableSet.of(ThreatType.MALWARE))
                .build(),
            new Spec11ThreatMatch.Builder()
                .setDomainName("party-night.net")
                .setDomainRepoId("2244AABBC-NET")
                .setRegistrarId("kitty-registrar")
                .setCheckDate(new LocalDate(2020, 1, 27))
                .setThreatTypes(ImmutableSet.of(ThreatType.SOCIAL_ENGINEERING))
                .build(),
            new Spec11ThreatMatch.Builder()
                .setDomainName("bitcoin.bank")
                .setDomainRepoId("1C3D5E7F9-BANK")
                .setRegistrarId("hello-registrar")
                .setCheckDate(new LocalDate(2020, 1, 27))
                .setThreatTypes(ImmutableSet.of(ThreatType.POTENTIALLY_HARMFUL_APPLICATION))
                .build(),
            new Spec11ThreatMatch.Builder()
                .setDomainName("no-email.com")
                .setDomainRepoId("2A4BA9BBC-COM")
                .setRegistrarId("kitty-registrar")
                .setCheckDate(new LocalDate(2020, 1, 27))
                .setThreatTypes(ImmutableSet.of(ThreatType.THREAT_TYPE_UNSPECIFIED))
                .build(),
            new Spec11ThreatMatch.Builder()
                .setDomainName("anti-anti-anti-virus.dev")
                .setDomainRepoId("555666888-DEV")
                .setRegistrarId("cool-registrar")
                .setCheckDate(new LocalDate(2020, 1, 27))
                .setThreatTypes(ImmutableSet.of(ThreatType.UNWANTED_SOFTWARE))
                .build());
  }

  @AfterEach
  void afterEach() {
    TransactionManagerFactory.setTm(tm);
  }

  @Test
  void testSuccess_fullSqlPipeline() throws Exception {
    setupCloudSql();
    options.setDatabase("CLOUD_SQL");
    EvaluateSafeBrowsingFn safeBrowsingFn =
        new EvaluateSafeBrowsingFn(
            SAFE_BROWSING_API_KEY,
            new Retrier(new FakeSleeper(new FakeClock()), 1),
            Suppliers.ofInstance(mockHttpClient));
    when(mockHttpClient.execute(any(HttpPost.class))).thenAnswer(new HttpResponder());
    Spec11Pipeline spec11Pipeline = new Spec11Pipeline(options, safeBrowsingFn);
    spec11Pipeline.setupPipeline(pipeline);
    pipeline.run(options).waitUntilFinish();
    verifySaveToGcs();
    verifySaveToCloudSql();
  }

  @Test
  void testSuccess_saveToSql() {
    Spec11Pipeline.saveToSql(threatMatches, options);
    pipeline.run().waitUntilFinish();
    verifySaveToCloudSql();
  }

  @Test
  void testSuccess_saveToGcs() throws Exception {
    Spec11Pipeline.saveToGcs(threatMatches, options);
    pipeline.run().waitUntilFinish();
    verifySaveToGcs();
  }

  @Test
  void testSuccess_readFromCloudSql() throws Exception {
    setupCloudSql();
    PCollection<Subdomain> subdomains = Spec11Pipeline.readFromCloudSql(pipeline);
    PAssert.that(subdomains).containsInAnyOrder(SUBDOMAINS);
    pipeline.run().waitUntilFinish();
  }

  private void setupCloudSql() {
    persistNewRegistrar("TheRegistrar");
    persistNewRegistrar("NewRegistrar");
    Registrar registrar1 =
        persistResource(
            makeRegistrar1()
                .asBuilder()
                .setClientId("hello-registrar")
                .setEmailAddress("email@hello.net")
                .build());
    Registrar registrar2 =
        persistResource(
            makeRegistrar1()
                .asBuilder()
                .setClientId("kitty-registrar")
                .setEmailAddress("contact@kit.ty")
                .build());
    Registrar registrar3 =
        persistResource(
            makeRegistrar1()
                .asBuilder()
                .setClientId("cool-registrar")
                .setEmailAddress("cool@aid.net")
                .build());

    createTld("com");
    createTld("net");
    createTld("bank");
    createTld("dev");

    ContactResource contact1 = persistActiveContact(registrar1.getClientId());
    ContactResource contact2 = persistActiveContact(registrar2.getClientId());
    ContactResource contact3 = persistActiveContact(registrar3.getClientId());

    persistResource(createDomain("111.com", "123456789-COM", registrar1, contact1));
    persistResource(createDomain("party-night.net", "2244AABBC-NET", registrar2, contact2));
    persistResource(createDomain("bitcoin.bank", "1C3D5E7F9-BANK", registrar1, contact1));
    persistResource(createDomain("no-email.com", "2A4BA9BBC-COM", registrar2, contact2));
    persistResource(
        createDomain("anti-anti-anti-virus.dev", "555666888-DEV", registrar3, contact3));
  }

  private void verifySaveToGcs() throws Exception {
    ImmutableList<String> expectedFileContents =
        ImmutableList.copyOf(
            ResourceUtils.readResourceUtf8(this.getClass(), "test_output.txt").split("\n"));
    ImmutableList<String> resultFileContents = resultFileContents();
    assertThat(resultFileContents.size()).isEqualTo(expectedFileContents.size());
    assertThat(resultFileContents.get(0)).isEqualTo(expectedFileContents.get(0));
    assertThat(resultFileContents.subList(1, resultFileContents.size()))
        .comparingElementsUsing(
            Correspondence.from(
                new ThreatMatchJsonPredicate(), "has fields with unordered threatTypes equal to"))
        .containsExactlyElementsIn(expectedFileContents.subList(1, expectedFileContents.size()));
  }

  private void verifySaveToCloudSql() {
    jpaTm()
        .transact(
            () -> {
              ImmutableList<Spec11ThreatMatch> sqlThreatMatches =
                  Spec11ThreatMatchDao.loadEntriesByDate(jpaTm(), new LocalDate(2020, 1, 27));
              assertThat(sqlThreatMatches)
                  .comparingElementsUsing(immutableObjectCorrespondence("id"))
                  .containsExactlyElementsIn(sqlThreatMatches);
            });
  }

  private DomainBase createDomain(
      String domainName, String repoId, Registrar registrar, ContactResource contact) {
    return new DomainBase.Builder()
        .setDomainName(domainName)
        .setRepoId(repoId)
        .setCreationClientId(registrar.getClientId())
        .setLastEppUpdateTime(fakeClock.nowUtc())
        .setLastEppUpdateClientId(registrar.getClientId())
        .setLastTransferTime(fakeClock.nowUtc())
        .setRegistrant(contact.createVKey())
        .setPersistedCurrentSponsorClientId(registrar.getClientId())
        .setRegistrationExpirationTime(fakeClock.nowUtc().plusYears(1))
        .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("password")))
        .build();
  }

  /** Returns the text contents of a file under the beamBucket/results directory. */
  private ImmutableList<String> resultFileContents() throws Exception {
    File resultFile =
        new File(
            String.format(
                "%s/icann/spec11/2020-01/SPEC11_MONTHLY_REPORT_2020-01-27",
                reportingBucketUrl.getAbsolutePath().toString()));
    return ImmutableList.copyOf(
        ResourceUtils.readResourceUtf8(resultFile.toURI().toURL()).split("\n"));
  }

  private static class ThreatMatchJsonPredicate implements BinaryPredicate<String, String> {
    private static final String THREAT_MATCHES = "threatMatches";

    @Override
    public boolean apply(@Nullable String actual, @Nullable String expected) {
      JSONObject actualJson = new JSONObject(actual);
      JSONObject expectedJson = new JSONObject(expected);
      if (!actualJson.keySet().equals(expectedJson.keySet())) {
        return false;
      }
      for (String key : actualJson.keySet()) {
        if (key.equals(THREAT_MATCHES)) {
          if (ImmutableSet.copyOf(actualJson.getJSONArray(key))
              .equals(ImmutableSet.copyOf(expectedJson.getJSONArray(key)))) {
            return false;
          }
        } else if (!actualJson.get(key).equals(expectedJson.get(key))) {
          return false;
        }
      }
      ;
      return true;
    }
  }
}
