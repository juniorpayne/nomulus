// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.smd;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.config.RegistryEnvironment;
import google.registry.model.EntityTestCase;
import google.registry.model.common.DatabaseTransitionSchedule;
import google.registry.model.common.DatabaseTransitionSchedule.PrimaryDatabase;
import google.registry.model.common.DatabaseTransitionSchedule.PrimaryDatabaseTransition;
import google.registry.model.common.DatabaseTransitionSchedule.TransitionId;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationWithCoverageExtension;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.SystemPropertyExtension;
import google.registry.testing.TestOfyAndSql;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;

@DualDatabaseTest
public class SignedMarkRevocationListDaoTest extends EntityTestCase {

  @RegisterExtension
  final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestRules.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  @RegisterExtension
  @Order(value = 1)
  final DatastoreEntityExtension datastoreEntityExtension = new DatastoreEntityExtension();

  @RegisterExtension
  @Order(value = Integer.MAX_VALUE)
  final SystemPropertyExtension systemPropertyExtension = new SystemPropertyExtension();

  @BeforeEach
  void setup() {
    fakeClock.setTo(DateTime.parse("1984-12-21T00:00:00.000Z"));
    DatabaseTransitionSchedule schedule =
        DatabaseTransitionSchedule.create(
            TransitionId.SIGNED_MARK_REVOCATION_LIST,
            TimedTransitionProperty.fromValueMap(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    PrimaryDatabase.DATASTORE,
                    fakeClock.nowUtc().plusDays(1),
                    PrimaryDatabase.CLOUD_SQL),
                PrimaryDatabaseTransition.class));

