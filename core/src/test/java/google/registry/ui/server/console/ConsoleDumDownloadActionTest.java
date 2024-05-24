// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.Action;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeConsoleApiParams;
import google.registry.testing.FakeResponse;
import google.registry.tools.GsonUtils;
import google.registry.ui.server.registrar.ConsoleApiParams;
import java.io.IOException;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ConsoleDumDownloadActionTest {

  private static final Gson GSON = GsonUtils.provideGson();

  private final FakeClock clock = new FakeClock(DateTime.parse("2024-04-15T00:00:00.000Z"));

  private ConsoleApiParams consoleApiParams;

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    for (int i = 0; i < 3; i++) {
      DatabaseHelper.persistActiveDomain(
          i + "exists.tld", clock.nowUtc(), clock.nowUtc().plusDays(300));
      clock.advanceOneMilli();
    }
    DatabaseHelper.persistDeletedDomain("deleted.tld", clock.nowUtc().minusDays(1));
  }

  @Test
  void testSuccess_returnsCorrectDomains() throws IOException {
    User user =
        new User.Builder()
            .setEmailAddress("email@email.com")
            .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build())
            .build();

    AuthResult authResult = AuthResult.createUser(UserAuthInfo.create(user));
    ConsoleDumDownloadAction action = createAction(Optional.of(authResult));
    action.run();
    ImmutableList<String> expected =
        ImmutableList.of(
            "Domain Name,Creation Time,Expiration Time,Domain Statuses",
            "2exists.tld,2024-04-15 00:00:00.002+00,2025-02-09 00:00:00.002+00,{INACTIVE}",
            "1exists.tld,2024-04-15 00:00:00.001+00,2025-02-09 00:00:00.001+00,{INACTIVE}",
            "0exists.tld,2024-04-15 00:00:00+00,2025-02-09 00:00:00+00,{INACTIVE}");
    FakeResponse response = (FakeResponse) consoleApiParams.response();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    ImmutableList<String> actual =
        ImmutableList.copyOf(response.getStringWriter().toString().split("\r\n"));
    assertThat(actual).containsExactlyElementsIn(expected);
  }

  @Test
  void testFailure_forbidden() {
    UserRoles userRoles =
        new UserRoles.Builder().setGlobalRole(GlobalRole.NONE).setIsAdmin(false).build();

    User user =
        new User.Builder().setEmailAddress("email@email.com").setUserRoles(userRoles).build();

    AuthResult authResult = AuthResult.createUser(UserAuthInfo.create(user));
    ConsoleDumDownloadAction action = createAction(Optional.of(authResult));
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  private ConsoleDumDownloadAction createAction(Optional<AuthResult> maybeAuthResult) {
    consoleApiParams = FakeConsoleApiParams.get(maybeAuthResult);
    when(consoleApiParams.request().getMethod()).thenReturn(Action.Method.GET.toString());
    return new ConsoleDumDownloadAction(clock, consoleApiParams, "TheRegistrar", "test_name");
  }
}
