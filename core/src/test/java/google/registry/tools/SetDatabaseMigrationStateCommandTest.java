// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.common.DatabaseMigrationStateSchedule.DEFAULT_TRANSITION_MAP;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import google.registry.testing.DatabaseHelper;
import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link SetDatabaseMigrationStateCommand}. */
public class SetDatabaseMigrationStateCommandTest
    extends CommandTestCase<SetDatabaseMigrationStateCommand> {

  @AfterEach
  void afterEach() {
    DatabaseHelper.removeDatabaseMigrationSchedule();
  }

  @Test
  void testSuccess_setsBasicSchedule() throws Exception {
    assertThat(DatabaseMigrationStateSchedule.get()).isEqualTo(DEFAULT_TRANSITION_MAP);
    assertThat(jpaTm().transact(() -> jpaTm().loadSingleton(DatabaseMigrationStateSchedule.class)))
        .isEmpty();
    runCommandForced("--migration_schedule=1970-01-01T00:00:00.000Z=DATASTORE_ONLY");
    jpaTm()
        .transact(
            () ->
                assertThat(
                        jpaTm()
                            .loadSingleton(DatabaseMigrationStateSchedule.class)
                            .get()
                            .migrationTransitions)
                    .isEqualTo(DEFAULT_TRANSITION_MAP));
    assertThat(DatabaseMigrationStateSchedule.get()).isEqualTo(DEFAULT_TRANSITION_MAP);
  }

  @Test
  void testSuccess_fullSchedule() throws Exception {
    DateTime now = fakeClock.nowUtc();
    DateTime datastorePrimary = now.plusHours(1);
    DateTime datastorePrimaryNoAsync = now.plusHours(2);
    DateTime datastorePrimaryReadOnly = now.plusHours(3);
    DateTime sqlPrimary = now.plusHours(4);
    DateTime sqlOnly = now.plusHours(5);
    runCommandForced(
        String.format(
            "--migration_schedule=%s=DATASTORE_ONLY,%s=DATASTORE_PRIMARY,"
                + "%s=DATASTORE_PRIMARY_NO_ASYNC,%s=DATASTORE_PRIMARY_READ_ONLY,"
                + "%s=SQL_PRIMARY,%s=SQL_ONLY",
            START_OF_TIME,
            datastorePrimary,
            datastorePrimaryNoAsync,
            datastorePrimaryReadOnly,
            sqlPrimary,
            sqlOnly));
    assertThat(DatabaseMigrationStateSchedule.get().toValueMap())
        .containsExactlyEntriesIn(
            ImmutableSortedMap.of(
                START_OF_TIME,
                MigrationState.DATASTORE_ONLY,
                datastorePrimary,
                MigrationState.DATASTORE_PRIMARY,
                datastorePrimaryNoAsync,
                MigrationState.DATASTORE_PRIMARY_NO_ASYNC,
                datastorePrimaryReadOnly,
                MigrationState.DATASTORE_PRIMARY_READ_ONLY,
                sqlPrimary,
                MigrationState.SQL_PRIMARY,
                sqlOnly,
                MigrationState.SQL_ONLY));
  }

  @Test
  void testSuccess_warnsOnChangeSoon() throws Exception {
    DateTime now = fakeClock.nowUtc();
    runCommandForced(
        String.format(
            "--migration_schedule=%s=DATASTORE_ONLY,%s=DATASTORE_PRIMARY",
            START_OF_TIME, now.plusMinutes(1)));
    assertThat(DatabaseMigrationStateSchedule.get().toValueMap())
        .containsExactlyEntriesIn(
            ImmutableSortedMap.of(
                START_OF_TIME,
                MigrationState.DATASTORE_ONLY,
                now.plusMinutes(1),
                MigrationState.DATASTORE_PRIMARY));
    assertInStdout("MAY BE DANGEROUS");
  }

  @Test
  void testSuccess_goesBackward() throws Exception {
    DateTime now = fakeClock.nowUtc();
    runCommandForced(
        String.format(
            "--migration_schedule=%s=DATASTORE_ONLY,%s=DATASTORE_PRIMARY,"
                + "%s=DATASTORE_PRIMARY_NO_ASYNC,%s=DATASTORE_PRIMARY_READ_ONLY,"
                + "%s=DATASTORE_PRIMARY",
            START_OF_TIME, now.plusHours(1), now.plusHours(2), now.plusHours(3), now.plusHours(4)));
    assertThat(DatabaseMigrationStateSchedule.get().toValueMap())
        .containsExactlyEntriesIn(
            ImmutableSortedMap.of(
                START_OF_TIME,
                MigrationState.DATASTORE_ONLY,
                now.plusHours(1),
                MigrationState.DATASTORE_PRIMARY,
                now.plusHours(2),
                MigrationState.DATASTORE_PRIMARY_NO_ASYNC,
                now.plusHours(3),
                MigrationState.DATASTORE_PRIMARY_READ_ONLY,
                now.plusHours(4),
                MigrationState.DATASTORE_PRIMARY));
  }

  @Test
  void testFailure_invalidTransition() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    String.format(
                        "--migration_schedule=%s=DATASTORE_ONLY,%s=DATASTORE_PRIMARY_READ_ONLY",
                        START_OF_TIME, START_OF_TIME.plusHours(1))));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "validStateTransitions map cannot transition from DATASTORE_ONLY "
                + "to DATASTORE_PRIMARY_READ_ONLY.");
  }

  @Test
  void testFailure_invalidTransitionFromOldToNew() {
    // The map we pass in is valid by itself, but we can't go from DATASTORE_ONLY now to
    // DATASTORE_PRIMARY_READ_ONLY now
    DateTime now = fakeClock.nowUtc();
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    String.format(
                        "--migration_schedule=%s=DATASTORE_ONLY,%s=DATASTORE_PRIMARY,"
                            + "%s=DATASTORE_PRIMARY_NO_ASYNC,%s=DATASTORE_PRIMARY_READ_ONLY",
                        START_OF_TIME, now.minusHours(3), now.minusHours(2), now.minusHours(1))));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Cannot transition from current state-as-of-now DATASTORE_ONLY "
                + "to new state-as-of-now DATASTORE_PRIMARY_READ_ONLY");
  }

  @Test
  void testFailure_invalidParams() {
    assertThrows(ParameterException.class, this::runCommandForced);
    assertThrows(ParameterException.class, () -> runCommandForced("--migration_schedule=FOOBAR"));
    assertThrows(
        ParameterException.class,
        () -> runCommandForced("--migration_schedule=1970-01-01T00:00:00.000Z=FOOBAR"));
  }
}