    tm().transactNew(() -> ofy().saveWithoutBackup().entity(schedule).now());
  }

  @TestOfyAndSql
  void testSave_datastorePrimary_success() {
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(
            fakeClock.nowUtc(), ImmutableMap.of("mark", fakeClock.nowUtc().minusHours(1)));
    SignedMarkRevocationListDao.save(list);
    SignedMarkRevocationList fromDb = SignedMarkRevocationListDao.load();
    assertAboutImmutableObjects().that(fromDb).isEqualExceptFields(list, "revisionId");
  }

  @TestOfyAndSql
  void testSave_cloudSqlPrimary_success() {
    fakeClock.advanceBy(Duration.standardDays(5));
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(
            fakeClock.nowUtc(), ImmutableMap.of("mark", fakeClock.nowUtc().minusHours(1)));
    SignedMarkRevocationListDao.save(list);
    SignedMarkRevocationList fromDb = SignedMarkRevocationListDao.load();
    assertAboutImmutableObjects().that(fromDb).isEqualExceptFields(list);
  }

  @TestOfyAndSql
  void testSaveAndLoad_datastorePrimary_emptyList() {
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(START_OF_TIME, ImmutableMap.of());
    SignedMarkRevocationListDao.save(list);
    SignedMarkRevocationList fromDb = SignedMarkRevocationListDao.load();
    assertAboutImmutableObjects().that(fromDb).isEqualExceptFields(list, "revisionId");
  }

  @TestOfyAndSql
  void testSaveAndLoad_cloudSqlPrimary_emptyList() {
    fakeClock.advanceBy(Duration.standardDays(5));
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(fakeClock.nowUtc(), ImmutableMap.of());
    SignedMarkRevocationListDao.save(list);
    SignedMarkRevocationList fromDb = SignedMarkRevocationListDao.load();
    assertAboutImmutableObjects().that(fromDb).isEqualExceptFields(list, "revisionId");
  }

  @TestOfyAndSql
  void testSave_datastorePrimary_multipleVersions() {
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(
            fakeClock.nowUtc(), ImmutableMap.of("mark", fakeClock.nowUtc().minusHours(1)));
    SignedMarkRevocationListDao.save(list);
    assertThat(SignedMarkRevocationListDao.load().isSmdRevoked("mark", fakeClock.nowUtc()))
        .isTrue();

    // Now remove the revocation
    SignedMarkRevocationList secondList =
        SignedMarkRevocationList.create(fakeClock.nowUtc(), ImmutableMap.of());
    SignedMarkRevocationListDao.save(secondList);
    assertThat(SignedMarkRevocationListDao.load().isSmdRevoked("mark", fakeClock.nowUtc()))
        .isFalse();
  }

  @TestOfyAndSql
  void testSave_cloudSqlPrimary_multipleVersions() {
    fakeClock.advanceBy(Duration.standardDays(5));
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(
            fakeClock.nowUtc(), ImmutableMap.of("mark", fakeClock.nowUtc().minusHours(1)));
    SignedMarkRevocationListDao.save(list);
    assertThat(SignedMarkRevocationListDao.load().isSmdRevoked("mark", fakeClock.nowUtc()))
        .isTrue();

    // Now remove the revocation
    SignedMarkRevocationList secondList =
        SignedMarkRevocationList.create(fakeClock.nowUtc(), ImmutableMap.of());
    SignedMarkRevocationListDao.save(secondList);
    assertThat(SignedMarkRevocationListDao.load().isSmdRevoked("mark", fakeClock.nowUtc()))
        .isFalse();
  }

  @TestOfyAndSql
  void testLoad_datastorePrimary_unequalLists() {
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(
            fakeClock.nowUtc(), ImmutableMap.of("mark", fakeClock.nowUtc().minusHours(1)));
    SignedMarkRevocationListDao.save(list);
    SignedMarkRevocationList list2 =
        SignedMarkRevocationList.create(
            fakeClock.nowUtc(), ImmutableMap.of("mark", fakeClock.nowUtc().minusHours(3)));
    jpaTm().transact(() -> jpaTm().put(list2));
    RuntimeException thrown =
        assertThrows(RuntimeException.class, SignedMarkRevocationListDao::load);
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "SMD mark has key 1984-12-20T23:00:00.000Z in DATASTORE and key"
                + " 1984-12-20T21:00:00.000Z in secondary database.");
  }

  @TestOfyAndSql
  void testLoad_cloudSqlPrimary_unequalLists() {
    fakeClock.advanceBy(Duration.standardDays(5));
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(
            fakeClock.nowUtc(), ImmutableMap.of("mark", fakeClock.nowUtc().minusHours(1)));
    SignedMarkRevocationListDao.save(list);
    SignedMarkRevocationList list2 =
        SignedMarkRevocationList.create(
            fakeClock.nowUtc(), ImmutableMap.of("mark", fakeClock.nowUtc().minusHours(3)));
    jpaTm().transact(() -> jpaTm().put(list2));
    RuntimeException thrown =
        assertThrows(RuntimeException.class, SignedMarkRevocationListDao::load);
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "SMD mark has key 1984-12-25T21:00:00.000Z in CLOUD_SQL and key"
                + " 1984-12-25T23:00:00.000Z in secondary database.");
  }

  @TestOfyAndSql
  void testLoad_cloudSqlPrimary_unequalLists_succeedsInProduction() {
    RegistryEnvironment.PRODUCTION.setup(systemPropertyExtension);
    fakeClock.advanceBy(Duration.standardDays(5));
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(
            fakeClock.nowUtc(), ImmutableMap.of("mark", fakeClock.nowUtc().minusHours(1)));
    SignedMarkRevocationListDao.save(list);
    SignedMarkRevocationList list2 =
        SignedMarkRevocationList.create(
            fakeClock.nowUtc(), ImmutableMap.of("mark", fakeClock.nowUtc().minusHours(3)));
    jpaTm().transact(() -> jpaTm().put(list2));
    SignedMarkRevocationList fromDb = SignedMarkRevocationListDao.load();
    assertAboutImmutableObjects().that(fromDb).isEqualExceptFields(list2, "revisionId");
  }

  @TestOfyAndSql
  void testLoad_datastorePrimary_noListInCloudSql() {
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(
            fakeClock.nowUtc(), ImmutableMap.of("mark", fakeClock.nowUtc().minusHours(1)));
    SignedMarkRevocationListDao.save(list);
    jpaTm().transact(() -> jpaTm().delete(list));
    RuntimeException thrown =
        assertThrows(RuntimeException.class, SignedMarkRevocationListDao::load);
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "SignedMarkRevocationList in Cloud SQL is empty while it is not empty in the"
                + " primary database.");
  }

  @TestOfyAndSql
  void testLoad_cloudSqlPrimary_noListInDatastore() {
    fakeClock.advanceBy(Duration.standardDays(5));
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(
            fakeClock.nowUtc(), ImmutableMap.of("mark", fakeClock.nowUtc().minusHours(1)));
    jpaTm().transact(() -> jpaTm().put(list));
    RuntimeException thrown =
        assertThrows(RuntimeException.class, SignedMarkRevocationListDao::load);
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "SignedMarkRevocationList in Datastore is empty while it is not empty in the"
                + " primary database.");
  }
}
