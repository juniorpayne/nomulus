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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.bsa.api.UnblockableDomain;
import google.registry.bsa.persistence.BsaUnblockableDomain.Reason;
import google.registry.persistence.transaction.DatabaseException;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link BsaUnblockableDomain}. */
public class BsaUnblockableDomainTest {

  FakeClock fakeClock = new FakeClock(DateTime.now(UTC));

  @RegisterExtension
  final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  @Test
  void persist() {
    tm().transact(() -> tm().put(new BsaLabel("label", fakeClock.nowUtc())));
    tm().transact(() -> tm().put(new BsaUnblockableDomain("label", "tld", Reason.REGISTERED)));
    BsaUnblockableDomain persisted =
        tm().transact(() -> tm().loadByKey(BsaUnblockableDomain.vKey("label", "tld")));
    assertThat(persisted.label).isEqualTo("label");
    assertThat(persisted.tld).isEqualTo("tld");
    assertThat(persisted.reason).isEqualTo(Reason.REGISTERED);
  }

  @Test
  void cascadeDeletion() {
    tm().transact(() -> tm().put(new BsaLabel("label", fakeClock.nowUtc())));
    tm().transact(() -> tm().put(new BsaUnblockableDomain("label", "tld", Reason.REGISTERED)));
    assertThat(
            tm().transact(() -> tm().loadByKeyIfPresent(BsaUnblockableDomain.vKey("label", "tld"))))
        .isPresent();
    tm().transact(() -> tm().delete(BsaLabel.vKey("label")));
    assertThat(
            tm().transact(() -> tm().loadByKeyIfPresent(BsaUnblockableDomain.vKey("label", "tld"))))
        .isEmpty();
  }

  @Test
  void insertDomainWithoutLabel() {
    assertThat(
            assertThrows(
                DatabaseException.class,
                () ->
                    tm().transact(
                            () ->
                                tm().put(
                                        new BsaUnblockableDomain(
                                            "label", "tld", Reason.REGISTERED)))))
        .hasMessageThat()
        .contains("violates foreign key constraint");
  }

  @Test
  void reason_convertibleToApiClass() {
    for (BsaUnblockableDomain.Reason reason : BsaUnblockableDomain.Reason.values()) {
      try {
        UnblockableDomain.Reason.valueOf(reason.name());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            String.format(
                "Missing enum name [%s] in %s",
                reason.name(), BsaUnblockableDomain.Reason.class.getName()));
      }
    }
  }
}
