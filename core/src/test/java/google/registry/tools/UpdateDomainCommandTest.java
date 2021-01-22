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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.domain.rgp.GracePeriodStatus.AUTO_RENEW;
import static google.registry.model.eppcommon.StatusValue.PENDING_DELETE;
import static google.registry.model.eppcommon.StatusValue.SERVER_UPDATE_PROHIBITED;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_CREATE;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newContactResource;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.GracePeriod;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.ofy.Ofy;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.testing.InjectExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link UpdateDomainCommand}. */
class UpdateDomainCommandTest extends EppToolCommandTestCase<UpdateDomainCommand> {

  private DomainBase domain;

  @RegisterExtension public final InjectExtension inject = new InjectExtension();

  @BeforeEach
  void beforeEach() {
    inject.setStaticField(Ofy.class, "clock", fakeClock);
    command.clock = fakeClock;
    domain = persistActiveDomain("example.tld");
  }

  @Test
  void testSuccess_complete() throws Exception {
    runCommandForced(
        "--client=NewRegistrar",
        "--add_nameservers=ns1.zdns.google,ns2.zdns.google",
        "--add_admins=crr-admin2",
        "--add_techs=crr-tech2",
        "--add_statuses=serverDeleteProhibited",
        "--add_ds_records=1 2 3 abcd,4 5 6 EF01",
        "--remove_nameservers=ns3.zdns.google,ns4.zdns.google",
        "--remove_admins=crr-admin1",
        "--remove_techs=crr-tech1",
        "--remove_statuses=serverHold",
        "--remove_ds_records=7 8 9 12ab,6 5 4 34CD",
        "--registrant=crr-admin",
        "--password=2fooBAR",
        "example.tld");
    eppVerifier.verifySent("domain_update_complete.xml");
  }

  @Test
  void testSuccess_completeWithSquareBrackets() throws Exception {
    runCommandForced(
        "--client=NewRegistrar",
        "--add_nameservers=ns[1-2].zdns.google",
        "--add_admins=crr-admin2",
        "--add_techs=crr-tech2",
        "--add_statuses=serverDeleteProhibited",
        "--add_ds_records=1 2 3 abcd,4 5 6 EF01",
        "--remove_nameservers=ns[3-4].zdns.google",
        "--remove_admins=crr-admin1",
        "--remove_techs=crr-tech1",
        "--remove_statuses=serverHold",
        "--remove_ds_records=7 8 9 12ab,6 5 4 34CD",
        "--registrant=crr-admin",
        "--password=2fooBAR",
        "example.tld");
    eppVerifier.verifySent("domain_update_complete.xml");
  }

  @Test
  void testSuccess_multipleDomains() throws Exception {
    createTld("abc");
    persistActiveDomain("example.abc");
    runCommandForced(
        "--client=NewRegistrar",
        "--add_nameservers=ns1.zdns.google,ns2.zdns.google",
        "--add_admins=crr-admin2",
        "--add_techs=crr-tech2",
        "--add_statuses=serverDeleteProhibited",
        "--add_ds_records=1 2 3 abcd,4 5 6 EF01",
        "--remove_nameservers=ns[3-4].zdns.google",
        "--remove_admins=crr-admin1",
        "--remove_techs=crr-tech1",
        "--remove_statuses=serverHold",
        "--remove_ds_records=7 8 9 12ab,6 5 4 34CD",
        "--registrant=crr-admin",
        "--password=2fooBAR",
        "example.tld",
        "example.abc");
    eppVerifier
        .verifySent("domain_update_complete.xml")
        .verifySent("domain_update_complete_abc.xml");
  }

  @Test
  void testSuccess_multipleDomains_setNameservers() throws Exception {
    runTest_multipleDomains_setNameservers("-n ns1.foo.fake,ns2.foo.fake");
  }

  @Test
  void testSuccess_multipleDomains_setNameserversWithSquareBrackets() throws Exception {
    runTest_multipleDomains_setNameservers("-n ns[1-2].foo.fake");
  }

