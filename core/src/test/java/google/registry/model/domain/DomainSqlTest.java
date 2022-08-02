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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.flows.domain.DomainTransferUtils.createPendingTransferData;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.updateInDb;
import static google.registry.testing.SqlHelper.assertThrowForeignKeyViolation;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.googlecode.objectify.Key;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.billing.BillingEvent.Recurring;
import google.registry.model.common.EntityGroupRoot;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.Period.Unit;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.ContactTransferData;
import google.registry.model.transfer.DomainTransferData;
import google.registry.persistence.VKey;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.util.SerializeUtils;
import java.util.Arrays;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;

/** Verify that we can store/retrieve Domain objects from a SQL database. */
public class DomainSqlTest {

  protected FakeClock fakeClock = new FakeClock(DateTime.now(UTC));

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withCloudSql()
          .enableJpaEntityCoverageCheck(true)
          .withClock(fakeClock)
          .build();

  private Domain domain;
  private DomainHistory historyEntry;
  private VKey<ContactResource> contactKey;
  private VKey<ContactResource> contact2Key;
  private VKey<HostResource> host1VKey;
  private HostResource host;
  private ContactResource contact;
  private ContactResource contact2;
  private ImmutableSet<GracePeriod> gracePeriods;

  @BeforeEach
  void setUp() {
    saveRegistrar("registrar1");
    saveRegistrar("registrar2");
    saveRegistrar("registrar3");
    contactKey = createKey(ContactResource.class, "contact_id1");
    contact2Key = createKey(ContactResource.class, "contact_id2");

    host1VKey = createKey(HostResource.class, "host1");

    domain =
        new Domain.Builder()
            .setDomainName("example.com")
            .setRepoId("4-COM")
            .setCreationRegistrarId("registrar1")
            .setLastEppUpdateTime(fakeClock.nowUtc())
            .setLastEppUpdateRegistrarId("registrar2")
            .setLastTransferTime(fakeClock.nowUtc())
            .setNameservers(host1VKey)
            .setStatusValues(
                ImmutableSet.of(
                    StatusValue.CLIENT_DELETE_PROHIBITED,
                    StatusValue.SERVER_DELETE_PROHIBITED,
                    StatusValue.SERVER_TRANSFER_PROHIBITED,
                    StatusValue.SERVER_UPDATE_PROHIBITED,
                    StatusValue.SERVER_RENEW_PROHIBITED,
                    StatusValue.SERVER_HOLD))
            .setRegistrant(contactKey)
            .setContacts(ImmutableSet.of(DesignatedContact.create(Type.ADMIN, contact2Key)))
            .setSubordinateHosts(ImmutableSet.of("ns1.example.com"))
            .setPersistedCurrentSponsorRegistrarId("registrar3")
            .setRegistrationExpirationTime(fakeClock.nowUtc().plusYears(1))
            .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("password")))
            .setDsData(ImmutableSet.of(DelegationSignerData.create(1, 2, 3, new byte[] {0, 1, 2})))
            .setLaunchNotice(
                LaunchNotice.create("tcnid", "validatorId", START_OF_TIME, START_OF_TIME))
            .setSmdId("smdid")
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.ADD, "4-COM", END_OF_TIME, "registrar1", null, 100L))
            .build();

    host =
        new HostResource.Builder()
            .setRepoId("host1")
            .setHostName("ns1.example.com")
            .setCreationRegistrarId("registrar1")
            .setPersistedCurrentSponsorRegistrarId("registrar2")
            .build();
    contact = makeContact("contact_id1");
    contact2 = makeContact("contact_id2");
  }

  @Test
  void testDomainPersistence() {
    persistDomain();
    assertEqualDomainExcept(loadByKey(domain.createVKey()));
  }

  @Test
  void testHostForeignKeyConstraints() {
    // Persist the domain without the associated host object.
    assertThrowForeignKeyViolation(() -> insertInDb(contact, contact2, domain));
  }

  @Test
  void testContactForeignKeyConstraints() {
    // Persist the domain without the associated contact objects.
    assertThrowForeignKeyViolation(() -> insertInDb(domain, host));
  }

  @Test
  void testResaveDomain_succeeds() {
    persistDomain();
    jpaTm()
        .transact(
            () -> {
              Domain persisted = jpaTm().loadByKey(domain.createVKey());
              jpaTm().put(persisted.asBuilder().build());
            });
    // Load the domain in its entirety.
    assertEqualDomainExcept(loadByKey(domain.createVKey()));
  }

  @Test
  void testModifyGracePeriod_setEmptyCollectionSuccessfully() {
    persistDomain();
    jpaTm()
        .transact(
            () -> {
              Domain persisted = jpaTm().loadByKey(domain.createVKey());
              Domain modified = persisted.asBuilder().setGracePeriods(ImmutableSet.of()).build();
              jpaTm().put(modified);
            });

    jpaTm()
        .transact(
            () -> {
              Domain persisted = jpaTm().loadByKey(domain.createVKey());
              assertThat(persisted.getGracePeriods()).isEmpty();
            });
  }

  @Test
  void testModifyGracePeriod_setNullCollectionSuccessfully() {
    persistDomain();
    jpaTm()
        .transact(
            () -> {
              Domain persisted = jpaTm().loadByKey(domain.createVKey());
              Domain modified = persisted.asBuilder().setGracePeriods(null).build();
              jpaTm().put(modified);
            });

    jpaTm()
        .transact(
            () -> {
              Domain persisted = jpaTm().loadByKey(domain.createVKey());
              assertThat(persisted.getGracePeriods()).isEmpty();
            });
  }

  @Test
  void testModifyGracePeriod_addThenRemoveSuccessfully() {
    persistDomain();
    jpaTm()
        .transact(
            () -> {
              Domain persisted = jpaTm().loadByKey(domain.createVKey());
              Domain modified =
                  persisted
                      .asBuilder()
                      .addGracePeriod(
                          GracePeriod.create(
                              GracePeriodStatus.RENEW,
                              "4-COM",
                              END_OF_TIME,
                              "registrar1",
                              null,
                              200L))
                      .build();
              jpaTm().put(modified);
            });

    jpaTm()
        .transact(
            () -> {
              Domain persisted = jpaTm().loadByKey(domain.createVKey());
              assertThat(persisted.getGracePeriods())
                  .containsExactly(
                      GracePeriod.create(
                          GracePeriodStatus.ADD, "4-COM", END_OF_TIME, "registrar1", null, 100L),
                      GracePeriod.create(
                          GracePeriodStatus.RENEW, "4-COM", END_OF_TIME, "registrar1", null, 200L));
              assertEqualDomainExcept(persisted, "gracePeriods");
            });

    jpaTm()
        .transact(
            () -> {
              Domain persisted = jpaTm().loadByKey(domain.createVKey());
              Domain.Builder builder = persisted.asBuilder();
              for (GracePeriod gracePeriod : persisted.getGracePeriods()) {
                if (gracePeriod.getType() == GracePeriodStatus.RENEW) {
                  builder.removeGracePeriod(gracePeriod);
                }
              }
              jpaTm().put(builder.build());
            });

    jpaTm()
        .transact(
            () -> {
              Domain persisted = jpaTm().loadByKey(domain.createVKey());
              assertEqualDomainExcept(persisted);
            });
  }

  @Test
  void testModifyGracePeriod_removeThenAddSuccessfully() {
    persistDomain();
    jpaTm()
        .transact(
            () -> {
              Domain persisted = jpaTm().loadByKey(domain.createVKey());
              Domain modified = persisted.asBuilder().setGracePeriods(ImmutableSet.of()).build();
              jpaTm().put(modified);
            });

    jpaTm()
        .transact(
            () -> {
              Domain persisted = jpaTm().loadByKey(domain.createVKey());
              assertThat(persisted.getGracePeriods()).isEmpty();
              Domain modified =
                  persisted
                      .asBuilder()
                      .addGracePeriod(
                          GracePeriod.create(
                              GracePeriodStatus.ADD,
                              "4-COM",
                              END_OF_TIME,
                              "registrar1",
                              null,
                              100L))
                      .build();
              jpaTm().put(modified);
            });

    jpaTm()
        .transact(
            () -> {
              Domain persisted = jpaTm().loadByKey(domain.createVKey());
              assertThat(persisted.getGracePeriods())
                  .containsExactly(
                      GracePeriod.create(
                          GracePeriodStatus.ADD, "4-COM", END_OF_TIME, "registrar1", null, 100L));
              assertEqualDomainExcept(persisted, "gracePeriods");
            });
  }

  @Test
  void testModifyDsData_addThenRemoveSuccessfully() {
    persistDomain();
    DelegationSignerData extraDsData =
        DelegationSignerData.create(2, 2, 3, new byte[] {0, 1, 2}, "4-COM");
    ImmutableSet<DelegationSignerData> unionDsData =
        Sets.union(domain.getDsData(), ImmutableSet.of(extraDsData)).immutableCopy();

    // Add an extra DelegationSignerData to dsData set.
    jpaTm()
        .transact(
            () -> {
              Domain persisted = jpaTm().loadByKey(domain.createVKey());
              assertThat(persisted.getDsData()).containsExactlyElementsIn(domain.getDsData());
              Domain modified = persisted.asBuilder().setDsData(unionDsData).build();
              jpaTm().put(modified);
            });

    // Verify that the persisted domain entity contains both DelegationSignerData records.
    jpaTm()
        .transact(
            () -> {
              Domain persisted = jpaTm().loadByKey(domain.createVKey());
              assertThat(persisted.getDsData()).containsExactlyElementsIn(unionDsData);
              assertEqualDomainExcept(persisted, "dsData");
            });

    // Remove the extra DelegationSignerData record from dsData set.
    jpaTm()
        .transact(
            () -> {
              Domain persisted = jpaTm().loadByKey(domain.createVKey());
              jpaTm().put(persisted.asBuilder().setDsData(domain.getDsData()).build());
            });

    // Verify that the persisted domain is equal to the original domain.
    jpaTm()
        .transact(
            () -> {
              Domain persisted = jpaTm().loadByKey(domain.createVKey());
              assertEqualDomainExcept(persisted);
            });
  }

  @Test
  void testSerializable() {
    createTld("com");
    insertInDb(contact, contact2, domain, host);
    Domain persisted = jpaTm().transact(() -> jpaTm().loadByEntity(domain));
    assertThat(SerializeUtils.serializeDeserialize(persisted)).isEqualTo(persisted);
  }

  @Test
  void testUpdates() {
    createTld("com");
    insertInDb(contact, contact2, domain, host);
    domain = domain.asBuilder().setNameservers(ImmutableSet.of()).build();
    updateInDb(domain);
    assertAboutImmutableObjects()
        .that(loadByEntity(domain))
        .isEqualExceptFields(domain, "updateTimestamp", "creationTime");
  }

  static ContactResource makeContact(String repoId) {
    return new ContactResource.Builder()
        .setRepoId(repoId)
        .setCreationRegistrarId("registrar1")
        .setTransferData(new ContactTransferData.Builder().build())
        .setPersistedCurrentSponsorRegistrarId("registrar1")
        .build();
  }

  private void persistDomain() {
    createTld("com");
    insertInDb(contact, contact2, domain, host);
  }

  @Test
  void persistDomainWithCompositeVKeys() {
    createTld("com");
    historyEntry =
        new DomainHistory.Builder()
            .setId(100L)
            .setType(HistoryEntry.Type.DOMAIN_CREATE)
            .setPeriod(Period.create(1, Period.Unit.YEARS))
            .setModificationTime(DateTime.now(UTC))
            .setDomainRepoId("4-COM")
            .setRegistrarId("registrar1")
            // These are non-null, but I don't think some tests set them.
            .setReason("felt like it")
            .setRequestedByRegistrar(false)
            .setXmlBytes(new byte[0])
            .build();
    BillingEvent.Recurring billEvent =
        new BillingEvent.Recurring.Builder()
            .setId(200L)
            .setReason(Reason.RENEW)
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setTargetId("example.com")
            .setRegistrarId("registrar1")
            .setEventTime(DateTime.now(UTC).plusYears(1))
            .setRecurrenceEndTime(END_OF_TIME)
            .setDomainHistory(historyEntry)
            .build();
    PollMessage.Autorenew autorenewPollMessage =
        new PollMessage.Autorenew.Builder()
            .setId(300L)
            .setRegistrarId("registrar1")
            .setEventTime(DateTime.now(UTC).plusYears(1))
            .setHistoryEntry(historyEntry)
            .build();
    PollMessage.OneTime deletePollMessage =
        new PollMessage.OneTime.Builder()
            .setId(400L)
            .setRegistrarId("registrar1")
            .setEventTime(DateTime.now(UTC).plusYears(1))
            .setHistoryEntry(historyEntry)
            .build();
    BillingEvent.OneTime oneTimeBillingEvent =
        new BillingEvent.OneTime.Builder()
            .setId(500L)
            // Use SERVER_STATUS so we don't have to add a period.
            .setReason(Reason.SERVER_STATUS)
            .setTargetId("example.com")
            .setRegistrarId("registrar1")
            .setBillingTime(DateTime.now(UTC))
            .setCost(Money.of(USD, 100))
            .setEventTime(DateTime.now(UTC).plusYears(1))
            .setDomainHistory(historyEntry)
            .build();
    DomainTransferData transferData =
        new DomainTransferData.Builder()
            .setServerApproveBillingEvent(oneTimeBillingEvent.createVKey())
            .setServerApproveAutorenewEvent(billEvent.createVKey())
            .setServerApproveAutorenewPollMessage(autorenewPollMessage.createVKey())
            .build();
    gracePeriods =
        ImmutableSet.of(
            GracePeriod.create(
                GracePeriodStatus.ADD,
                "4-COM",
                END_OF_TIME,
                "registrar1",
                oneTimeBillingEvent.createVKey()),
            GracePeriod.createForRecurring(
                GracePeriodStatus.AUTO_RENEW,
                "4-COM",
                END_OF_TIME,
                "registrar1",
                billEvent.createVKey()));

    domain =
        domain
            .asBuilder()
            .setAutorenewBillingEvent(billEvent.createVKey())
            .setAutorenewPollMessage(
                autorenewPollMessage.createVKey(), autorenewPollMessage.getHistoryRevisionId())
            .setDeletePollMessage(deletePollMessage.createVKey())
            .setTransferData(transferData)
            .setGracePeriods(gracePeriods)
            .build();
    historyEntry = historyEntry.asBuilder().setDomain(domain).build();
    insertInDb(
        contact,
        contact2,
        host,
        historyEntry,
        autorenewPollMessage,
        billEvent,
        deletePollMessage,
        oneTimeBillingEvent,
        domain);

    // Store the existing BillingRecurrence VKey.  This happens after the event has been persisted.
    Domain persisted = loadByKey(domain.createVKey());

    // Verify that the domain data has been persisted.
    // dsData still isn't persisted.  gracePeriods appears to have the same values but for some
    // reason is showing up as different.
    assertEqualDomainExcept(persisted, "creationTime", "dsData", "gracePeriods");

    // Verify that the DomainContent object from the history record sets the fields correctly.
    DomainHistory persistedHistoryEntry = loadByKey(historyEntry.createVKey());
    assertThat(persistedHistoryEntry.getDomainContent().get().getAutorenewPollMessage())
        .isEqualTo(domain.getAutorenewPollMessage());
    assertThat(persistedHistoryEntry.getDomainContent().get().getAutorenewBillingEvent())
        .isEqualTo(domain.getAutorenewBillingEvent());
    assertThat(persistedHistoryEntry.getDomainContent().get().getDeletePollMessage())
        .isEqualTo(domain.getDeletePollMessage());
    DomainTransferData persistedTransferData =
        persistedHistoryEntry.getDomainContent().get().getTransferData();
    DomainTransferData originalTransferData = domain.getTransferData();
    assertThat(persistedTransferData.getServerApproveBillingEvent())
        .isEqualTo(originalTransferData.getServerApproveBillingEvent());
    assertThat(persistedTransferData.getServerApproveAutorenewEvent())
        .isEqualTo(originalTransferData.getServerApproveAutorenewEvent());
    assertThat(persistedTransferData.getServerApproveAutorenewPollMessage())
        .isEqualTo(originalTransferData.getServerApproveAutorenewPollMessage());
    assertThat(persisted.getGracePeriods()).isEqualTo(gracePeriods);
  }

  @Test
  void persistDomainWithLegacyVKeys() {
    createTld("com");
    historyEntry =
        new DomainHistory.Builder()
            .setId(100L)
            .setType(HistoryEntry.Type.DOMAIN_CREATE)
            .setPeriod(Period.create(1, Period.Unit.YEARS))
            .setModificationTime(DateTime.now(UTC))
            .setDomainRepoId("4-COM")
            .setRegistrarId("registrar1")
            // These are non-null, but I don't think some tests set them.
            .setReason("felt like it")
            .setRequestedByRegistrar(false)
            .setXmlBytes(new byte[0])
            .build();
    BillingEvent.Recurring billEvent =
        new BillingEvent.Recurring.Builder()
            .setId(200L)
            .setReason(Reason.RENEW)
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setTargetId("example.com")
            .setRegistrarId("registrar1")
            .setEventTime(DateTime.now(UTC).plusYears(1))
            .setRecurrenceEndTime(END_OF_TIME)
            .setDomainHistory(historyEntry)
            .build();
    PollMessage.Autorenew autorenewPollMessage =
        new PollMessage.Autorenew.Builder()
            .setId(300L)
            .setRegistrarId("registrar1")
            .setEventTime(DateTime.now(UTC).plusYears(1))
            .setHistoryEntry(historyEntry)
            .build();
    PollMessage.OneTime deletePollMessage =
        new PollMessage.OneTime.Builder()
            .setId(400L)
            .setRegistrarId("registrar1")
            .setEventTime(DateTime.now(UTC).plusYears(1))
            .setHistoryEntry(historyEntry)
            .build();
    BillingEvent.OneTime oneTimeBillingEvent =
        new BillingEvent.OneTime.Builder()
            .setId(500L)
            // Use SERVER_STATUS so we don't have to add a period.
            .setReason(Reason.SERVER_STATUS)
            .setTargetId("example.com")
            .setRegistrarId("registrar1")
            .setBillingTime(DateTime.now(UTC))
            .setCost(Money.of(USD, 100))
            .setEventTime(DateTime.now(UTC).plusYears(1))
            .setDomainHistory(historyEntry)
            .build();
    DomainTransferData transferData =
        createPendingTransferData(
            domain.getRepoId(),
            historyEntry.getId(),
            new DomainTransferData.Builder()
                .setTransferRequestTrid(Trid.create("foo", "bar"))
                .setTransferRequestTime(fakeClock.nowUtc())
                .setGainingRegistrarId("registrar2")
                .setLosingRegistrarId("registrar1")
                .setPendingTransferExpirationTime(fakeClock.nowUtc().plusDays(1)),
            ImmutableSet.of(oneTimeBillingEvent, billEvent, autorenewPollMessage),
            Period.create(0, Unit.YEARS));
    gracePeriods =
        ImmutableSet.of(
            GracePeriod.create(
                GracePeriodStatus.ADD,
                "4-COM",
                END_OF_TIME,
                "registrar1",
                oneTimeBillingEvent.createVKey()),
            GracePeriod.createForRecurring(
                GracePeriodStatus.AUTO_RENEW,
                "4-COM",
                END_OF_TIME,
                "registrar1",
                billEvent.createVKey()));

    domain =
        domain
            .asBuilder()
            .setAutorenewBillingEvent(Recurring.createVKey(billEvent.getId()))
            .setAutorenewPollMessage(
                createLegacyVKey(PollMessage.Autorenew.class, autorenewPollMessage.getId()),
                autorenewPollMessage.getHistoryRevisionId())
            .setDeletePollMessage(
                createLegacyVKey(PollMessage.OneTime.class, deletePollMessage.getId()))
            .setTransferData(transferData)
            .setGracePeriods(gracePeriods)
            .build();
    historyEntry = historyEntry.asBuilder().setDomain(domain).build();
    insertInDb(
        contact,
        contact2,
        host,
        historyEntry,
        autorenewPollMessage,
        billEvent,
        deletePollMessage,
        oneTimeBillingEvent,
        domain);

    // Store the existing BillingRecurrence VKey.  This happens after the event has been persisted.
    Domain persisted = loadByKey(domain.createVKey());

    // Verify that the domain data has been persisted.
    // dsData still isn't persisted.  gracePeriods appears to have the same values but for some
    // reason is showing up as different.
    assertEqualDomainExcept(persisted, "creationTime", "dsData", "gracePeriods");

    // Verify that the DomainContent object from the history record sets the fields correctly.
    DomainHistory persistedHistoryEntry = loadByKey(historyEntry.createVKey());
    assertThat(persistedHistoryEntry.getDomainContent().get().getAutorenewPollMessage())
        .isEqualTo(domain.getAutorenewPollMessage());
    assertThat(persistedHistoryEntry.getDomainContent().get().getAutorenewBillingEvent())
        .isEqualTo(domain.getAutorenewBillingEvent());
    assertThat(persistedHistoryEntry.getDomainContent().get().getDeletePollMessage())
        .isEqualTo(domain.getDeletePollMessage());
    DomainTransferData persistedTransferData =
        persistedHistoryEntry.getDomainContent().get().getTransferData();
    DomainTransferData originalTransferData = domain.getTransferData();
    assertThat(persistedTransferData.getServerApproveBillingEvent())
        .isEqualTo(originalTransferData.getServerApproveBillingEvent());
    assertThat(persistedTransferData.getServerApproveAutorenewEvent())
        .isEqualTo(originalTransferData.getServerApproveAutorenewEvent());
    assertThat(persistedTransferData.getServerApproveAutorenewPollMessage())
        .isEqualTo(originalTransferData.getServerApproveAutorenewPollMessage());
    assertThat(domain.getGracePeriods()).isEqualTo(gracePeriods);
  }

  private <T> VKey<T> createKey(Class<T> clazz, String name) {
    return VKey.create(clazz, name, Key.create(clazz, name));
  }

  private <T> VKey<T> createLegacyVKey(Class<T> clazz, long id) {
    return VKey.create(
        clazz, id, Key.create(Key.create(EntityGroupRoot.class, "per-tld"), clazz, id));
  }

  private void assertEqualDomainExcept(Domain thatDomain, String... excepts) {
    ImmutableList<String> moreExcepts =
        new ImmutableList.Builder<String>()
            .addAll(Arrays.asList(excepts))
            .add("creationTime")
            .add("updateTimestamp")
            .add("transferData")
            .build();
    // Note that the equality comparison forces a lazy load of all fields.
    assertAboutImmutableObjects().that(thatDomain).isEqualExceptFields(domain, moreExcepts);
    // Transfer data cannot be directly compared due to serverApproveEtities inequalities
    assertAboutImmutableObjects()
        .that(domain.getTransferData())
        .isEqualExceptFields(thatDomain.getTransferData(), "serverApproveEntities");
  }

  @Test
  void testUpdateTimeAfterNameserverUpdate() {
    persistDomain();
    Domain persisted = loadByKey(domain.createVKey());
    DateTime originalUpdateTime = persisted.getUpdateTimestamp().getTimestamp();
    fakeClock.advanceOneMilli();
    DateTime transactionTime =
        jpaTm()
            .transact(
                () -> {
                  HostResource host2 =
                      new HostResource.Builder()
                          .setRepoId("host2")
                          .setHostName("ns2.example.com")
                          .setCreationRegistrarId("registrar1")
                          .setPersistedCurrentSponsorRegistrarId("registrar2")
                          .build();
                  insertInDb(host2);
                  domain = persisted.asBuilder().addNameserver(host2.createVKey()).build();
                  updateInDb(domain);
                  return jpaTm().getTransactionTime();
                });
    domain = loadByKey(domain.createVKey());
    assertThat(domain.getUpdateTimestamp().getTimestamp()).isEqualTo(transactionTime);
    assertThat(domain.getUpdateTimestamp().getTimestamp()).isNotEqualTo(originalUpdateTime);
  }

  @Test
  void testUpdateTimeAfterDsDataUpdate() {
    persistDomain();
    Domain persisted = loadByKey(domain.createVKey());
    DateTime originalUpdateTime = persisted.getUpdateTimestamp().getTimestamp();
    fakeClock.advanceOneMilli();
    DateTime transactionTime =
        jpaTm()
            .transact(
                () -> {
                  domain =
                      persisted
                          .asBuilder()
                          .setDsData(
                              ImmutableSet.of(
                                  DelegationSignerData.create(1, 2, 3, new byte[] {0, 1, 2})))
                          .build();
                  updateInDb(domain);
                  return jpaTm().getTransactionTime();
                });
    domain = loadByKey(domain.createVKey());
    assertThat(domain.getUpdateTimestamp().getTimestamp()).isEqualTo(transactionTime);
    assertThat(domain.getUpdateTimestamp().getTimestamp()).isNotEqualTo(originalUpdateTime);
  }
}
