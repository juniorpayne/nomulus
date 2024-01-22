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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.bsa.BsaTransactions.bsaQuery;
import static google.registry.bsa.persistence.Queries.deleteBsaLabelByLabels;
import static google.registry.bsa.persistence.Queries.queryBsaLabelByLabels;
import static google.registry.bsa.persistence.Queries.queryBsaUnblockableDomainByLabels;
import static google.registry.bsa.persistence.Queries.queryNewlyCreatedDomains;
import static google.registry.bsa.persistence.Queries.queryUnblockablesByNames;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.newDomain;
import static google.registry.testing.DatabaseHelper.persistDomainAsDeleted;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.bsa.persistence.BsaUnblockableDomain.Reason;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link Queries}. */
class QueriesTest {

  FakeClock fakeClock = new FakeClock(DateTime.parse("2023-11-09T02:08:57.880Z"));

  @RegisterExtension
  final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  @BeforeEach
  void setup() {
    tm().transact(
            () -> {
              tm().putAll(
                      ImmutableList.of(
                          new BsaLabel("label1", fakeClock.nowUtc()),
                          new BsaLabel("label2", fakeClock.nowUtc()),
                          new BsaLabel("label3", fakeClock.nowUtc())));
              tm().putAll(
                      ImmutableList.of(
                          BsaUnblockableDomain.of("label1.app", Reason.REGISTERED),
                          BsaUnblockableDomain.of("label1.dev", Reason.RESERVED),
                          BsaUnblockableDomain.of("label2.page", Reason.REGISTERED),
                          BsaUnblockableDomain.of("label3.app", Reason.REGISTERED)));
            });
  }

  @Test
  void queryBsaUnblockableDomainByLabels_oneLabel() {
    assertThat(
            tm().transact(
                    () ->
                        queryBsaUnblockableDomainByLabels(ImmutableList.of("label1"))
                            .map(BsaUnblockableDomain::toVkey)
                            .collect(toImmutableList())))
        .containsExactly(
            BsaUnblockableDomain.vKey("label1", "app"), BsaUnblockableDomain.vKey("label1", "dev"));
  }

  @Test
  void queryBsaUnblockableDomainByLabels_twoLabels() {
    assertThat(
            tm().transact(
                    () ->
                        queryBsaUnblockableDomainByLabels(ImmutableList.of("label1", "label2"))
                            .map(BsaUnblockableDomain::toVkey)
                            .collect(toImmutableList())))
        .containsExactly(
            BsaUnblockableDomain.vKey("label1", "app"),
            BsaUnblockableDomain.vKey("label1", "dev"),
            BsaUnblockableDomain.vKey("label2", "page"));
  }

  @Test
  void queryBsaLabelByLabels_oneLabel() {
    assertThat(
            tm().transact(
                    () ->
                        queryBsaLabelByLabels(ImmutableList.of("label1"))
                            .collect(toImmutableList())))
        .containsExactly(new BsaLabel("label1", fakeClock.nowUtc()));
  }

  @Test
  void queryBsaLabelByLabels_twoLabels() {
    assertThat(
            tm().transact(
                    () ->
                        queryBsaLabelByLabels(ImmutableList.of("label1", "label2"))
                            .collect(toImmutableList())))
        .containsExactly(
            new BsaLabel("label1", fakeClock.nowUtc()), new BsaLabel("label2", fakeClock.nowUtc()));
  }

  @Test
  void deleteBsaLabelByLabels_oneLabel() {
    assertThat(tm().transact(() -> deleteBsaLabelByLabels(ImmutableList.of("label1"))))
        .isEqualTo(1);
    assertThat(tm().transact(() -> tm().loadAllOf(BsaLabel.class)))
        .containsExactly(
            new BsaLabel("label2", fakeClock.nowUtc()), new BsaLabel("label3", fakeClock.nowUtc()));
    assertThat(
            tm().transact(
                    () ->
                        tm().loadAllOfStream(BsaUnblockableDomain.class)
                            .map(BsaUnblockableDomain::toVkey)
                            .collect(toImmutableList())))
        .containsExactly(
            BsaUnblockableDomain.vKey("label2", "page"),
            BsaUnblockableDomain.vKey("label3", "app"));
  }

  @Test
  void deleteBsaLabelByLabels_twoLabels() {
    assertThat(tm().transact(() -> deleteBsaLabelByLabels(ImmutableList.of("label1", "label2"))))
        .isEqualTo(2);
    assertThat(tm().transact(() -> tm().loadAllOf(BsaLabel.class)))
        .containsExactly(new BsaLabel("label3", fakeClock.nowUtc()));
    assertThat(
            tm().transact(
                    () ->
                        tm().loadAllOfStream(BsaUnblockableDomain.class)
                            .map(BsaUnblockableDomain::toVkey)
                            .collect(toImmutableList())))
        .containsExactly(BsaUnblockableDomain.vKey("label3", "app"));
  }

