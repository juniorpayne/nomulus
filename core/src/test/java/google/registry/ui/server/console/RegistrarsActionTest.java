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
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.SqlHelper.saveRegistrar;

import com.google.api.client.http.HttpStatusCodes;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrar.Registrar;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthSettings.AuthLevel;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.FakeResponse;
import google.registry.util.UtilsModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link google.registry.ui.server.console.RegistrarsAction}. */
class RegistrarsActionTest {

  private static final Gson GSON = UtilsModule.provideGson();
  private FakeResponse response;

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
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(
                    createUser(
                        new UserRoles.Builder().setGlobalRole(GlobalRole.SUPPORT_LEAD).build()))));
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    assertThat(response.getPayload()).isEqualTo("[\"NewRegistrar\",\"TheRegistrar\"]");
  }

  @Test
  void testSuccess_getRegistrarIds() {
    saveRegistrar("registrarId");
    RegistrarsAction action =
        createAction(
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(
                    createUser(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build()))));
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    assertThat(response.getPayload())
        .isEqualTo("[\"NewRegistrar\",\"TheRegistrar\",\"registrarId\"]");
  }

  @Test
  void testFailure_getRegistrarIds() {
    saveRegistrar("registrarId");
    RegistrarsAction action =
        createAction(
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

  private RegistrarsAction createAction(AuthResult authResult) {
    response = new FakeResponse();
    return new RegistrarsAction(authResult, response, GSON);
  }
}
