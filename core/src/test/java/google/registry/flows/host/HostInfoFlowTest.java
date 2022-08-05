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

package google.registry.flows.host;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.assertNoBillingEvents;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.deleteResource;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import google.registry.flows.EppException;
import google.registry.flows.FlowUtils.NotLoggedInException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.host.HostFlowUtils.HostNameNotLowerCaseException;
import google.registry.flows.host.HostFlowUtils.HostNameNotNormalizedException;
import google.registry.flows.host.HostFlowUtils.HostNameNotPunyCodedException;
import google.registry.model.domain.Domain;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.Host;
import google.registry.testing.DatabaseHelper;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link HostInfoFlow}. */
class HostInfoFlowTest extends ResourceFlowTestCase<HostInfoFlow, Host> {

  @BeforeEach
  void initHostTest() {
    createTld("foobar");
    setEppInput("host_info.xml", ImmutableMap.of("HOSTNAME", "ns1.example.tld"));
  }

  private Host persistHost() throws Exception {
    return persistResource(
        new Host.Builder()
            .setHostName(getUniqueIdFromCommand())
            .setRepoId("1FF-FOOBAR")
            .setPersistedCurrentSponsorRegistrarId("my sponsor")
            .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_UPDATE_PROHIBITED))
            .setInetAddresses(
                ImmutableSet.of(
                    InetAddresses.forString("192.0.2.2"),
                    InetAddresses.forString("1080:0:0:0:8:800:200C:417A"),
                    InetAddresses.forString("192.0.2.29")))
            .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
            .setCreationRegistrarId("NewRegistrar")
            .setLastEppUpdateRegistrarId("NewRegistrar")
            .setCreationTimeForTest(DateTime.parse("1999-04-03T22:00:00.0Z"))
            .setLastEppUpdateTime(DateTime.parse("1999-12-03T09:00:00.0Z"))
            .setLastTransferTime(DateTime.parse("2000-04-08T09:00:00.0Z"))
            .build());
  }

  @Test
  void testNotLoggedIn() {
    sessionMetadata.setRegistrarId(null);
    EppException thrown = assertThrows(NotLoggedInException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess() throws Exception {
    persistHost();
    assertTransactionalFlow(false);
    // Check that the persisted host info was returned.
    runFlowAssertResponse(
        loadFile("host_info_response.xml"),
        // We use a different roid scheme than the samples so ignore it.
        "epp.response.resData.infData.roid");
    assertNoHistory();
    assertNoBillingEvents();
  }

  @Test
  void testSuccess_linked() throws Exception {
    persistHost();
    persistResource(
        DatabaseHelper.newDomain("example.foobar")
            .asBuilder()
            .addNameserver(persistHost().createVKey())
            .build());
    assertTransactionalFlow(false);
    // Check that the persisted host info was returned.
    runFlowAssertResponse(
        loadFile("host_info_response_linked.xml"),
        // We use a different roid scheme than the samples so ignore it.
        "epp.response.resData.infData.roid");
    assertNoHistory();
    assertNoBillingEvents();
  }

  private void runTest_superordinateDomain(
      DateTime domainTransferTime, @Nullable DateTime lastSuperordinateChange) throws Exception {
    persistNewRegistrar("superclientid");
    Domain domain =
        persistResource(
            DatabaseHelper.newDomain("parent.foobar")
                .asBuilder()
                .setRepoId("BEEF-FOOBAR")
                .setLastTransferTime(domainTransferTime)
                .setPersistedCurrentSponsorRegistrarId("superclientid")
                .build());
    Host firstHost = persistHost();
    persistResource(
        firstHost
            .asBuilder()
            .setRepoId("CEEF-FOOBAR")
            .setSuperordinateDomain(domain.createVKey())
            .setLastSuperordinateChange(lastSuperordinateChange)
            .build());
    // we shouldn't have two active hosts with the same hostname
    deleteResource(firstHost);
    assertTransactionalFlow(false);
    runFlowAssertResponse(
        loadFile("host_info_response_superordinate_clientid.xml"),
        // We use a different roid scheme than the samples so ignore it.
        "epp.response.resData.infData.roid");
    assertNoHistory();
    assertNoBillingEvents();
  }

  @Test
  void testSuccess_withSuperordinateDomain_hostMovedAfterDomainTransfer() throws Exception {
    runTest_superordinateDomain(
        DateTime.parse("2000-01-08T09:00:00.0Z"), DateTime.parse("2000-03-01T01:00:00.0Z"));
  }

  @Test
  void testSuccess_withSuperordinateDomain_hostMovedBeforeDomainTransfer() throws Exception {
    runTest_superordinateDomain(
        DateTime.parse("2000-04-08T09:00:00.0Z"), DateTime.parse("2000-02-08T09:00:00.0Z"));
  }

  @Test
  void testSuccess_withSuperordinateDomain() throws Exception {
    runTest_superordinateDomain(DateTime.parse("2000-04-08T09:00:00.0Z"), null);
  }

  @Test
  void testFailure_neverExisted() throws Exception {
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @Test
  void testFailure_existedButWasDeleted() throws Exception {
    persistResource(persistHost().asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @Test
  void testFailure_nonLowerCaseHostname() {
    setEppInput("host_info.xml", ImmutableMap.of("HOSTNAME", "NS1.EXAMPLE.NET"));
    EppException thrown = assertThrows(HostNameNotLowerCaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_nonPunyCodedHostname() {
    setEppInput("host_info.xml", ImmutableMap.of("HOSTNAME", "ns1.çauçalito.tld"));
    HostNameNotPunyCodedException thrown =
        assertThrows(HostNameNotPunyCodedException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("expected ns1.xn--aualito-txac.tld");
  }

  @Test
  void testFailure_nonCanonicalHostname() {
    setEppInput("host_info.xml", ImmutableMap.of("HOSTNAME", "ns1.example.tld."));
    EppException thrown = assertThrows(HostNameNotNormalizedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testIcannActivityReportField_getsLogged() throws Exception {
    persistHost();
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-host-info");
  }
}