  private void runTest_multipleDomains_setNameservers(String nsParam) throws Exception {
    createTld("abc");
    HostResource host1 = persistActiveHost("foo.bar.tld");
    HostResource host2 = persistActiveHost("baz.bar.tld");
    persistResource(
        newDomainBase("example.abc")
            .asBuilder()
            .setNameservers(ImmutableSet.of(host1.createVKey()))
            .build());
    persistResource(
        newDomainBase("example.tld")
            .asBuilder()
            .setNameservers(ImmutableSet.of(host2.createVKey()))
            .build());
    runCommandForced(
        "--client=NewRegistrar", nsParam, "example.abc", "example.tld");
    eppVerifier
        .verifySent(
            "domain_update_add_two_hosts_remove_one.xml",
            ImmutableMap.of("DOMAIN", "example.abc", "REMOVEHOST", "foo.bar.tld"))
        .verifySent(
            "domain_update_add_two_hosts_remove_one.xml",
            ImmutableMap.of("DOMAIN", "example.tld", "REMOVEHOST", "baz.bar.tld"));
  }

  @Test
  void testSuccess_add() throws Exception {
    runCommandForced(
        "--client=NewRegistrar",
        "--add_nameservers=ns2.zdns.google,ns3.zdns.google",
        "--add_admins=crr-admin2",
        "--add_techs=crr-tech2",
        "--add_statuses=serverDeleteProhibited",
        "--add_ds_records=1 2 3 abcd,4 5 6 EF01",
        "example.tld");
    eppVerifier.verifySent("domain_update_add.xml");
  }

  @Test
  void testSuccess_remove() throws Exception {
    runCommandForced(
        "--client=NewRegistrar",
        "--remove_nameservers=ns4.zdns.google",
        "--remove_admins=crr-admin1",
        "--remove_techs=crr-tech1",
        "--remove_statuses=serverHold",
        "--remove_ds_records=7 8 9 12ab,6 5 4 34CD",
        "example.tld");
    eppVerifier.verifySent("domain_update_remove.xml");
  }

  @Test
  void testSuccess_change() throws Exception {
    runCommandForced(
        "--client=NewRegistrar", "--registrant=crr-admin", "--password=2fooBAR", "example.tld");
    eppVerifier.verifySent("domain_update_change.xml");
  }

  @Test
  void testSuccess_setNameservers() throws Exception {
    HostResource host1 = persistActiveHost("ns1.zdns.google");
    HostResource host2 = persistActiveHost("ns2.zdns.google");
    ImmutableSet<VKey<HostResource>> nameservers =
        ImmutableSet.of(host1.createVKey(), host2.createVKey());
    persistResource(
        newDomainBase("example.tld").asBuilder().setNameservers(nameservers).build());
    runCommandForced(
        "--client=NewRegistrar", "--nameservers=ns2.zdns.google,ns3.zdns.google", "example.tld");
    eppVerifier.verifySent("domain_update_set_nameservers.xml");
  }

  @Test
  void testSuccess_setContacts() throws Exception {
    ContactResource adminContact1 = persistResource(newContactResource("crr-admin1"));
    ContactResource adminContact2 = persistResource(newContactResource("crr-admin2"));
    ContactResource techContact1 = persistResource(newContactResource("crr-tech1"));
    ContactResource techContact2 = persistResource(newContactResource("crr-tech2"));
    VKey<ContactResource> adminResourceKey1 = adminContact1.createVKey();
    VKey<ContactResource> adminResourceKey2 = adminContact2.createVKey();
    VKey<ContactResource> techResourceKey1 = techContact1.createVKey();
    VKey<ContactResource> techResourceKey2 = techContact2.createVKey();

    persistResource(
        newDomainBase("example.tld")
            .asBuilder()
            .setContacts(
                ImmutableSet.of(
                    DesignatedContact.create(DesignatedContact.Type.ADMIN, adminResourceKey1),
                    DesignatedContact.create(DesignatedContact.Type.ADMIN, adminResourceKey2),
                    DesignatedContact.create(DesignatedContact.Type.TECH, techResourceKey1),
                    DesignatedContact.create(DesignatedContact.Type.TECH, techResourceKey2)))
            .build());

    runCommandForced(
        "--client=NewRegistrar",
        "--admins=crr-admin2,crr-admin3",
        "--techs=crr-tech2,crr-tech3",
        "example.tld");
    eppVerifier.verifySent("domain_update_set_contacts.xml");
  }

