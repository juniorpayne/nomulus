// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.registrar;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.request.auth.AuthenticatedRegistrarAccessor.Role.ADMIN;
import static google.registry.request.auth.AuthenticatedRegistrarAccessor.Role.OWNER;
import static google.registry.testing.AppEngineExtension.makeRegistrar2;
import static google.registry.testing.AppEngineExtension.makeRegistrarContact2;
import static google.registry.testing.AppEngineExtension.makeRegistrarContact3;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.SqlHelper.saveRegistryLock;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.api.client.http.HttpStatusCodes;
import com.google.appengine.api.users.User;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.gson.Gson;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.UserRoles;
import google.registry.model.domain.RegistryLock;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.request.Action.Method;
import google.registry.request.auth.AuthLevel;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import java.util.Map;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RegistryLockGetAction}. */
final class RegistryLockGetActionTest {

  private static final Gson GSON = new Gson();

  private final FakeClock fakeClock = new FakeClock();

  @RegisterExtension
  final AppEngineExtension appEngineExtension =
      AppEngineExtension.builder().withCloudSql().withClock(fakeClock).build();

  private final FakeResponse response = new FakeResponse();

  private User user;
  private AuthResult authResult;
  private AuthenticatedRegistrarAccessor accessor;
  private RegistryLockGetAction action;

  @BeforeEach
  void beforeEach() {
    user = userFromRegistrarPoc(makeRegistrarContact3());
    fakeClock.setTo(DateTime.parse("2000-06-08T22:00:00.0Z"));
    authResult = AuthResult.create(AuthLevel.USER, UserAuthInfo.create(user, false));
    accessor =
        AuthenticatedRegistrarAccessor.createForTesting(
            ImmutableSetMultimap.of(
                "TheRegistrar", OWNER,
                "NewRegistrar", OWNER));
    action =
        new RegistryLockGetAction(
            Method.GET, response, accessor, authResult, Optional.of("TheRegistrar"));
  }

  @Test
  void testSuccess_newConsoleUser() {
    RegistryLock regularLock =
        new RegistryLock.Builder()
            .setRepoId("repoId")
            .setDomainName("example.test")
            .setRegistrarId("TheRegistrar")
            .setVerificationCode("123456789ABCDEFGHJKLMNPQRSTUVWXY")
            .setRegistrarPocId("johndoe@theregistrar.com")
            .setLockCompletionTime(fakeClock.nowUtc())
            .build();
    saveRegistryLock(regularLock);
    google.registry.model.console.User consoleUser =
        new google.registry.model.console.User.Builder()
            .setEmailAddress("johndoe@theregistrar.com")
            .setGaiaId("gaiaId")
            .setUserRoles(
                new UserRoles.Builder()
                    .setRegistrarRoles(
                        ImmutableMap.of(
                            "TheRegistrar", RegistrarRole.ACCOUNT_MANAGER_WITH_REGISTRY_LOCK))
                    .build())
            .build();

    action.authResult = AuthResult.create(AuthLevel.USER, UserAuthInfo.create(consoleUser));
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    assertThat(GSON.fromJson(response.getPayload(), Map.class))
        .containsExactly(
            "status",
            "SUCCESS",
            "message",
            "Successful locks retrieval",
            "results",
            ImmutableList.of(
                ImmutableMap.of(
                    "lockEnabledForContact",
                    true,
                    "email",
                    "johndoe@theregistrar.com",
                    "clientId",
                    "TheRegistrar",
                    "locks",
                    ImmutableList.of(
                        new ImmutableMap.Builder<>()
                            .put("domainName", "example.test")
                            .put("lockedTime", "2000-06-08T22:00:00.000Z")
                            .put("lockedBy", "johndoe@theregistrar.com")
                            .put("isLockPending", false)
                            .put("isUnlockPending", false)
                            .put("userCanUnlock", true)
                            .build()))));
  }

