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

package google.registry.model.domain;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.testing.DatabaseHelper.cloneAndSetAutoTimestamps;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.newHostResource;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DomainBaseSubject.assertAboutDomains;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.appengine.api.datastore.Entity;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.collect.Streams;
import com.googlecode.objectify.Key;
import google.registry.model.EntityTestCase;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.poll.PollMessage;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.persistence.VKey;
import java.util.Optional;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DomainBase}. */
public class DomainBaseTest extends EntityTestCase {

  private DomainBase domain;
  private VKey<BillingEvent.OneTime> oneTimeBillKey;
  private VKey<BillingEvent.Recurring> recurringBillKey;
  private Key<HistoryEntry> historyEntryKey;

  @BeforeEach
  void setUp() {
    createTld("com");
    VKey<DomainBase> domainKey = VKey.from(Key.create(null, DomainBase.class, "4-COM"));
    VKey<HostResource> hostKey =
        persistResource(
                new HostResource.Builder()
                    .setHostName("ns1.example.com")
                    .setSuperordinateDomain(domainKey)
                    .setRepoId("1-COM")
                    .build())
            .createVKey();
    VKey<ContactResource> contact1Key =
        persistResource(
                new ContactResource.Builder()
                    .setContactId("contact_id1")
                    .setRepoId("2-COM")
                    .build())
            .createVKey();
    VKey<ContactResource> contact2Key =
        persistResource(
                new ContactResource.Builder()
                    .setContactId("contact_id2")
                    .setRepoId("3-COM")
                    .build())
            .createVKey();
    historyEntryKey =
        Key.create(
            persistResource(new HistoryEntry.Builder().setParent(domainKey.getOfyKey()).build()));
    oneTimeBillKey = VKey.from(Key.create(historyEntryKey, BillingEvent.OneTime.class, 1));
    recurringBillKey = VKey.from(Key.create(historyEntryKey, BillingEvent.Recurring.class, 2));
    VKey<PollMessage.Autorenew> autorenewPollKey =
        VKey.from(Key.create(historyEntryKey, PollMessage.Autorenew.class, 3));
    VKey<PollMessage.OneTime> onetimePollKey =
        VKey.from(Key.create(historyEntryKey, PollMessage.OneTime.class, 1));
    // Set up a new persisted domain entity.
    domain =
        persistResource(
            cloneAndSetAutoTimestamps(
                new DomainBase.Builder()
                    .setDomainName("example.com")
                    .setRepoId("4-COM")
                    .setCreationClientId("a registrar")
                    .setLastEppUpdateTime(fakeClock.nowUtc())
                    .setLastEppUpdateClientId("AnotherRegistrar")
                    .setLastTransferTime(fakeClock.nowUtc())
                    .setStatusValues(
                        ImmutableSet.of(
                            StatusValue.CLIENT_DELETE_PROHIBITED,
                            StatusValue.SERVER_DELETE_PROHIBITED,
                            StatusValue.SERVER_TRANSFER_PROHIBITED,
                            StatusValue.SERVER_UPDATE_PROHIBITED,
                            StatusValue.SERVER_RENEW_PROHIBITED,
                            StatusValue.SERVER_HOLD))
                    .setRegistrant(contact1Key)
                    .setContacts(ImmutableSet.of(DesignatedContact.create(Type.ADMIN, contact2Key)))
                    .setNameservers(ImmutableSet.of(hostKey))
                    .setSubordinateHosts(ImmutableSet.of("ns1.example.com"))
                    .setPersistedCurrentSponsorClientId("losing")
                    .setRegistrationExpirationTime(fakeClock.nowUtc().plusYears(1))
                    .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("password")))
                    .setDsData(
                        ImmutableSet.of(DelegationSignerData.create(1, 2, 3, new byte[] {0, 1, 2})))
                    .setLaunchNotice(
                        LaunchNotice.create("tcnid", "validatorId", START_OF_TIME, START_OF_TIME))
                    .setTransferData(
                        new DomainTransferData.Builder()
                            .setGainingClientId("gaining")
                            .setLosingClientId("losing")
                            .setPendingTransferExpirationTime(fakeClock.nowUtc())
                            .setServerApproveEntities(
                                ImmutableSet.of(oneTimeBillKey, recurringBillKey, autorenewPollKey))
                            .setServerApproveBillingEvent(oneTimeBillKey)
                            .setServerApproveAutorenewEvent(recurringBillKey)
                            .setServerApproveAutorenewPollMessage(autorenewPollKey)
                            .setTransferRequestTime(fakeClock.nowUtc().plusDays(1))
                            .setTransferStatus(TransferStatus.SERVER_APPROVED)
                            .setTransferRequestTrid(Trid.create("client-trid", "server-trid"))
                            .build())
                    .setDeletePollMessage(onetimePollKey)
                    .setAutorenewBillingEvent(recurringBillKey)
                    .setAutorenewPollMessage(autorenewPollKey)
                    .setSmdId("smdid")
                    .addGracePeriod(
                        GracePeriod.create(
                            GracePeriodStatus.ADD,
                            "4-COM",
                            fakeClock.nowUtc().plusDays(1),
                            "registrar",
                            null))
                    .setAutorenewEndTime(Optional.of(fakeClock.nowUtc().plusYears(2)))
                    .build()));
  }

  @Test
  void testGracePeriod_nullIdFromOfy() {
    Entity entity = ofyTm().transact(() -> ofy().save().toEntity(domain));
    entity.setUnindexedProperty("gracePeriods.gracePeriodId", null);
    DomainBase domainFromEntity = ofyTm().transact(() -> ofy().load().fromEntity(entity));
    GracePeriod gracePeriod = domainFromEntity.getGracePeriods().iterator().next();
    assertThat(gracePeriod.gracePeriodId).isNotNull();
  }

  @Test
  void testPersistence() {
    // Note that this only verifies that the value stored under the foreign key is the same as that
    // stored under the primary key ("domain" is the domain loaded from the datastore, not the
    // original domain object).
    assertThat(loadByForeignKey(DomainBase.class, domain.getForeignKey(), fakeClock.nowUtc()))
        .hasValue(domain);
  }

  @Test
  void testVKeyRestoration() {
    assertThat(domain.deletePollMessageHistoryId).isEqualTo(historyEntryKey.getId());
    assertThat(domain.autorenewBillingEventHistoryId).isEqualTo(historyEntryKey.getId());
    assertThat(domain.autorenewPollMessageHistoryId).isEqualTo(historyEntryKey.getId());
    assertThat(domain.getTransferData().getServerApproveBillingEventHistoryId())
        .isEqualTo(historyEntryKey.getId());
    assertThat(domain.getTransferData().getServerApproveAutorenewEventHistoryId())
        .isEqualTo(historyEntryKey.getId());
    assertThat(domain.getTransferData().getServerApproveAutorenewPollMessageHistoryId())
        .isEqualTo(historyEntryKey.getId());
  }

  @Test
  void testIndexing() throws Exception {
    verifyIndexing(
        domain,
        "allContacts.contact",
        "fullyQualifiedDomainName",
        "nsHosts",
        "currentSponsorClientId",
        "deletionTime",
        "tld",
        "autorenewEndTime");
  }

  @Test
  void testEmptyStringsBecomeNull() {
    assertThat(
            newDomainBase("example.com")
                .asBuilder()
                .setPersistedCurrentSponsorClientId(null)
                .build()
                .getCurrentSponsorClientId())
        .isNull();
    assertThat(
            newDomainBase("example.com")
                .asBuilder()
                .setPersistedCurrentSponsorClientId("")
                .build()
                .getCurrentSponsorClientId())
        .isNull();
    assertThat(
            newDomainBase("example.com")
                .asBuilder()
                .setPersistedCurrentSponsorClientId(" ")
                .build()
                .getCurrentSponsorClientId())
        .isNotNull();
  }

  @Test
  void testEmptySetsAndArraysBecomeNull() {
    assertThat(
            newDomainBase("example.com")
                .asBuilder()
                .setNameservers(ImmutableSet.of())
                .build()
                .nsHosts)
        .isNull();
    assertThat(
            newDomainBase("example.com")
                .asBuilder()
                .setNameservers(ImmutableSet.of())
                .build()
                .nsHosts)
        .isNull();
    assertThat(
            newDomainBase("example.com")
                .asBuilder()
                .setNameservers(ImmutableSet.of(newHostResource("foo.example.tld").createVKey()))
                .build()
                .nsHosts)
        .isNotNull();
    // This behavior should also hold true for ImmutableObjects nested in collections.
    assertThat(
            newDomainBase("example.com")
                .asBuilder()
                .setDsData(ImmutableSet.of(DelegationSignerData.create(1, 1, 1, (byte[]) null)))
                .build()
                .getDsData()
                .asList()
                .get(0)
                .getDigest())
        .isNull();
    assertThat(
            newDomainBase("example.com")
                .asBuilder()
                .setDsData(ImmutableSet.of(DelegationSignerData.create(1, 1, 1, new byte[] {})))
                .build()
                .getDsData()
                .asList()
                .get(0)
                .getDigest())
        .isNull();
    assertThat(
            newDomainBase("example.com")
                .asBuilder()
                .setDsData(ImmutableSet.of(DelegationSignerData.create(1, 1, 1, new byte[] {1})))
                .build()
                .getDsData()
                .asList()
                .get(0)
                .getDigest())
        .isNotNull();
  }

  @Test
  void testEmptyTransferDataBecomesNull() {
    DomainBase withNull = newDomainBase("example.com").asBuilder().setTransferData(null).build();
    DomainBase withEmpty = withNull.asBuilder().setTransferData(DomainTransferData.EMPTY).build();
    assertThat(withNull).isEqualTo(withEmpty);
    assertThat(withEmpty.transferData).isNull();
  }

  @Test
  void testImplicitStatusValues() {
    ImmutableSet<VKey<HostResource>> nameservers =
        ImmutableSet.of(newHostResource("foo.example.tld").createVKey());
    StatusValue[] statuses = {StatusValue.OK};
    // OK is implicit if there's no other statuses but there are nameservers.
    assertAboutDomains()
        .that(newDomainBase("example.com").asBuilder().setNameservers(nameservers).build())
        .hasExactlyStatusValues(statuses);
    StatusValue[] statuses1 = {StatusValue.CLIENT_HOLD};
    // If there are other status values, OK should be suppressed. (Domains can't be LINKED.)
    assertAboutDomains()
        .that(
            newDomainBase("example.com")
                .asBuilder()
                .setNameservers(nameservers)
                .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_HOLD))
                .build())
        .hasExactlyStatusValues(statuses1);
    StatusValue[] statuses2 = {StatusValue.CLIENT_HOLD};
    // When OK is suppressed, it should be removed even if it was originally there.
    assertAboutDomains()
        .that(
            newDomainBase("example.com")
                .asBuilder()
                .setNameservers(nameservers)
                .setStatusValues(ImmutableSet.of(StatusValue.OK, StatusValue.CLIENT_HOLD))
                .build())
        .hasExactlyStatusValues(statuses2);
    StatusValue[] statuses3 = {StatusValue.INACTIVE};
    // If there are no nameservers, INACTIVE should be added, which suppresses OK.
    assertAboutDomains()
        .that(newDomainBase("example.com").asBuilder().build())
        .hasExactlyStatusValues(statuses3);
    StatusValue[] statuses4 = {StatusValue.CLIENT_HOLD, StatusValue.INACTIVE};
    // If there are no nameservers but there are status values, INACTIVE should still be added.
    assertAboutDomains()
        .that(
            newDomainBase("example.com")
                .asBuilder()
                .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_HOLD))
                .build())
        .hasExactlyStatusValues(statuses4);
    StatusValue[] statuses5 = {StatusValue.CLIENT_HOLD};
    // If there are nameservers, INACTIVE should be removed even if it was originally there.
    assertAboutDomains()
        .that(
            newDomainBase("example.com")
                .asBuilder()
                .setNameservers(nameservers)
                .setStatusValues(ImmutableSet.of(StatusValue.INACTIVE, StatusValue.CLIENT_HOLD))
                .build())
        .hasExactlyStatusValues(statuses5);
  }

  private void assertTransferred(
      DomainBase domain,
      DateTime newExpirationTime,
      VKey<BillingEvent.Recurring> newAutorenewEvent) {
    assertThat(domain.getTransferData().getTransferStatus())
        .isEqualTo(TransferStatus.SERVER_APPROVED);
    assertThat(domain.getCurrentSponsorClientId()).isEqualTo("winner");
    assertThat(domain.getLastTransferTime()).isEqualTo(fakeClock.nowUtc().plusDays(1));
    assertThat(domain.getRegistrationExpirationTime()).isEqualTo(newExpirationTime);
    assertThat(domain.getAutorenewBillingEvent()).isEqualTo(newAutorenewEvent);
  }

  private void doExpiredTransferTest(DateTime oldExpirationTime) {
    HistoryEntry historyEntry = new HistoryEntry.Builder().setParent(domain).build();
    BillingEvent.OneTime transferBillingEvent =
        persistResource(
            new BillingEvent.OneTime.Builder()
                .setReason(Reason.TRANSFER)
                .setClientId("winner")
                .setTargetId("example.com")
                .setEventTime(fakeClock.nowUtc())
                .setBillingTime(
                    fakeClock
                        .nowUtc()
                        .plusDays(1)
                        .plus(Registry.get("com").getTransferGracePeriodLength()))
                .setCost(Money.of(USD, 11))
                .setPeriodYears(1)
                .setParent(historyEntry)
                .build());
    domain =
        domain
            .asBuilder()
            .setRegistrationExpirationTime(oldExpirationTime)
            .setTransferData(
                domain
                    .getTransferData()
                    .asBuilder()
                    .setTransferStatus(TransferStatus.PENDING)
                    .setTransferRequestTime(fakeClock.nowUtc().minusDays(4))
                    .setPendingTransferExpirationTime(fakeClock.nowUtc().plusDays(1))
                    .setGainingClientId("winner")
                    .setServerApproveBillingEvent(transferBillingEvent.createVKey())
                    .setServerApproveEntities(ImmutableSet.of(transferBillingEvent.createVKey()))
                    .build())
            .addGracePeriod(
                // Okay for billing event to be null since the point of this grace period is just
                // to check that the transfer will clear all existing grace periods.
                GracePeriod.create(
                    GracePeriodStatus.ADD,
                    domain.getRepoId(),
                    fakeClock.nowUtc().plusDays(100),
                    "foo",
                    null))
            .build();
    DomainBase afterTransfer = domain.cloneProjectedAtTime(fakeClock.nowUtc().plusDays(1));
    DateTime newExpirationTime = oldExpirationTime.plusYears(1);
    VKey<BillingEvent.Recurring> serverApproveAutorenewEvent =
        domain.getTransferData().getServerApproveAutorenewEvent();
    assertTransferred(afterTransfer, newExpirationTime, serverApproveAutorenewEvent);
    assertThat(afterTransfer.getGracePeriods())
        .containsExactly(
            GracePeriod.create(
                GracePeriodStatus.TRANSFER,
                domain.getRepoId(),
                fakeClock
                    .nowUtc()
                    .plusDays(1)
                    .plus(Registry.get("com").getTransferGracePeriodLength()),
                "winner",
                transferBillingEvent.createVKey(),
                afterTransfer.getGracePeriods().iterator().next().getGracePeriodId()));
    // If we project after the grace period expires all should be the same except the grace period.
    DomainBase afterGracePeriod =
        domain.cloneProjectedAtTime(
            fakeClock
                .nowUtc()
                .plusDays(2)
                .plus(Registry.get("com").getTransferGracePeriodLength()));
    assertTransferred(afterGracePeriod, newExpirationTime, serverApproveAutorenewEvent);
    assertThat(afterGracePeriod.getGracePeriods()).isEmpty();
  }

  @Test
  void testExpiredTransfer() {
    doExpiredTransferTest(fakeClock.nowUtc().plusMonths(1));
  }

  @Test
  void testExpiredTransfer_autoRenewBeforeTransfer() {
    // Since transfer swallows a preceding autorenew, this should be identical to the regular
    // transfer case (and specifically, the new expiration and grace periods will be the same as if
    // there was no autorenew).
    doExpiredTransferTest(fakeClock.nowUtc().minusDays(1));
  }

  private void setupPendingTransferDomain(
      DateTime oldExpirationTime, DateTime transferRequestTime, DateTime transferSuccessTime) {
    domain =
        domain
            .asBuilder()
            .setRegistrationExpirationTime(oldExpirationTime)
            .setTransferData(
                domain
                    .getTransferData()
                    .asBuilder()
                    .setTransferStatus(TransferStatus.PENDING)
                    .setTransferRequestTime(transferRequestTime)
                    .setPendingTransferExpirationTime(transferSuccessTime)
                    .build())
            .setLastEppUpdateTime(transferRequestTime)
            .setLastEppUpdateClientId(domain.getTransferData().getGainingClientId())
            .build();
  }

  @Test
  void testEppLastUpdateTimeAndClientId_autoRenewBeforeTransferSuccess() {
    DateTime now = fakeClock.nowUtc();
    DateTime transferRequestDateTime = now.plusDays(1);
    DateTime autorenewDateTime = now.plusDays(3);
    DateTime transferSuccessDateTime = now.plusDays(5);
    setupPendingTransferDomain(autorenewDateTime, transferRequestDateTime, transferSuccessDateTime);

    DomainBase beforeAutoRenew = domain.cloneProjectedAtTime(autorenewDateTime.minusDays(1));
    assertThat(beforeAutoRenew.getLastEppUpdateTime()).isEqualTo(transferRequestDateTime);
    assertThat(beforeAutoRenew.getLastEppUpdateClientId()).isEqualTo("gaining");

    // If autorenew happens before transfer succeeds(before transfer grace period starts as well),
    // lastEppUpdateClientId should still be the current sponsor client id
    DomainBase afterAutoRenew = domain.cloneProjectedAtTime(autorenewDateTime.plusDays(1));
    assertThat(afterAutoRenew.getLastEppUpdateTime()).isEqualTo(autorenewDateTime);
    assertThat(afterAutoRenew.getLastEppUpdateClientId()).isEqualTo("losing");
  }

  @Test
  void testEppLastUpdateTimeAndClientId_autoRenewAfterTransferSuccess() {
    DateTime now = fakeClock.nowUtc();
    DateTime transferRequestDateTime = now.plusDays(1);
    DateTime autorenewDateTime = now.plusDays(3);
    DateTime transferSuccessDateTime = now.plusDays(5);
    setupPendingTransferDomain(autorenewDateTime, transferRequestDateTime, transferSuccessDateTime);

    DomainBase beforeAutoRenew = domain.cloneProjectedAtTime(autorenewDateTime.minusDays(1));
    assertThat(beforeAutoRenew.getLastEppUpdateTime()).isEqualTo(transferRequestDateTime);
    assertThat(beforeAutoRenew.getLastEppUpdateClientId()).isEqualTo("gaining");

    DomainBase afterTransferSuccess =
        domain.cloneProjectedAtTime(transferSuccessDateTime.plusDays(1));
    assertThat(afterTransferSuccess.getLastEppUpdateTime()).isEqualTo(transferSuccessDateTime);
    assertThat(afterTransferSuccess.getLastEppUpdateClientId()).isEqualTo("gaining");
  }

  private void setupUnmodifiedDomain(DateTime oldExpirationTime) {
    domain =
        domain
            .asBuilder()
            .setRegistrationExpirationTime(oldExpirationTime)
            .setTransferData(DomainTransferData.EMPTY)
            .setGracePeriods(ImmutableSet.of())
            .setLastEppUpdateTime(null)
            .setLastEppUpdateClientId(null)
            .build();
  }

  @Test
  void testEppLastUpdateTimeAndClientId_isSetCorrectlyWithNullPreviousValue() {
    DateTime now = fakeClock.nowUtc();
    DateTime autorenewDateTime = now.plusDays(3);
    setupUnmodifiedDomain(autorenewDateTime);

    DomainBase beforeAutoRenew = domain.cloneProjectedAtTime(autorenewDateTime.minusDays(1));
    assertThat(beforeAutoRenew.getLastEppUpdateTime()).isEqualTo(null);
    assertThat(beforeAutoRenew.getLastEppUpdateClientId()).isEqualTo(null);

    DomainBase afterAutoRenew = domain.cloneProjectedAtTime(autorenewDateTime.plusDays(1));
    assertThat(afterAutoRenew.getLastEppUpdateTime()).isEqualTo(autorenewDateTime);
    assertThat(afterAutoRenew.getLastEppUpdateClientId()).isEqualTo("losing");
  }

  @Test
  void testStackedGracePeriods() {
    ImmutableList<GracePeriod> gracePeriods =
        ImmutableList.of(
            GracePeriod.create(
                GracePeriodStatus.ADD,
                domain.getRepoId(),
                fakeClock.nowUtc().plusDays(3),
                "foo",
                null),
            GracePeriod.create(
                GracePeriodStatus.ADD,
                domain.getRepoId(),
                fakeClock.nowUtc().plusDays(2),
                "bar",
                null),
            GracePeriod.create(
                GracePeriodStatus.ADD,
                domain.getRepoId(),
                fakeClock.nowUtc().plusDays(1),
                "baz",
                null));
    domain = domain.asBuilder().setGracePeriods(ImmutableSet.copyOf(gracePeriods)).build();
    for (int i = 1; i < 3; ++i) {
      assertThat(domain.cloneProjectedAtTime(fakeClock.nowUtc().plusDays(i)).getGracePeriods())
          .containsExactlyElementsIn(Iterables.limit(gracePeriods, 3 - i));
    }
  }

  @Test
  void testGracePeriodsByType() {
    ImmutableSet<GracePeriod> addGracePeriods =
        ImmutableSet.of(
            GracePeriod.create(
                GracePeriodStatus.ADD,
                domain.getRepoId(),
                fakeClock.nowUtc().plusDays(3),
                "foo",
                null),
            GracePeriod.create(
                GracePeriodStatus.ADD,
                domain.getRepoId(),
                fakeClock.nowUtc().plusDays(1),
                "baz",
                null));
    ImmutableSet<GracePeriod> renewGracePeriods =
        ImmutableSet.of(
            GracePeriod.create(
                GracePeriodStatus.RENEW,
                domain.getRepoId(),
                fakeClock.nowUtc().plusDays(3),
                "foo",
                null),
            GracePeriod.create(
                GracePeriodStatus.RENEW,
                domain.getRepoId(),
                fakeClock.nowUtc().plusDays(1),
                "baz",
                null));
    domain =
        domain
            .asBuilder()
            .setGracePeriods(
                Streams.concat(addGracePeriods.stream(), renewGracePeriods.stream())
                    .collect(toImmutableSet()))
            .build();
    assertThat(domain.getGracePeriodsOfType(GracePeriodStatus.ADD)).isEqualTo(addGracePeriods);
    assertThat(domain.getGracePeriodsOfType(GracePeriodStatus.RENEW)).isEqualTo(renewGracePeriods);
    assertThat(domain.getGracePeriodsOfType(GracePeriodStatus.TRANSFER)).isEmpty();
  }

  @Test
  void testRenewalsHappenAtExpiration() {
    DomainBase renewed = domain.cloneProjectedAtTime(domain.getRegistrationExpirationTime());
    assertThat(renewed.getRegistrationExpirationTime())
        .isEqualTo(domain.getRegistrationExpirationTime().plusYears(1));
    assertThat(renewed.getLastEppUpdateTime()).isEqualTo(domain.getRegistrationExpirationTime());
    assertThat(getOnlyElement(renewed.getGracePeriods()).getType())
        .isEqualTo(GracePeriodStatus.AUTO_RENEW);
  }

  @Test
  void testTldGetsSet() {
    createTld("tld");
    domain = newDomainBase("foo.tld");
    assertThat(domain.getTld()).isEqualTo("tld");
  }

  @Test
  void testRenewalsDontHappenOnFebruary29() {
    domain =
        domain
            .asBuilder()
            .setRegistrationExpirationTime(DateTime.parse("2004-02-29T22:00:00.0Z"))
            .build();
    DomainBase renewed =
        domain.cloneProjectedAtTime(domain.getRegistrationExpirationTime().plusYears(4));
    assertThat(renewed.getRegistrationExpirationTime().getDayOfMonth()).isEqualTo(28);
  }

  @Test
  void testMultipleAutoRenews() {
    // Change the registry so that renewal costs change every year to make sure we are using the
    // autorenew time as the lookup time for the cost.
    DateTime oldExpirationTime = domain.getRegistrationExpirationTime();
    persistResource(
        Registry.get("com")
            .asBuilder()
            .setRenewBillingCostTransitions(
                new ImmutableSortedMap.Builder<DateTime, Money>(Ordering.natural())
                    .put(START_OF_TIME, Money.of(USD, 1))
                    .put(oldExpirationTime.plusMillis(1), Money.of(USD, 2))
                    .put(oldExpirationTime.plusYears(1).plusMillis(1), Money.of(USD, 3))
                    // Surround the third autorenew with price changes right before and after just
                    // to be 100% sure that we lookup the cost at the expiration time.
                    .put(oldExpirationTime.plusYears(2).minusMillis(1), Money.of(USD, 4))
                    .put(oldExpirationTime.plusYears(2).plusMillis(1), Money.of(USD, 5))
                    .build())
            .build());
    DomainBase renewedThreeTimes = domain.cloneProjectedAtTime(oldExpirationTime.plusYears(2));
    assertThat(renewedThreeTimes.getRegistrationExpirationTime())
        .isEqualTo(oldExpirationTime.plusYears(3));
    assertThat(renewedThreeTimes.getLastEppUpdateTime()).isEqualTo(oldExpirationTime.plusYears(2));
    assertThat(renewedThreeTimes.getGracePeriods())
        .containsExactly(
            GracePeriod.createForRecurring(
                GracePeriodStatus.AUTO_RENEW,
                domain.getRepoId(),
                oldExpirationTime
                    .plusYears(2)
                    .plus(Registry.get("com").getAutoRenewGracePeriodLength()),
                renewedThreeTimes.getCurrentSponsorClientId(),
                renewedThreeTimes.autorenewBillingEvent,
                renewedThreeTimes.getGracePeriods().iterator().next().getGracePeriodId()));
  }

  @Test
  void testToHydratedString_notCircular() {
    domain.toHydratedString(); // If there are circular references, this will overflow the stack.
  }

  @Test
  void testFailure_uppercaseDomainName() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> domain.asBuilder().setDomainName("AAA.BBB"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Domain name must be in puny-coded, lower-case form");
  }

  @Test
  void testFailure_utf8DomainName() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> domain.asBuilder().setDomainName("みんな.みんな"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Domain name must be in puny-coded, lower-case form");
  }

  @Test
  void testClone_doNotExtendExpirationOnDeletedDomain() {
    DateTime now = DateTime.now(UTC);
    domain =
        persistResource(
            domain
                .asBuilder()
                .setRegistrationExpirationTime(now.minusDays(1))
                .setDeletionTime(now.minusDays(10))
                .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE, StatusValue.INACTIVE))
                .build());
    assertThat(domain.cloneProjectedAtTime(now).getRegistrationExpirationTime())
        .isEqualTo(now.minusDays(1));
  }

  @Test
  void testClone_doNotExtendExpirationOnFutureDeletedDomain() {
    // if a domain is in pending deletion (StatusValue.PENDING_DELETE), don't extend expiration
    DateTime now = DateTime.now(UTC);
    domain =
        persistResource(
            domain
                .asBuilder()
                .setRegistrationExpirationTime(now.plusDays(1))
                .setDeletionTime(now.plusDays(20))
                .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE, StatusValue.INACTIVE))
                .build());
    assertThat(domain.cloneProjectedAtTime(now).getRegistrationExpirationTime())
        .isEqualTo(now.plusDays(1));
  }

  @Test
  void testClone_extendsExpirationForExpiredTransferredDomain() {
    // If the transfer implicitly succeeded, the expiration time should be extended
    DateTime now = DateTime.now(UTC);
    DateTime transferExpirationTime = now.minusDays(1);
    DateTime previousExpiration = now.minusDays(2);

    DomainTransferData transferData =
        new DomainTransferData.Builder()
            .setPendingTransferExpirationTime(transferExpirationTime)
            .setTransferStatus(TransferStatus.PENDING)
            .setGainingClientId("TheRegistrar")
            .build();
    Period extensionPeriod = transferData.getTransferPeriod();
    DateTime newExpiration = previousExpiration.plusYears(extensionPeriod.getValue());
    domain =
        persistResource(
            domain
                .asBuilder()
                .setRegistrationExpirationTime(previousExpiration)
                .setTransferData(transferData)
                .build());

    assertThat(domain.cloneProjectedAtTime(now).getRegistrationExpirationTime())
        .isEqualTo(newExpiration);
  }

  @Test
  void testClone_extendsExpirationForNonExpiredTransferredDomain() {
    // If the transfer implicitly succeeded, the expiration time should be extended even if it
    // hadn't already expired
    DateTime now = DateTime.now(UTC);
    DateTime transferExpirationTime = now.minusDays(1);
    DateTime previousExpiration = now.plusWeeks(2);

    DomainTransferData transferData =
        new DomainTransferData.Builder()
            .setPendingTransferExpirationTime(transferExpirationTime)
            .setTransferStatus(TransferStatus.PENDING)
            .setGainingClientId("TheRegistrar")
            .build();
    Period extensionPeriod = transferData.getTransferPeriod();
    DateTime newExpiration = previousExpiration.plusYears(extensionPeriod.getValue());
    domain =
        persistResource(
            domain
                .asBuilder()
                .setRegistrationExpirationTime(previousExpiration)
                .setTransferData(transferData)
                .build());

    assertThat(domain.cloneProjectedAtTime(now).getRegistrationExpirationTime())
        .isEqualTo(newExpiration);
  }

  @Test
  void testClone_doesNotExtendExpirationForPendingTransfer() {
    // Pending transfers shouldn't affect the expiration time
    DateTime now = DateTime.now(UTC);
    DateTime transferExpirationTime = now.plusDays(1);
    DateTime previousExpiration = now.plusWeeks(2);

    DomainTransferData transferData =
        new DomainTransferData.Builder()
            .setPendingTransferExpirationTime(transferExpirationTime)
            .setTransferStatus(TransferStatus.PENDING)
            .setGainingClientId("TheRegistrar")
            .build();
    domain =
        persistResource(
            domain
                .asBuilder()
                .setRegistrationExpirationTime(previousExpiration)
                .setTransferData(transferData)
                .build());

    assertThat(domain.cloneProjectedAtTime(now).getRegistrationExpirationTime())
        .isEqualTo(previousExpiration);
  }

  @Test
  void testClone_transferDuringAutorenew() {
    // When the domain is an an autorenew grace period, we should not extend the registration
    // expiration by a further year--it should just be whatever the autorenew was
    DateTime now = DateTime.now(UTC);
    DateTime transferExpirationTime = now.minusDays(1);
    DateTime previousExpiration = now.minusDays(2);

    DomainTransferData transferData =
        new DomainTransferData.Builder()
            .setPendingTransferExpirationTime(transferExpirationTime)
            .setTransferStatus(TransferStatus.PENDING)
            .setGainingClientId("TheRegistrar")
            .setServerApproveAutorenewEvent(recurringBillKey)
            .setServerApproveBillingEvent(oneTimeBillKey)
            .build();
    domain =
        persistResource(
            domain
                .asBuilder()
                .setRegistrationExpirationTime(previousExpiration)
                .setGracePeriods(
                    ImmutableSet.of(
                        GracePeriod.createForRecurring(
                            GracePeriodStatus.AUTO_RENEW,
                            domain.getRepoId(),
                            now.plusDays(1),
                            "NewRegistrar",
                            recurringBillKey)))
                .setTransferData(transferData)
                .setAutorenewBillingEvent(recurringBillKey)
                .build());
    DomainBase clone = domain.cloneProjectedAtTime(now);
    assertThat(clone.getRegistrationExpirationTime())
        .isEqualTo(domain.getRegistrationExpirationTime().plusYears(1));
    // Transferring removes the AUTORENEW grace period and adds a TRANSFER grace period
    assertThat(getOnlyElement(clone.getGracePeriods()).getType())
        .isEqualTo(GracePeriodStatus.TRANSFER);
  }

  @Test
  void testHistoryIdRestoration() {
    // Verify that history ids for billing events are restored during load from datastore.  History
    // ids are not used by business code or persisted in datastore, but only to reconstruct
    // objectify keys when loading from SQL.
    DateTime now = fakeClock.nowUtc();
    domain =
        persistResource(
            domain
                .asBuilder()
                .setRegistrationExpirationTime(now.plusYears(1))
                .setGracePeriods(
                    ImmutableSet.of(
                        GracePeriod.createForRecurring(
                            GracePeriodStatus.AUTO_RENEW,
                            domain.getRepoId(),
                            now.plusDays(1),
                            "NewRegistrar",
                            recurringBillKey),
                        GracePeriod.create(
                            GracePeriodStatus.RENEW,
                            domain.getRepoId(),
                            now.plusDays(1),
                            "NewRegistrar",
                            oneTimeBillKey)))
                .build());
    ImmutableSet<BillEventInfo> historyIds =
        domain.getGracePeriods().stream()
            .map(
                gp -> new BillEventInfo(gp.getRecurringBillingEvent(), gp.getOneTimeBillingEvent()))
            .collect(toImmutableSet());
    assertThat(historyIds)
        .isEqualTo(
            ImmutableSet.of(
                new BillEventInfo(null, oneTimeBillKey),
                new BillEventInfo(recurringBillKey, null)));
  }

  static class BillEventInfo extends ImmutableObject {
    VKey<BillingEvent.Recurring> billingEventRecurring;
    Long billingEventRecurringHistoryId;
    VKey<BillingEvent.OneTime> billingEventOneTime;
    Long billingEventOneTimeHistoryId;

    BillEventInfo(
        VKey<BillingEvent.Recurring> billingEventRecurring,
        VKey<BillingEvent.OneTime> billingEventOneTime) {
      this.billingEventRecurring = billingEventRecurring;
      this.billingEventOneTime = billingEventOneTime;
    }
  }
}