  @Test
  void testSuccess_setStatuses() throws Exception {
    HostResource host = persistActiveHost("ns1.zdns.google");
    ImmutableSet<VKey<HostResource>> nameservers = ImmutableSet.of(host.createVKey());
    persistResource(
        newDomainBase("example.tld")
            .asBuilder()
            .setStatusValues(
                ImmutableSet.of(
                    StatusValue.CLIENT_RENEW_PROHIBITED, StatusValue.SERVER_TRANSFER_PROHIBITED))
            .setNameservers(nameservers)
            .build());

    runCommandForced(
        "--client=NewRegistrar", "--statuses=clientRenewProhibited,serverHold", "example.tld");
    eppVerifier.verifySent("domain_update_set_statuses.xml");
  }

  @Test
  void testSuccess_setDsRecords() throws Exception {
    runCommandForced(
        "--client=NewRegistrar", "--ds_records=1 2 3 abcd,4 5 6 EF01", "example.tld");
    eppVerifier.verifySent("domain_update_set_ds_records.xml");
  }

  @Test
  void testSuccess_setDsRecords_withUnneededClear() throws Exception {
    runCommandForced(
        "--client=NewRegistrar",
        "--ds_records=1 2 3 abcd,4 5 6 EF01",
        "--clear_ds_records",
        "example.tld");
    eppVerifier.verifySent("domain_update_set_ds_records.xml");
  }

  @Test
  void testSuccess_clearDsRecords() throws Exception {
    runCommandForced(
        "--client=NewRegistrar",
        "--clear_ds_records",
        "example.tld");
    eppVerifier.verifySent("domain_update_clear_ds_records.xml");
  }

  @Test
  void testSuccess_enableAutorenew() throws Exception {
    runCommandForced("--client=NewRegistrar", "--autorenews=true", "example.tld");
    eppVerifier.verifySent(
        "domain_update_set_autorenew.xml", ImmutableMap.of("AUTORENEWS", "true"));
  }

  @Test
  void testSuccess_disableAutorenew() throws Exception {
    runCommandForced("--client=NewRegistrar", "--autorenews=false", "example.tld");
    eppVerifier.verifySent(
        "domain_update_set_autorenew.xml", ImmutableMap.of("AUTORENEWS", "false"));
    assertThat(getStderrAsString()).doesNotContain("autorenew grace period");
  }

  @Test
  void testSuccess_disableAutorenew_inAutorenewGracePeriod() throws Exception {
    HistoryEntry createHistoryEntry =
        persistResource(
            new HistoryEntry.Builder().setType(DOMAIN_CREATE).setParent(domain).build());
    BillingEvent.Recurring autorenewBillingEvent =
        persistResource(
            new BillingEvent.Recurring.Builder()
                .setReason(Reason.RENEW)
                .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                .setTargetId("example.tld")
                .setClientId("NewRegistrar")
                .setEventTime(fakeClock.nowUtc().minusDays(5))
                .setRecurrenceEndTime(END_OF_TIME)
                .setParent(createHistoryEntry)
                .build());
    persistResource(
        domain
            .asBuilder()
            .setRegistrationExpirationTime(fakeClock.nowUtc().plusDays(360))
            .setAutorenewBillingEvent(autorenewBillingEvent.createVKey())
            .setGracePeriods(
                ImmutableSet.of(
                    GracePeriod.createForRecurring(
                        AUTO_RENEW,
                        domain.getRepoId(),
                        fakeClock.nowUtc().plusDays(40),
                        "NewRegistrar",
                        autorenewBillingEvent.createVKey())))
            .build());
    runCommandForced("--client=NewRegistrar", "--autorenews=false", "example.tld");
    eppVerifier.verifySent(
        "domain_update_set_autorenew.xml", ImmutableMap.of("AUTORENEWS", "false"));
    String stdErr = getStderrAsString();
    assertThat(stdErr).contains("The following domains are in autorenew grace periods.");
    assertThat(stdErr).contains("example.tld");
  }

