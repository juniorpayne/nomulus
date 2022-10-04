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
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.model.domain.Domain;
import google.registry.model.domain.secdns.DomainDsData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.Host;
import google.registry.persistence.VKey;
import google.registry.testing.DatabaseHelper;
import javax.xml.bind.annotation.adapters.HexBinaryAdapter;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link UniformRapidSuspensionCommand}. */
class UniformRapidSuspensionCommandTest
    extends EppToolCommandTestCase<UniformRapidSuspensionCommand> {

  private Host ns1;
  private Host ns2;
  private Host urs1;
  private Host urs2;
  private Domain defaultDomain;
  private ImmutableSet<DomainDsData> defaultDsData;

  @BeforeEach
  void beforeEach() {
    command.clock = fakeClock;
    // Since the command's history client ID must be CharlestonRoad, resave TheRegistrar that way.
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setRegistrarId("CharlestonRoad").build());
    ns1 = persistActiveHost("ns1.example.com");
    ns2 = persistActiveHost("ns2.example.com");
    urs1 = persistActiveHost("urs1.example.com");
    urs2 = persistActiveHost("urs2.example.com");
    defaultDomain = DatabaseHelper.newDomain("evil.tld");
    defaultDsData =
        ImmutableSet.of(
            DomainDsData.create(1, 2, 3, new HexBinaryAdapter().unmarshal("dead")),
            DomainDsData.create(4, 5, 6, new HexBinaryAdapter().unmarshal("beef")));
  }

  private void persistDomainWithHosts(
      Domain domain, ImmutableSet<DomainDsData> dsData, Host... hosts) {
    ImmutableSet.Builder<VKey<Host>> hostRefs = new ImmutableSet.Builder<>();
    for (Host host : hosts) {
      hostRefs.add(host.createVKey());
    }
    persistResource(domain.asBuilder().setNameservers(hostRefs.build()).setDsData(dsData).build());
  }

  @Test
  void testCommand_addsLocksReplacesHostsAndDsDataPrintsUndo() throws Exception {
    persistDomainWithHosts(defaultDomain, defaultDsData, ns1, ns2);
    runCommandForced(
        "--domain_name=evil.tld",
        "--hosts=urs1.example.com,urs2.example.com",
        "--dsdata=1 1 1 A94A8FE5CCB19BA61C4C0873D391E987982FBBD3",
        "--renew_one_year=false");
    eppVerifier
        .expectRegistrarId("CharlestonRoad")
        .expectSuperuser()
        .verifySent("uniform_rapid_suspension.xml")
        .verifyNoMoreSent();
    assertInStdout("uniform_rapid_suspension --undo");
    assertInStdout("--domain_name evil.tld");
    assertInStdout("--hosts ns1.example.com,ns2.example.com");
    assertInStdout("--dsdata 1 2 3 DEAD,4 5 6 BEEF");
    assertNotInStdout("--locks_to_preserve");
    assertNotInStdout("--restore_client_hold");
  }

  @Test
  void testCommand_respectsExistingHost() throws Exception {
    persistDomainWithHosts(defaultDomain, defaultDsData, urs2, ns1);
    runCommandForced(
        "--domain_name=evil.tld",
        "--hosts=urs1.example.com,urs2.example.com",
        "--renew_one_year=false");
    eppVerifier
        .expectRegistrarId("CharlestonRoad")
        .expectSuperuser()
        .verifySent("uniform_rapid_suspension_existing_host.xml")
        .verifyNoMoreSent();
    assertInStdout("uniform_rapid_suspension --undo ");
    assertInStdout("--domain_name evil.tld");
    assertInStdout("--hosts ns1.example.com,urs2.example.com");
    assertNotInStdout("--locks_to_preserve");
  }

  @Test
  void testCommand_generatesUndoForUndelegatedDomain() throws Exception {
    persistActiveDomain("evil.tld");
    runCommandForced(
        "--domain_name=evil.tld",
        "--hosts=urs1.example.com,urs2.example.com",
        "--renew_one_year=false");
    eppVerifier.verifySentAny().verifyNoMoreSent();
    assertInStdout("uniform_rapid_suspension --undo");
    assertInStdout("--domain_name evil.tld");
    assertNotInStdout("--locks_to_preserve");
  }

  @Test
  void testCommand_generatesUndoWithLocksToPreserve() throws Exception {
    persistResource(
        DatabaseHelper.newDomain("evil.tld")
            .asBuilder()
            .addStatusValue(StatusValue.SERVER_DELETE_PROHIBITED)
            .build());
    runCommandForced("--domain_name=evil.tld", "--renew_one_year=false");
    eppVerifier.verifySentAny().verifyNoMoreSent();
    assertInStdout("uniform_rapid_suspension --undo");
    assertInStdout("--domain_name evil.tld");
    assertInStdout("--locks_to_preserve serverDeleteProhibited");
  }

  @Test
  void testCommand_removeClientHold() throws Exception {
    persistResource(
        DatabaseHelper.newDomain("evil.tld")
            .asBuilder()
            .addStatusValue(StatusValue.CLIENT_HOLD)
            .addNameserver(ns1.createVKey())
            .addNameserver(ns2.createVKey())
            .build());
    runCommandForced(
        "--domain_name=evil.tld",
        "--hosts=urs1.example.com,urs2.example.com",
        "--dsdata=1 1 1 A94A8FE5CCB19BA61C4C0873D391E987982FBBD3",
        "--renew_one_year=false");
    eppVerifier
        .expectRegistrarId("CharlestonRoad")
        .expectSuperuser()
        .verifySent("uniform_rapid_suspension_with_client_hold.xml")
        .verifyNoMoreSent();
    assertInStdout("uniform_rapid_suspension --undo");
    assertInStdout("--domain_name evil.tld");
    assertInStdout("--hosts ns1.example.com,ns2.example.com");
    assertInStdout("--restore_client_hold");
  }

  @Test
  void testUndo_removesLocksReplacesHostsAndDsData() throws Exception {
    persistDomainWithHosts(defaultDomain, defaultDsData, urs1, urs2);
    runCommandForced(
        "--domain_name=evil.tld",
        "--undo",
        "--hosts=ns1.example.com,ns2.example.com",
        "--renew_one_year=false");
    eppVerifier
        .expectRegistrarId("CharlestonRoad")
        .expectSuperuser()
        .verifySent("uniform_rapid_suspension_undo.xml")
        .verifyNoMoreSent();
    assertNotInStdout("--undo");  // Undo shouldn't print a new undo command.
  }

  @Test
  void testUndo_respectsLocksToPreserveFlag() throws Exception {
    persistDomainWithHosts(defaultDomain, defaultDsData, urs1, urs2);
    runCommandForced(
        "--domain_name=evil.tld",
        "--undo",
        "--locks_to_preserve=serverDeleteProhibited",
        "--hosts=ns1.example.com,ns2.example.com",
        "--renew_one_year=false");
    eppVerifier
        .expectRegistrarId("CharlestonRoad")
        .expectSuperuser()
        .verifySent("uniform_rapid_suspension_undo_preserve.xml")
        .verifyNoMoreSent();
    assertNotInStdout("--undo");  // Undo shouldn't print a new undo command.
  }

  @Test
  void testUndo_restoresClientHolds() throws Exception {
    persistDomainWithHosts(defaultDomain, defaultDsData, urs1, urs2);
    runCommandForced(
        "--domain_name=evil.tld",
        "--undo",
        "--hosts=ns1.example.com,ns2.example.com",
        "--restore_client_hold",
        "--renew_one_year=false");
    eppVerifier
        .expectRegistrarId("CharlestonRoad")
        .expectSuperuser()
        .verifySent("uniform_rapid_suspension_undo_client_hold.xml")
        .verifyNoMoreSent();
    assertNotInStdout("--undo"); // Undo shouldn't print a new undo command.
  }

  @Test
  void testAutorenews_setToFalseByDefault() throws Exception {
    persistResource(
        DatabaseHelper.newDomain("evil.tld")
            .asBuilder()
            .addStatusValue(StatusValue.SERVER_DELETE_PROHIBITED)
            .build());
    runCommandForced("--domain_name=evil.tld", "--renew_one_year=false");
    eppVerifier.verifySentAny();
    assertInStdout("<superuser:autorenews>false</superuser:autorenews>");
  }

  @Test
  void testAutorenews_setToTrueWhenUndo() throws Exception {
    persistResource(
        DatabaseHelper.newDomain("evil.tld")
            .asBuilder()
            .addStatusValue(StatusValue.SERVER_DELETE_PROHIBITED)
            .build());
    runCommandForced(
        "--domain_name=evil.tld",
        "--undo",
        "--hosts=ns1.example.com,ns2.example.com",
        "--restore_client_hold",
        "--renew_one_year=false");
    eppVerifier.verifySentAny();
    assertInStdout("<superuser:autorenews>true</superuser:autorenews>");
  }

  @Test
  void testRenewOneYearWithoutUndo_verifyReasonWithoutUndo() throws Exception {
    persistDomainWithHosts(
        DatabaseHelper.newDomain("evil.tld")
            .asBuilder()
            .setCreationTimeForTest(DateTime.parse("2021-10-01T05:01:11Z"))
            .setRegistrationExpirationTime(DateTime.parse("2022-10-01T05:01:11Z"))
            .setPersistedCurrentSponsorRegistrarId("CharlestonRoad")
            .build(),
        defaultDsData,
        urs1,
        urs2);

    runCommandForced(
        "--domain_name=evil.tld",
        "--hosts=ns1.example.com,ns2.example.com",
        "--renew_one_year=true");

    eppVerifier
        .expectRegistrarId("CharlestonRoad")
        .expectSuperuser()
        .verifySent(
            "domain_renew_via_urs.xml",
            ImmutableMap.of(
                "DOMAIN",
                "evil.tld",
                "EXPDATE",
                "2022-10-01",
                "YEARS",
                "1",
                "REASON",
                "Uniform Rapid Suspension",
                "REQUESTED",
                "false"))
        .verifySentAny();
  }

  @Test
  void testRenewOneYearWithUndo_verifyReasonWithUndo() throws Exception {
    persistDomainWithHosts(
        DatabaseHelper.newDomain("evil.tld")
            .asBuilder()
            .setCreationTimeForTest(DateTime.parse("2021-10-01T05:01:11Z"))
            .setRegistrationExpirationTime(DateTime.parse("2022-10-01T05:01:11Z"))
            .setPersistedCurrentSponsorRegistrarId("CharlestonRoad")
            .build(),
        defaultDsData,
        urs1,
        urs2);

    runCommandForced(
        "--domain_name=evil.tld",
        "--undo",
        "--hosts=ns1.example.com,ns2.example.com",
        "--renew_one_year=true");

    eppVerifier
        .expectRegistrarId("CharlestonRoad")
        .expectSuperuser()
        .verifySent(
            "domain_renew_via_urs.xml",
            ImmutableMap.of(
                "DOMAIN",
                "evil.tld",
                "EXPDATE",
                "2022-10-01",
                "YEARS",
                "1",
                "REASON",
                "Undo Uniform Rapid Suspension",
                "REQUESTED",
                "false"))
        .verifySentAny();
  }

  @Test
  void testRenewOneYear_verifyBothRenewAndUpdateFlowsAreTriggered() throws Exception {
    persistDomainWithHosts(
        DatabaseHelper.newDomain("evil.tld")
            .asBuilder()
            .setCreationTimeForTest(DateTime.parse("2021-10-01T05:01:11Z"))
            .setRegistrationExpirationTime(DateTime.parse("2022-10-01T05:01:11Z"))
            .setPersistedCurrentSponsorRegistrarId("CharlestonRoad")
            .build(),
        defaultDsData,
        urs1,
        urs2);

    runCommandForced(
        "--domain_name=evil.tld",
        "--undo",
        "--hosts=ns1.example.com,ns2.example.com",
        "--renew_one_year=true");

    eppVerifier
        .expectRegistrarId("CharlestonRoad")
        .expectSuperuser()
        .verifySent(
            "domain_renew_via_urs.xml",
            ImmutableMap.of(
                "DOMAIN",
                "evil.tld",
                "EXPDATE",
                "2022-10-01",
                "YEARS",
                "1",
                "REASON",
                "Undo Uniform Rapid Suspension",
                "REQUESTED",
                "false"));

    eppVerifier
        .expectRegistrarId("CharlestonRoad")
        .expectSuperuser()
        .verifySent("uniform_rapid_suspension_undo.xml");

    // verify that no other flows are triggered after the renew and update flows
    eppVerifier.verifyNoMoreSent();
  }

  @Test
  void testFailure_locksToPreserveWithoutUndo() {
    persistActiveDomain("evil.tld");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--domain_name=evil.tld",
                    "--locks_to_preserve=serverDeleteProhibited",
                    "--renew_one_year=false"));
    assertThat(thrown).hasMessageThat().contains("--undo");
  }

  @Test
  void testFailure_domainNameRequired() {
    persistActiveDomain("evil.tld");
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    "--hosts=urs1.example.com,urs2.example.com", "--renew_one_year=false"));
    assertThat(thrown).hasMessageThat().contains("--domain_name");
  }

  @Test
  void testFailure_renewOneYearRequired() {
    persistActiveDomain("evil.tld");
    ParameterException thrown =
        assertThrows(ParameterException.class, () -> runCommandForced("--domain_name=evil.tld"));
    assertThat(thrown).hasMessageThat().contains("--renew_one_year");
  }

  @Test
  void testFailure_extraFieldInDsData() {
    persistActiveDomain("evil.tld");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--domain_name=evil.tld", "--dsdata=1 1 1 abc 1", "--renew_one_year=false"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("dsRecord 1 1 1 abc 1 should have 4 parts, but has 5");
  }

  @Test
  void testFailure_missingFieldInDsData() {
    persistActiveDomain("evil.tld");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--domain_name=evil.tld", "--dsdata=1 1 1", "--renew_one_year=false"));
    assertThat(thrown).hasMessageThat().contains("dsRecord 1 1 1 should have 4 parts, but has 3");
  }

  @Test
  void testFailure_malformedDsData() {
    persistActiveDomain("evil.tld");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--domain_name=evil.tld", "--dsdata=1,2,3", "--renew_one_year=false"));
    assertThat(thrown).hasMessageThat().contains("dsRecord 1 should have 4 parts, but has 1");
  }
}
