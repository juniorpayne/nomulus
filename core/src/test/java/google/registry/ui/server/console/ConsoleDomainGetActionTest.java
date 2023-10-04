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

package google.registry.ui.server.console;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static org.mockito.Mockito.mock;

import com.google.api.client.http.HttpStatusCodes;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.RequestModule;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthSettings.AuthLevel;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link google.registry.ui.server.console.ConsoleDomainGetAction}. */
public class ConsoleDomainGetActionTest {

  private static final Gson GSON = RequestModule.provideGson();
  private static final FakeResponse RESPONSE = new FakeResponse();

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    DatabaseHelper.persistActiveDomain("exists.tld");
  }

  @Test
  void testSuccess_fullJsonRepresentation() {
    ConsoleDomainGetAction action =
        createAction(
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(
                    createUser(
                        new UserRoles.Builder()
                            .setRegistrarRoles(
                                ImmutableMap.of("TheRegistrar", RegistrarRole.ACCOUNT_MANAGER))
                            .build()))),
            "exists.tld");
    action.run();
    assertThat(RESPONSE.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    assertThat(RESPONSE.getPayload())
        .isEqualTo(
            "{\"domainName\":\"exists.tld\",\"adminContact\":{\"key\":\"3-ROID\"},\"techContact\":"
                + "{\"key\":\"3-ROID\"},\"registrantContact\":{\"key\":\"3-ROID\"},\"registrationExpirationTime\":"
                + "\"294247-01-10T04:00:54.775Z\",\"lastTransferTime\":\"null\",\"repoId\":\"2-TLD\","
                + "\"currentSponsorRegistrarId\":\"TheRegistrar\",\"creationRegistrarId\":\"TheRegistrar\","
                + "\"creationTime\":{\"creationTime\":\"1970-01-01T00:00:00.000Z\"},\"lastEppUpdateTime\":\"null\","
                + "\"statuses\":[\"INACTIVE\"]}");
  }

  @Test
  void testFailure_emptyAuth() {
    ConsoleDomainGetAction action = createAction(AuthResult.NOT_AUTHENTICATED, "exists.tld");
    action.run();
    assertThat(RESPONSE.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_UNAUTHORIZED);
  }

  @Test
  void testFailure_appAuth() {
    ConsoleDomainGetAction action = createAction(AuthResult.create(AuthLevel.APP), "exists.tld");
    action.run();
    assertThat(RESPONSE.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_UNAUTHORIZED);
  }

  @Test
  void testFailure_wrongTypeOfUser() {
    ConsoleDomainGetAction action =
        createAction(
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(mock(com.google.appengine.api.users.User.class), false)),
            "exists.tld");
    action.run();
    assertThat(RESPONSE.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_UNAUTHORIZED);
  }

  @Test
  void testFailure_noAccessToRegistrar() {
    ConsoleDomainGetAction action =
        createAction(
            AuthResult.create(
                AuthLevel.USER, UserAuthInfo.create(createUser(new UserRoles.Builder().build()))),
            "exists.tld");
    action.run();
    assertThat(RESPONSE.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_NOT_FOUND);
  }

  @Test
  void testFailure_nonexistentDomain() {
    ConsoleDomainGetAction action =
        createAction(
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(createUser(new UserRoles.Builder().setIsAdmin(true).build()))),
            "nonexistent.tld");
    action.run();
    assertThat(RESPONSE.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_NOT_FOUND);
  }

  private User createUser(UserRoles userRoles) {
    return new User.Builder()
        .setEmailAddress("email@email.com")
        .setUserRoles(userRoles)
        .build();
  }

  private ConsoleDomainGetAction createAction(AuthResult authResult, String domain) {
    return new ConsoleDomainGetAction(authResult, RESPONSE, GSON, domain);
  }
}