  @Test
  void testSuccess_canUpdatePendingDeleteDomain_whenSuperuserPassesOverrideFlag() throws Exception {
    ContactResource adminContact1 = persistResource(newContactResource("crr-admin1"));
    ContactResource adminContact2 = persistResource(newContactResource("crr-admin2"));
    ContactResource techContact1 = persistResource(newContactResource("crr-tech1"));
    ContactResource techContact2 = persistResource(newContactResource("crr-tech2"));
    VKey<ContactResource> adminResourceKey1 = adminContact1.createVKey();
    VKey<ContactResource> adminResourceKey2 = adminContact2.createVKey();
    VKey<ContactResource> techResourceKey1 = techContact1.createVKey();
    VKey<ContactResource> techResourceKey2 = techContact2.createVKey();

    persistResource(
        newDomainBase("example.tld")
            .asBuilder()
            .setContacts(
                ImmutableSet.of(
                    DesignatedContact.create(DesignatedContact.Type.ADMIN, adminResourceKey1),
                    DesignatedContact.create(DesignatedContact.Type.ADMIN, adminResourceKey2),
                    DesignatedContact.create(DesignatedContact.Type.TECH, techResourceKey1),
                    DesignatedContact.create(DesignatedContact.Type.TECH, techResourceKey2)))
            .setStatusValues(ImmutableSet.of(PENDING_DELETE))
            .build());

    runCommandForced(
        "--client=NewRegistrar",
        "--admins=crr-admin2,crr-admin3",
        "--techs=crr-tech2,crr-tech3",
        "--superuser",
        "--force_in_pending_delete",
        "example.tld");
    eppVerifier.expectSuperuser().verifySent("domain_update_set_contacts.xml");
  }

