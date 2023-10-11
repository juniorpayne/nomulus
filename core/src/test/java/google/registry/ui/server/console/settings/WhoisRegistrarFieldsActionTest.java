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

package google.registry.ui.server.console.settings;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.client.http.HttpStatusCodes;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrar.Registrar;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.RequestModule;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.Role;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.ui.server.registrar.RegistrarConsoleModule;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link WhoisRegistrarFieldsAction}. */
public class WhoisRegistrarFieldsActionTest {

  private static final Gson GSON = RequestModule.provideGson();
  private final FakeClock clock = new FakeClock(DateTime.parse("2023-08-01T00:00:00.000Z"));
  private final FakeResponse fakeResponse = new FakeResponse();
  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final AuthenticatedRegistrarAccessor registrarAccessor =
      AuthenticatedRegistrarAccessor.createForTesting(
          ImmutableSetMultimap.of("TheRegistrar", Role.OWNER, "NewRegistrar", Role.OWNER));

  private final HashMap<String, Object> uiRegistrarMap =
      Maps.newHashMap(
          ImmutableMap.of(
              "registrarId",
              "TheRegistrar",
              "whoisServer",
              "whois.nic.google",
              "type",
              "REAL",
              "emailAddress",
              "the.registrar@example.com",
              "state",
              "ACTIVE",
              "url",
              "\"http://my.fake.url\"",
              "localizedAddress",
              "{\"street\": [\"123 Example Boulevard\"], \"city\": \"Williamsburg\", \"state\":"
                  + " \"NY\", \"zip\": \"11201\", \"countryCode\": \"US\"}"));

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @Test
  void testSuccess_setsAllFields() throws Exception {
    Registrar oldRegistrar = Registrar.loadRequiredRegistrarCached("TheRegistrar");
    assertThat(oldRegistrar.getWhoisServer()).isEqualTo("whois.nic.fakewhois.example");
    assertThat(oldRegistrar.getUrl()).isEqualTo("http://my.fake.url");
    ImmutableMap<String, Object> addressMap =
        ImmutableMap.of(
            "street",
            ImmutableList.of("123 Fake St"),
            "city",
            "Fakeville",
            "state",
            "NL",
            "zip",
            "10011",
            "countryCode",
            "CA");
    uiRegistrarMap.putAll(
        ImmutableMap.of(
            "whoisServer",
            "whois.nic.google",
            "url",
            "\"https://newurl.example\"",
            "localizedAddress",
            "{\"street\": [\"123 Fake St\"], \"city\": \"Fakeville\", \"state\":"
                + " \"NL\", \"zip\": \"10011\", \"countryCode\": \"CA\"}"));
    WhoisRegistrarFieldsAction action = createAction();
    action.run();
    assertThat(fakeResponse.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    Registrar newRegistrar = Registrar.loadByRegistrarId("TheRegistrar").get(); // skip cache
    assertThat(newRegistrar.getWhoisServer()).isEqualTo("whois.nic.google");
    assertThat(newRegistrar.getUrl()).isEqualTo("https://newurl.example");
    assertThat(newRegistrar.getLocalizedAddress().toJsonMap()).isEqualTo(addressMap);
    // the non-changed fields should be the same
    assertAboutImmutableObjects()
        .that(newRegistrar)
        .isEqualExceptFields(oldRegistrar, "whoisServer", "url", "localizedAddress");
  }

  @Test
  void testFailure_noAccessToRegistrar() throws Exception {
    Registrar newRegistrar = Registrar.loadByRegistrarIdCached("NewRegistrar").get();
    AuthResult onlyTheRegistrar =
        AuthResult.createUser(
            UserAuthInfo.create(
                new User.Builder()
                    .setEmailAddress("email@email.example")
                    .setUserRoles(
                        new UserRoles.Builder()
                            .setRegistrarRoles(
                                ImmutableMap.of("TheRegistrar", RegistrarRole.PRIMARY_CONTACT))
                            .build())
                    .build()));
    uiRegistrarMap.put("registrarId", "NewRegistrar");
    WhoisRegistrarFieldsAction action = createAction(onlyTheRegistrar);
    action.run();
    assertThat(fakeResponse.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_FORBIDDEN);
    // should be no change
    assertThat(DatabaseHelper.loadByEntity(newRegistrar)).isEqualTo(newRegistrar);
  }

  private AuthResult defaultUserAuth() {
    return AuthResult.createUser(
        UserAuthInfo.create(
            new User.Builder()
                .setEmailAddress("email@email.example")
                .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build())
                .build()));
  }

  private WhoisRegistrarFieldsAction createAction() throws IOException {
    return createAction(defaultUserAuth());
  }

  private WhoisRegistrarFieldsAction createAction(AuthResult authResult) throws IOException {
    when(request.getReader())
        .thenReturn(new BufferedReader(new StringReader(uiRegistrarMap.toString())));
    return new WhoisRegistrarFieldsAction(
        authResult,
        fakeResponse,
        GSON,
        registrarAccessor,
        RegistrarConsoleModule.provideRegistrar(
            GSON, RequestModule.provideJsonBody(request, GSON)));
  }
}