  private void setupUnblockableDomains() {
    tm().transact(
            () ->
                tm().insertAll(
                        ImmutableList.of(
                            new BsaLabel("a", fakeClock.nowUtc()),
                            new BsaLabel("b", fakeClock.nowUtc()))));
    BsaUnblockableDomain a1 = new BsaUnblockableDomain("a", "tld1", Reason.RESERVED);
    BsaUnblockableDomain b1 = new BsaUnblockableDomain("b", "tld1", Reason.REGISTERED);
    BsaUnblockableDomain a2 = new BsaUnblockableDomain("a", "tld2", Reason.REGISTERED);
    tm().transact(() -> tm().insertAll(ImmutableList.of(a1, b1, a2)));
  }

  @Test
  void queryUnblockablesByNames_singleName_found() {
    setupUnblockableDomains();
    assertThat(tm().transact(() -> queryUnblockablesByNames(ImmutableSet.of("a.tld1"))))
        .containsExactly("a.tld1");
  }

  @Test
  void queryUnblockablesByNames_singleName_notFound() {
    setupUnblockableDomains();
    assertThat(tm().transact(() -> queryUnblockablesByNames(ImmutableSet.of("c.tld3")))).isEmpty();
  }

  @Test
  void queryUnblockablesByNames_multipleNames() {
    setupUnblockableDomains();
    assertThat(
            tm().transact(
                    () -> queryUnblockablesByNames(ImmutableSet.of("a.tld1", "b.tld1", "c.tld3"))))
        .containsExactly("a.tld1", "b.tld1");
  }

  @Test
  void queryNewlyCreatedDomains_onlyLiveDomainsReturned() {
    DateTime testStartTime = fakeClock.nowUtc();
    createTlds("tld");
    persistNewRegistrar("TheRegistrar");
    // time 0:
    persistResource(
        newDomain("d1.tld").asBuilder().setCreationTimeForTest(fakeClock.nowUtc()).build());
    // time 0, deletion time 1
    persistDomainAsDeleted(
        newDomain("will-delete.tld").asBuilder().setCreationTimeForTest(fakeClock.nowUtc()).build(),
        fakeClock.nowUtc().plusMillis(1));
    fakeClock.advanceOneMilli();
    // time 1
    persistResource(
        newDomain("d2.tld").asBuilder().setCreationTimeForTest(fakeClock.nowUtc()).build());
    fakeClock.advanceOneMilli();
    // Now is time 2
    assertThat(
            bsaQuery(
                () ->
                    queryNewlyCreatedDomains(
                        ImmutableList.of("tld"), testStartTime, fakeClock.nowUtc())))
        .containsExactly("d1.tld", "d2.tld");
  }

  @Test
  void queryNewlyCreatedDomains_onlyDomainsAfterMinCreationTimeReturned() {
    DateTime testStartTime = fakeClock.nowUtc();
    createTlds("tld");
    persistNewRegistrar("TheRegistrar");
    // time 0:
    persistResource(
        newDomain("d1.tld").asBuilder().setCreationTimeForTest(fakeClock.nowUtc()).build());
    // time 0, deletion time 1
    persistDomainAsDeleted(
        newDomain("will-delete.tld").asBuilder().setCreationTimeForTest(fakeClock.nowUtc()).build(),
        fakeClock.nowUtc().plusMillis(1));
    fakeClock.advanceOneMilli();
    // time 1
    persistResource(
        newDomain("d2.tld").asBuilder().setCreationTimeForTest(fakeClock.nowUtc()).build());
    fakeClock.advanceOneMilli();
    // Now is time 2, ask for domains created since time 1
    assertThat(
            bsaQuery(
                () ->
                    queryNewlyCreatedDomains(
                        ImmutableList.of("tld"), testStartTime.plusMillis(1), fakeClock.nowUtc())))
        .containsExactly("d2.tld");
  }

  @Test
  void queryNewlyCreatedDomains_onlyDomainsInRequestedTldsReturned() {
    DateTime testStartTime = fakeClock.nowUtc();
    createTlds("tld", "tld2");
    persistNewRegistrar("TheRegistrar");
    persistResource(
        newDomain("d1.tld").asBuilder().setCreationTimeForTest(fakeClock.nowUtc()).build());
    persistResource(
        newDomain("d2.tld2").asBuilder().setCreationTimeForTest(fakeClock.nowUtc()).build());
    fakeClock.advanceOneMilli();
    assertThat(
            bsaQuery(
                () ->
                    queryNewlyCreatedDomains(
                        ImmutableList.of("tld"), testStartTime, fakeClock.nowUtc())))
        .containsExactly("d1.tld");
  }
}
