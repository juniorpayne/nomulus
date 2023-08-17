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
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.client.http.HttpStatusCodes;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.Action;
import google.registry.request.RequestModule;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthSettings.AuthLevel;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.FakeResponse;
import google.registry.ui.server.registrar.RegistrarConsoleModule;
import google.registry.util.StringGenerator;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link google.registry.ui.server.console.RegistrarsAction}. */
class RegistrarsActionTest {

  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private static final Gson GSON = RequestModule.provideGson();
  private FakeResponse response;

  private StringGenerator passwordGenerator =
      new DeterministicStringGenerator("abcdefghijklmnopqrstuvwxyz");
  private StringGenerator passcodeGenerator = new DeterministicStringGenerator("314159265");

  private ImmutableMap<String, String> userFriendlyKeysToRegistrarKeys =
      ImmutableMap.of(
          "registrarId", "registrarId",
          "registrarName", "name",
          "billingAccountMap", "billingAccount",
          "ianaIdentifier", "ianaId",
          "icannReferralEmail", "referralEmail",
          "driveFolderId", "driveId",
          "emailAddress", "consoleUserEmail",
          "localizedAddress", "address");

  private ImmutableMap<String, String> registrarParamMap =
      ImmutableMap.of(
          "registrarId",
          "regIdTest",
          "registrarName",
          "name",
          "billingAccountMap",
          "{\"USD\": \"789\"}",
          "ianaIdentifier",
          "123",
          "icannReferralEmail",
          "cannReferralEmail@gmail.com",
          "driveFolderId",
          "testDriveId",
          "emailAddress",
          "testEmailAddress@gmail.com",
          "localizedAddress",
          "{ \"street\": [\"test street\"], \"city\": \"test city\", \"state\": \"test state\","
              + " \"zip\": \"00700\", \"countryCode\": \"US\" }");

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @Test
  void testSuccess_onlyRealRegistrars() {
    Registrar registrar = persistNewRegistrar("registrarId");
    registrar = registrar.asBuilder().setType(Registrar.Type.TEST).setIanaIdentifier(null).build();
    persistResource(registrar);
    RegistrarsAction action =
        createAction(
            Action.Method.GET,
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(
                    createUser(
                        new UserRoles.Builder().setGlobalRole(GlobalRole.SUPPORT_LEAD).build()))));
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    String payload = response.getPayload();
    assertThat(
            ImmutableList.of("\"registrarId\":\"NewRegistrar\"", "\"registrarId\":\"TheRegistrar\"")
                .stream()
                .allMatch(s -> payload.contains(s)))
        .isTrue();
  }

  @Test
  void testSuccess_getRegistrars() {
    saveRegistrar("registrarId");
    RegistrarsAction action =
        createAction(
            Action.Method.GET,
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(
                    createUser(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build()))));
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    String payload = response.getPayload();
    assertThat(
            ImmutableList.of(
                    "\"registrarId\":\"NewRegistrar\"",
                    "\"registrarId\":\"TheRegistrar\"",
                    "\"registrarId\":\"registrarId\"")
                .stream()
                .allMatch(s -> payload.contains(s)))
        .isTrue();
  }

  @Test
  void testSuccess_createRegistrar() {
    RegistrarsAction action =
        createAction(
            Action.Method.POST,
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(createUser(new UserRoles.Builder().setIsAdmin(true).build()))));
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    Registrar r = loadRegistrar("regIdTest");
    assertThat(r).isNotNull();
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(rPOC -> rPOC.getEmailAddress().equals("testEmailAddress@gmail.com"))
                .findAny()
                .isPresent())
        .isTrue();
  }

  @Test
  void testFailure_createRegistrar_missingValue() {
    ImmutableMap<String, String> copy = ImmutableMap.copyOf(registrarParamMap);
    copy.keySet()
        .forEach(
            key -> {
              registrarParamMap =
                  ImmutableMap.copyOf(
                      copy.entrySet().stream()
                          .filter(entry -> !entry.getKey().equals(key))
                          .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
              RegistrarsAction action =
                  createAction(
                      Action.Method.POST,
                      AuthResult.create(
                          AuthLevel.USER,
                          UserAuthInfo.create(
                              createUser(new UserRoles.Builder().setIsAdmin(true).build()))));
              action.run();
              assertThat(response.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
              assertThat(response.getPayload())
                  .isEqualTo(
                      GSON.toJson(
                          String.format(
                              "Missing value for %s", userFriendlyKeysToRegistrarKeys.get(key))));
            });
  }

  @Test
  void testFailure_createRegistrar_existingRegistrar() {
    saveRegistrar("regIdTest");
    RegistrarsAction action =
        createAction(
            Action.Method.POST,
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(createUser(new UserRoles.Builder().setIsAdmin(true).build()))));
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
    assertThat(response.getPayload())
        .isEqualTo(GSON.toJson("Registrar with registrarId regIdTest already exists"));
  }

  @Test
  void testFailure_getRegistrarIds() {
    saveRegistrar("registrarId");
    RegistrarsAction action =
        createAction(
            Action.Method.GET,
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(
                    createUser(
                        new UserRoles.Builder()
                            .setRegistrarRoles(
                                ImmutableMap.of(
                                    "registrarId",
                                    RegistrarRole.ACCOUNT_MANAGER_WITH_REGISTRY_LOCK))
                            .build()))));
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_FORBIDDEN);
  }

  private User createUser(UserRoles userRoles) {
    return new User.Builder()
        .setEmailAddress("email@email.com")
        .setGaiaId("gaiaId")
        .setUserRoles(userRoles)
        .build();
  }

  private RegistrarsAction createAction(Action.Method method, AuthResult authResult) {
    response = new FakeResponse();
    when(request.getMethod()).thenReturn(method.toString());
    if (method.equals(Action.Method.GET)) {
      return new RegistrarsAction(
          request,
          authResult,
          response,
          GSON,
          Optional.ofNullable(null),
          passwordGenerator,
          passcodeGenerator);
    } else {
      try {
        doReturn(
                new BufferedReader(
                    new StringReader("{\"registrar\":" + registrarParamMap.toString() + "}")))
            .when(request)
            .getReader();
      } catch (IOException e) {
        return new RegistrarsAction(
            request,
            authResult,
            response,
            GSON,
            Optional.ofNullable(null),
            passwordGenerator,
            passcodeGenerator);
      }
      Optional<Registrar> maybeRegistrar =
          RegistrarConsoleModule.provideRegistrar(
              GSON, RequestModule.provideJsonBody(request, GSON));
      return new RegistrarsAction(
          request,
          authResult,
          response,
          GSON,
          maybeRegistrar,
          passwordGenerator,
          passcodeGenerator);
    }
  }
}
