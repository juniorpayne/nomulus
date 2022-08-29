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

package google.registry.flows.contact;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.EppResourceUtils.isDeleted;
import static google.registry.testing.DatabaseHelper.assertNoBillingEvents;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.flows.EppException;
import google.registry.flows.FlowUtils.NotLoggedInException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException;
import google.registry.model.contact.Contact;
import google.registry.model.contact.ContactAddress;
import google.registry.model.contact.ContactAuthInfo;
import google.registry.model.contact.ContactPhoneNumber;
import google.registry.model.contact.Disclose;
import google.registry.model.contact.PostalInfo;
import google.registry.model.contact.PostalInfo.Type;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.PresenceMarker;
import google.registry.model.eppcommon.StatusValue;
import google.registry.testing.DatabaseHelper;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ContactInfoFlow}. */
class ContactInfoFlowTest extends ResourceFlowTestCase<ContactInfoFlow, Contact> {

  ContactInfoFlowTest() {
    setEppInput("contact_info.xml");
  }

  private Contact persistContact(boolean active) {
    Contact contact =
        persistResource(
            new Contact.Builder()
                .setContactId("sh8013")
                .setRepoId("2FF-ROID")
                .setDeletionTime(active ? null : clock.nowUtc().minusDays(1))
                .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_DELETE_PROHIBITED))
                .setInternationalizedPostalInfo(
                    new PostalInfo.Builder()
                        .setType(Type.INTERNATIONALIZED)
                        .setName("John Doe")
                        .setOrg("Example Inc.")
                        .setAddress(
                            new ContactAddress.Builder()
                                .setStreet(ImmutableList.of("123 Example Dr.", "Suite 100"))
                                .setCity("Dulles")
                                .setState("VA")
                                .setZip("20166-6503")
                                .setCountryCode("US")
                                .build())
                        .build())
                .setVoiceNumber(
                    new ContactPhoneNumber.Builder()
                        .setPhoneNumber("+1.7035555555")
                        .setExtension("1234")
                        .build())
                .setFaxNumber(
                    new ContactPhoneNumber.Builder().setPhoneNumber("+1.7035555556").build())
                .setEmailAddress("jdoe@example.com")
                .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
                .setCreationRegistrarId("NewRegistrar")
                .setLastEppUpdateRegistrarId("NewRegistrar")
                .setCreationTimeForTest(DateTime.parse("1999-04-03T22:00:00.0Z"))
                .setLastEppUpdateTime(DateTime.parse("1999-12-03T09:00:00.0Z"))
                .setLastTransferTime(DateTime.parse("2000-04-08T09:00:00.0Z"))
                .setAuthInfo(ContactAuthInfo.create(PasswordAuth.create("2fooBAR")))
                .setDisclose(
                    new Disclose.Builder()
                        .setFlag(true)
                        .setVoice(new PresenceMarker())
                        .setEmail(new PresenceMarker())
                        .build())
                .build());
    assertThat(isDeleted(contact, clock.nowUtc())).isNotEqualTo(active);
    return contact;
  }

  @Test
  void testNotLoggedIn() {
    sessionMetadata.setRegistrarId(null);
    EppException thrown = assertThrows(NotLoggedInException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess() throws Exception {
    persistContact(true);
    // Check that the persisted contact info was returned.
    assertTransactionalFlow(false);
    runFlowAssertResponse(
        loadFile("contact_info_response.xml"),
        // We use a different roid scheme than the samples so ignore it.
        "epp.response.resData.infData.roid");
    assertNoHistory();
    assertNoBillingEvents();
  }

  @Test
  void testSuccess_linked() throws Exception {
    createTld("foobar");
    persistResource(DatabaseHelper.newDomain("example.foobar", persistContact(true)));
    // Check that the persisted contact info was returned.
    assertTransactionalFlow(false);
    runFlowAssertResponse(
        loadFile("contact_info_response_linked.xml"),
        // We use a different roid scheme than the samples so ignore it.
        "epp.response.resData.infData.roid");
    assertNoHistory();
    assertNoBillingEvents();
  }

  @Test
  void testSuccess_owningRegistrarWithoutAuthInfo_seesAuthInfo() throws Exception {
    setEppInput("contact_info_no_authinfo.xml");
    persistContact(true);
    // Check that the persisted contact info was returned.
    assertTransactionalFlow(false);
    runFlowAssertResponse(
        loadFile("contact_info_response.xml"),
        // We use a different roid scheme than the samples so ignore it.
        "epp.response.resData.infData.roid");
    assertNoHistory();
    assertNoBillingEvents();
  }

  @Test
  void testFailure_otherRegistrar_notAuthorized() throws Exception {
    setRegistrarIdForFlow("NewRegistrar");
    persistContact(true);
    // Check that the persisted contact info was returned.
    assertTransactionalFlow(false);
    ResourceNotOwnedException thrown = assertThrows(ResourceNotOwnedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_otherRegistrarWithoutAuthInfoAsSuperuser_doesNotSeeAuthInfo() throws Exception {
    setRegistrarIdForFlow("NewRegistrar");
    setEppInput("contact_info_no_authinfo.xml");
    persistContact(true);
    // Check that the persisted contact info was returned.
    assertTransactionalFlow(false);
    runFlowAssertResponse(
        CommitMode.LIVE,
        UserPrivileges.SUPERUSER,
        loadFile("contact_info_response_no_authinfo.xml"),
        // We use a different roid scheme than the samples so ignore it.
        "epp.response.resData.infData.roid");
    assertNoHistory();
    assertNoBillingEvents();
  }

  @Test
  void testSuccess_otherRegistrarWithAuthInfoAsSuperuser_seesAuthInfo() throws Exception {
    setRegistrarIdForFlow("NewRegistrar");
    persistContact(true);
    // Check that the persisted contact info was returned.
    assertTransactionalFlow(false);
    runFlowAssertResponse(
        CommitMode.LIVE,
        UserPrivileges.SUPERUSER,
        loadFile("contact_info_response.xml"),
        // We use a different roid scheme than the samples so ignore it.
        "epp.response.resData.infData.roid");
    assertNoHistory();
    assertNoBillingEvents();
  }

  @Test
  void testFailure_neverExisted() throws Exception {
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_existedButWasDeleted() throws Exception {
    persistContact(false);
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testIcannActivityReportField_getsLogged() throws Exception {
    persistContact(true);
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-cont-info");
  }
}