  @Test
  void testSuccess_retrievesLocks() {
    RegistryLock expiredLock =
        new RegistryLock.Builder()
            .setRepoId("repoId")
            .setDomainName("expired.test")
            .setRegistrarId("TheRegistrar")
            .setVerificationCode("123456789ABCDEFGHJKLMNPQRSTUVWXY")
            .setRegistrarPocId("johndoe@theregistrar.com")
            .build();
    saveRegistryLock(expiredLock);
    RegistryLock expiredUnlock =
        new RegistryLock.Builder()
            .setRepoId("repoId")
            .setDomainName("expiredunlock.test")
            .setRegistrarId("TheRegistrar")
            .setVerificationCode("123456789ABCDEFGHJKLMNPQRSTUVWXY")
            .setRegistrarPocId("johndoe@theregistrar.com")
            .setLockCompletionTime(fakeClock.nowUtc())
            .setUnlockRequestTime(fakeClock.nowUtc())
            .build();
    saveRegistryLock(expiredUnlock);
    fakeClock.advanceBy(Duration.standardDays(1));

    RegistryLock regularLock =
        new RegistryLock.Builder()
            .setRepoId("repoId")
            .setDomainName("example.test")
            .setRegistrarId("TheRegistrar")
            .setVerificationCode("123456789ABCDEFGHJKLMNPQRSTUVWXY")
            .setRegistrarPocId("johndoe@theregistrar.com")
            .setLockCompletionTime(fakeClock.nowUtc())
            .build();
    fakeClock.advanceOneMilli();
    RegistryLock adminLock =
        new RegistryLock.Builder()
            .setRepoId("repoId")
            .setDomainName("adminexample.test")
            .setRegistrarId("TheRegistrar")
            .setVerificationCode("122222222ABCDEFGHJKLMNPQRSTUVWXY")
            .isSuperuser(true)
            .setLockCompletionTime(fakeClock.nowUtc())
            .build();
    RegistryLock incompleteLock =
        new RegistryLock.Builder()
            .setRepoId("repoId")
            .setDomainName("pending.test")
            .setRegistrarId("TheRegistrar")
            .setVerificationCode("111111111ABCDEFGHJKLMNPQRSTUVWXY")
            .setRegistrarPocId("johndoe@theregistrar.com")
            .build();

    RegistryLock incompleteUnlock =
        new RegistryLock.Builder()
            .setRepoId("repoId")
            .setDomainName("incompleteunlock.test")
            .setRegistrarId("TheRegistrar")
            .setVerificationCode("123456789ABCDEFGHJKLMNPQRSTUVWXY")
            .setRegistrarPocId("johndoe@theregistrar.com")
            .setLockCompletionTime(fakeClock.nowUtc())
            .setUnlockRequestTime(fakeClock.nowUtc())
            .build();

    RegistryLock unlockedLock =
        new RegistryLock.Builder()
            .setRepoId("repoId")
            .setDomainName("unlocked.test")
            .setRegistrarId("TheRegistrar")
            .setRegistrarPocId("johndoe@theregistrar.com")
            .setVerificationCode("123456789ABCDEFGHJKLMNPQRSTUUUUU")
            .setLockCompletionTime(fakeClock.nowUtc())
            .setUnlockRequestTime(fakeClock.nowUtc())
            .setUnlockCompletionTime(fakeClock.nowUtc())
            .build();

    saveRegistryLock(regularLock);
    saveRegistryLock(adminLock);
    saveRegistryLock(incompleteLock);
    saveRegistryLock(incompleteUnlock);
    saveRegistryLock(unlockedLock);

    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    assertThat(GSON.fromJson(response.getPayload(), Map.class))
        .containsExactly(
            "status", "SUCCESS",
            "message", "Successful locks retrieval",
            "results",
                ImmutableList.of(
                    ImmutableMap.of(
                        "lockEnabledForContact",
                        true,
                        "email",
                        "Marla.Singer.RegistryLock@crr.com",
                        "clientId",
                        "TheRegistrar",
                        "locks",
                        ImmutableList.of(
                            new ImmutableMap.Builder<>()
                                .put("domainName", "adminexample.test")
                                .put("lockedTime", "2000-06-09T22:00:00.001Z")
                                .put("lockedBy", "admin")
                                .put("userCanUnlock", false)
                                .put("isLockPending", false)
                                .put("isUnlockPending", false)
                                .build(),
                            new ImmutableMap.Builder<>()
                                .put("domainName", "example.test")
                                .put("lockedTime", "2000-06-09T22:00:00.000Z")
                                .put("lockedBy", "johndoe@theregistrar.com")
                                .put("userCanUnlock", true)
                                .put("isLockPending", false)
                                .put("isUnlockPending", false)
                                .build(),
                            new ImmutableMap.Builder<>()
                                .put("domainName", "expiredunlock.test")
                                .put("lockedTime", "2000-06-08T22:00:00.000Z")
                                .put("lockedBy", "johndoe@theregistrar.com")
                                .put("userCanUnlock", true)
                                .put("isLockPending", false)
                                .put("isUnlockPending", false)
                                .build(),
                            new ImmutableMap.Builder<>()
                                .put("domainName", "incompleteunlock.test")
                                .put("lockedTime", "2000-06-09T22:00:00.001Z")
                                .put("lockedBy", "johndoe@theregistrar.com")
                                .put("userCanUnlock", true)
                                .put("isLockPending", false)
                                .put("isUnlockPending", true)
                                .build(),
                            new ImmutableMap.Builder<>()
                                .put("domainName", "pending.test")
                                .put("lockedTime", "")
                                .put("lockedBy", "johndoe@theregistrar.com")
                                .put("userCanUnlock", true)
                                .put("isLockPending", true)
                                .put("isUnlockPending", false)
                                .build()))));
  }