  @Test
  void testFailure_cantUpdateRegistryLockedDomainEvenAsSuperuser() {
    HostResource host = persistActiveHost("ns1.zdns.google");
    ImmutableSet<VKey<HostResource>> nameservers = ImmutableSet.of(host.createVKey());
    persistResource(
        newDomainBase("example.tld")
            .asBuilder()
            .setStatusValues(ImmutableSet.of(SERVER_UPDATE_PROHIBITED))
            .setNameservers(nameservers)
            .build());

    Exception e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--statuses=clientRenewProhibited,serverHold",
                    "--superuser",
                    "example.tld"));
    assertThat(e)
        .hasMessageThat()
        .contains("The domain 'example.tld' has status SERVER_UPDATE_PROHIBITED.");
  }

  @Test
  void testFailure_cantUpdatePendingDeleteDomainEvenAsSuperuser_withoutPassingOverrideFlag() {
    HostResource host = persistActiveHost("ns1.zdns.google");
    ImmutableSet<VKey<HostResource>> nameservers = ImmutableSet.of(host.createVKey());
    persistResource(
        newDomainBase("example.tld")
            .asBuilder()
            .setStatusValues(ImmutableSet.of(PENDING_DELETE))
            .setNameservers(nameservers)
            .build());

    Exception e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--statuses=clientRenewProhibited,serverHold",
                    "--superuser",
                    "example.tld"));
    assertThat(e).hasMessageThat().contains("The domain 'example.tld' has status PENDING_DELETE.");
  }

  @Test
  void testFailure_duplicateDomains() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--registrant=crr-admin",
                    "--password=2fooBAR",
                    "example.tld",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("Duplicate arguments found");
  }

  @Test
  void testFailure_missingDomain() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar", "--registrant=crr-admin", "--password=2fooBAR"));
    assertThat(thrown).hasMessageThat().contains("Main parameters are required");
  }

  @Test
  void testFailure_missingClientId() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () -> runCommandForced("--registrant=crr-admin", "--password=2fooBAR", "example.tld"));
    assertThat(thrown).hasMessageThat().contains("--client");
  }

  @Test
  void testFailure_addTooManyNameServers() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--add_nameservers=ns1.zdns.google,ns2.zdns.google,ns3.zdns.google,ns4.zdns.google,"
                        + "ns5.zdns.google,ns6.zdns.google,ns7.zdns.google,ns8.zdns.google,"
                        + "ns9.zdns.google,ns10.zdns.google,ns11.zdns.google,ns12.zdns.google,"
                        + "ns13.zdns.google,ns14.zdns.google",
                    "--add_admins=crr-admin2",
                    "--add_techs=crr-tech2",
                    "--add_statuses=serverDeleteProhibited",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("You can add at most 13 nameservers");
  }

  @Test
  void testFailure_providedNameserversAndAddNameservers() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--add_nameservers=ns1.zdns.google",
                    "--nameservers=ns2.zdns.google,ns3.zdns.google",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "If you provide the nameservers flag, "
                + "you cannot use the add_nameservers and remove_nameservers flags.");
  }

  @Test
  void testFailure_providedNameserversAndRemoveNameservers() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--remove_nameservers=ns1.zdns.google",
                    "--nameservers=ns2.zdns.google,ns3.zdns.google",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "If you provide the nameservers flag, "
                + "you cannot use the add_nameservers and remove_nameservers flags.");
  }

  @Test
  void testFailure_providedAdminsAndAddAdmins() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--add_admins=crr-admin2",
                    "--admins=crr-admin2",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "If you provide the admins flag, "
                + "you cannot use the add_admins and remove_admins flags.");
  }

  @Test
  void testFailure_providedAdminsAndRemoveAdmins() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--remove_admins=crr-admin2",
                    "--admins=crr-admin2",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "If you provide the admins flag, "
                + "you cannot use the add_admins and remove_admins flags.");
  }

  @Test
  void testFailure_providedTechsAndAddTechs() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--add_techs=crr-tech2",
                    "--techs=crr-tech2",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "If you provide the techs flag, you cannot use the add_techs and remove_techs flags.");
  }

  @Test
  void testFailure_providedTechsAndRemoveTechs() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--remove_techs=crr-tech2",
                    "--techs=crr-tech2",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "If you provide the techs flag, you cannot use the add_techs and remove_techs flags.");
  }

  @Test
  void testFailure_providedStatusesAndAddStatuses() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--add_statuses=serverHold",
                    "--statuses=crr-serverHold",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "If you provide the statuses flag, "
                + "you cannot use the add_statuses and remove_statuses flags.");
  }

  @Test
  void testFailure_providedStatusesAndRemoveStatuses() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--remove_statuses=serverHold",
                    "--statuses=crr-serverHold",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "If you provide the statuses flag, "
                + "you cannot use the add_statuses and remove_statuses flags.");
  }

  @Test
  void testFailure_provideDsRecordsAndAddDsRecords() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--add_ds_records=1 2 3 abcd",
                    "--ds_records=4 5 6 EF01",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "If you provide the ds_records or clear_ds_records flags, "
                + "you cannot use the add_ds_records and remove_ds_records flags.");
  }

  @Test
  void testFailure_provideDsRecordsAndRemoveDsRecords() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--remove_ds_records=7 8 9 12ab",
                    "--ds_records=4 5 6 EF01",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "If you provide the ds_records or clear_ds_records flags, "
                + "you cannot use the add_ds_records and remove_ds_records flags.");
  }

  @Test
  void testFailure_clearDsRecordsAndAddDsRecords() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--add_ds_records=1 2 3 abcd",
                    "--clear_ds_records",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "If you provide the ds_records or clear_ds_records flags, "
                + "you cannot use the add_ds_records and remove_ds_records flags.");
  }

  @Test
  void testFailure_clearDsRecordsAndRemoveDsRecords() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--remove_ds_records=7 8 9 12ab",
                    "--clear_ds_records",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "If you provide the ds_records or clear_ds_records flags, "
                + "you cannot use the add_ds_records and remove_ds_records flags.");
  }
}