  @Test
  void testFailure_invalidMethod() {
    action.method = Method.POST;
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, action::run);
    assertThat(thrown).hasMessageThat().isEqualTo("Only GET requests allowed");
  }

  @Test
  void testFailure_noAuthInfo() {
    action.authResult = AuthResult.NOT_AUTHENTICATED;
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, action::run);
    assertThat(thrown).hasMessageThat().isEqualTo("User auth info must be present");
  }

  @Test
  void testFailure_noClientId() {
    action.paramClientId = Optional.empty();
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, action::run);
    assertThat(thrown).hasMessageThat().isEqualTo("clientId must be present");
  }

  @Test
  void testFailure_noRegistrarAccess() {
    accessor = AuthenticatedRegistrarAccessor.createForTesting(ImmutableSetMultimap.of());
    action =
        new RegistryLockGetAction(
            Method.GET, response, accessor, authResult, Optional.of("TheRegistrar"));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  @Test
  void testSuccess_readOnlyAccessForOtherUsers() {
    // If lock is not enabled for a user, this should be read-only
    persistResource(
        makeRegistrarContact3().asBuilder().setAllowedToSetRegistryLockPassword(true).build());
    action.run();
    assertThat(GSON.fromJson(response.getPayload(), Map.class).get("results"))
        .isEqualTo(
            ImmutableList.of(
                ImmutableMap.of(
                    "lockEnabledForContact",
                    false,
                    "email",
                    "Marla.Singer.RegistryLock@crr.com",
                    "clientId",
                    "TheRegistrar",
                    "locks",
                    ImmutableList.of())));
  }

  @Test
  void testSuccess_lockAllowedForAdmin() {
    // Locks are allowed for admins even when they're not enabled for the registrar
    persistResource(makeRegistrar2().asBuilder().setRegistryLockAllowed(false).build());
    // disallow the other user
    persistResource(makeRegistrarContact2().asBuilder().setLoginEmailAddress(null).build());
    authResult = AuthResult.create(AuthLevel.USER, UserAuthInfo.create(user, true));
    accessor =
        AuthenticatedRegistrarAccessor.createForTesting(
            ImmutableSetMultimap.of(
                "TheRegistrar", ADMIN,
                "NewRegistrar", OWNER));
    action =
        new RegistryLockGetAction(
            Method.GET, response, accessor, authResult, Optional.of("TheRegistrar"));
    action.run();
    assertThat(GSON.fromJson(response.getPayload(), Map.class).get("results"))
        .isEqualTo(
            ImmutableList.of(
                ImmutableMap.of(
                    "lockEnabledForContact",
                    true,
                    "email",
                    "Marla.Singer@crr.com",
                    "clientId",
                    "TheRegistrar",
                    "locks",
                    ImmutableList.of())));
  }

  @Test
  void testSuccess_linkedToLoginContactEmail() {
    // Note that the email address is case-insensitive.
    user = new User("marla.singer@crr.com", "crr.com", user.getUserId());
    authResult = AuthResult.create(AuthLevel.USER, UserAuthInfo.create(user, false));
    action =
        new RegistryLockGetAction(
            Method.GET, response, accessor, authResult, Optional.of("TheRegistrar"));
    action.run();
    assertThat(GSON.fromJson(response.getPayload(), Map.class).get("results"))
        .isEqualTo(
            ImmutableList.of(
                ImmutableMap.of(
                    "lockEnabledForContact",
                    true,
                    "email",
                    "Marla.Singer.RegistryLock@crr.com",
                    "clientId",
                    "TheRegistrar",
                    "locks",
                    ImmutableList.of())));
  }

  @Test
  void testFailure_lockNotAllowedForRegistrar() {
    // The UI shouldn't be making requests where lock isn't enabled for this registrar
    action =
        new RegistryLockGetAction(
            Method.GET, response, accessor, authResult, Optional.of("NewRegistrar"));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
  }

  @Test
  void testFailure_accessDenied() {
    accessor = AuthenticatedRegistrarAccessor.createForTesting(ImmutableSetMultimap.of());
    action =
        new RegistryLockGetAction(
            Method.GET, response, accessor, authResult, Optional.of("TheRegistrar"));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  @Test
  void testFailure_badRegistrar() {
    action =
        new RegistryLockGetAction(
            Method.GET, response, accessor, authResult, Optional.of("SomeBadRegistrar"));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  static User userFromRegistrarPoc(RegistrarPoc registrarPoc) {
    return new User(registrarPoc.getLoginEmailAddress(), "gmail.com");
  }
}
