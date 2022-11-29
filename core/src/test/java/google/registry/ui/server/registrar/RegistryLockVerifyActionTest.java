// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.SqlHelper.getRegistryLockByVerificationCode;
import static google.registry.testing.SqlHelper.saveRegistryLock;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static javax.servlet.http.HttpServletResponse.SC_MOVED_TEMPORARILY;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserService;
import com.google.appengine.api.users.UserServiceFactory;
import com.google.common.collect.ImmutableMap;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.RegistryLock;
import google.registry.model.host.Host;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Registry;
import google.registry.request.auth.AuthLevel;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.UserAuthInfo;
import google.registry.security.XsrfTokenManager;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.UserInfo;
import google.registry.tools.DomainLockUtils;
import google.registry.util.StringGenerator;
import google.registry.util.StringGenerator.Alphabets;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RegistryLockVerifyAction}. */
final class RegistryLockVerifyActionTest {

  private final FakeClock fakeClock = new FakeClock();

  @RegisterExtension
  final AppEngineExtension appEngineExtension =
      AppEngineExtension.builder()
          .withCloudSql()
          .withClock(fakeClock)
          .withUserService(UserInfo.create("marla.singer@example.com"))
          .build();

  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final UserService userService = UserServiceFactory.getUserService();
  private final User user = new User("marla.singer@example.com", "gmail.com", "12345");
  private final String lockId = "123456789ABCDEFGHJKLMNPQRSTUVWXY";
  private final StringGenerator stringGenerator =
      new DeterministicStringGenerator(Alphabets.BASE_58);

  private FakeResponse response;
  private Domain domain;
  private AuthResult authResult;
  private RegistryLockVerifyAction action;
  private CloudTasksHelper cloudTasksHelper = new CloudTasksHelper(fakeClock);

  @BeforeEach
  void beforeEach() {
    createTlds("tld", "net");
    Host host = persistActiveHost("ns1.example.net");
    domain = persistResource(DatabaseHelper.newDomain("example.tld", host));
    when(request.getRequestURI()).thenReturn("https://registry.example/registry-lock-verification");
    action = createAction(lockId, true);
  }

  @Test
  void testSuccess_lockDomain() {
    saveRegistryLock(createLock());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(reloadDomain().getStatusValues()).containsExactlyElementsIn(REGISTRY_LOCK_STATUSES);
    assertThat(response.getPayload()).contains("Success: lock has been applied to example.tld");
    DomainHistory historyEntry =
        getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_UPDATE, DomainHistory.class);
    assertThat(historyEntry.getRequestedByRegistrar()).isTrue();
    assertThat(historyEntry.getBySuperuser()).isFalse();
    assertThat(historyEntry.getReason())
        .isEqualTo("Lock of a domain through a RegistryLock operation");
    assertBillingEvent(historyEntry);
  }

  @Test
  void testSuccess_unlockDomain() {
    action = createAction(lockId, false);
    domain = persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    saveRegistryLock(createLock().asBuilder().setUnlockRequestTime(fakeClock.nowUtc()).build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).contains("Success: unlock has been applied to example.tld");
    assertThat(reloadDomain().getStatusValues()).containsNoneIn(REGISTRY_LOCK_STATUSES);
    DomainHistory historyEntry =
        getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_UPDATE, DomainHistory.class);
    assertThat(historyEntry.getRequestedByRegistrar()).isTrue();
    assertThat(historyEntry.getBySuperuser()).isFalse();
    assertThat(historyEntry.getReason())
        .isEqualTo("Unlock of a domain through a RegistryLock operation");
    assertBillingEvent(historyEntry);
  }

  @Test
  void testSuccess_adminLock_createsOnlyHistoryEntry() {
    action.authResult = AuthResult.create(AuthLevel.USER, UserAuthInfo.create(user, true));
    saveRegistryLock(createLock().asBuilder().isSuperuser(true).build());

    action.run();
    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_UPDATE);
    assertThat(historyEntry.getRequestedByRegistrar()).isFalse();
    assertThat(historyEntry.getBySuperuser()).isTrue();
    DatabaseHelper.assertNoBillingEvents();
  }

  @Test
  void testFailure_badVerificationCode() {
    saveRegistryLock(
        createLock().asBuilder().setVerificationCode("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").build());
    action.run();
    assertThat(response.getPayload()).contains("Failed: Invalid verification code");
    assertNoDomainChanges();
  }

  @Test
  void testFailure_alreadyVerified() {
    saveRegistryLock(createLock().asBuilder().setLockCompletionTime(fakeClock.nowUtc()).build());
    action.run();
    assertThat(response.getPayload()).contains("Failed: Domain example.tld is already locked");
    assertNoDomainChanges();
  }

  @Test
  void testFailure_expired() {
    saveRegistryLock(createLock());
    fakeClock.advanceBy(Duration.standardHours(2));
    action.run();
    assertThat(response.getPayload())
        .contains("Failed: The pending lock has expired; please try again");
    assertNoDomainChanges();
  }

  @Test
  void testFailure_nonAdmin_verifyingAdminLock() {
    saveRegistryLock(createLock().asBuilder().isSuperuser(true).build());
    action.run();
    assertThat(response.getPayload()).contains("Failed: Non-admin user cannot complete admin lock");
    assertNoDomainChanges();
  }

  @Test
  void testFailure_alreadyUnlocked() {
    action = createAction(lockId, false);
    saveRegistryLock(
        createLock()
            .asBuilder()
            .setLockCompletionTime(fakeClock.nowUtc())
            .setUnlockRequestTime(fakeClock.nowUtc())
            .setUnlockCompletionTime(fakeClock.nowUtc())
            .build());
    action.run();
    assertThat(response.getPayload()).contains("Failed: Domain example.tld is already unlocked");
    assertNoDomainChanges();
  }

  @Test
  void testFailure_alreadyLocked() {
    saveRegistryLock(createLock());
    domain = persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    action.run();
    assertThat(response.getPayload()).contains("Failed: Domain example.tld is already locked");
    assertNoDomainChanges();
  }

  @Test
  void testFailure_notLoggedIn() {
    action.authResult = AuthResult.NOT_AUTHENTICATED;
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_MOVED_TEMPORARILY);
    assertThat(response.getHeaders()).containsKey("Location");
    assertNoDomainChanges();
  }

  @Test
  void testFailure_doesNotChangeLockObject() {
    // A failure when performing Datastore actions means that no actions should be taken in the
    // Cloud SQL RegistryLock object
    RegistryLock lock = createLock();
    saveRegistryLock(lock);
    // reload the lock to pick up creation time
    lock = getRegistryLockByVerificationCode(lock.getVerificationCode()).get();
    fakeClock.advanceOneMilli();
    domain = persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    action.run();
    // we would have failed during the Datastore segment of the action
    assertThat(response.getPayload()).contains("Failed: Domain example.tld is already locked");

    // verify that the changes to the SQL object were rolled back
    RegistryLock afterAction = getRegistryLockByVerificationCode(lock.getVerificationCode()).get();
    assertThat(afterAction).isEqualTo(lock);
  }

  @Test
  void testFailure_isLockTrue_shouldBeFalse() {
    domain = persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    saveRegistryLock(
        createLock()
            .asBuilder()
            .setLockCompletionTime(fakeClock.nowUtc())
            .setUnlockRequestTime(fakeClock.nowUtc())
            .build());
    action.run();
    assertThat(response.getPayload()).contains("Failed: Domain example.tld is already locked");
  }

  @Test
  void testFailure_isLockFalse_shouldBeTrue() {
    action = createAction(lockId, false);
    saveRegistryLock(createLock());
    action.run();
    assertThat(response.getPayload()).contains("Failed: Domain example.tld is already unlocked");
  }

  @Test
  void testFailure_lock_unlock_lockAgain() {
    RegistryLock lock = saveRegistryLock(createLock());
    action.run();
    assertThat(response.getPayload()).contains("Success: lock has been applied to example.tld");
    String unlockVerificationCode = "some-unlock-code";
    saveRegistryLock(
        lock.asBuilder()
            .setVerificationCode(unlockVerificationCode)
            .setUnlockRequestTime(fakeClock.nowUtc())
            .build());
    action = createAction(unlockVerificationCode, false);
    action.run();
    assertThat(response.getPayload()).contains("Success: unlock has been applied to example.tld");
    action = createAction(lockId, true);
    action.run();
    assertThat(response.getPayload()).contains("Failed: Invalid verification code");
  }

  @Test
  void testFailure_lock_lockAgain() {
    saveRegistryLock(createLock());
    action.run();
    assertThat(response.getPayload()).contains("Success: lock has been applied to example.tld");
    action = createAction(lockId, true);
    action.run();
    assertThat(response.getPayload()).contains("Failed: Domain example.tld is already locked");
  }

  @Test
  void testFailure_unlock_unlockAgain() {
    action = createAction(lockId, false);
    domain = persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    saveRegistryLock(createLock().asBuilder().setUnlockRequestTime(fakeClock.nowUtc()).build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).contains("Success: unlock has been applied to example.tld");
    action = createAction(lockId, false);
    action.run();
    assertThat(response.getPayload()).contains("Failed: Domain example.tld is already unlocked");
  }

  private RegistryLock createLock() {
    return new RegistryLock.Builder()
        .setDomainName("example.tld")
        .setRegistrarId("TheRegistrar")
        .setRepoId("repoId")
        .setRegistrarPocId("marla.singer@example.com")
        .isSuperuser(false)
        .setVerificationCode(lockId)
        .build();
  }

  private Domain reloadDomain() {
    return loadByEntity(domain);
  }

  private void assertNoDomainChanges() {
    assertThat(reloadDomain()).isEqualTo(domain);
  }

  private void assertBillingEvent(DomainHistory historyEntry) {
    DatabaseHelper.assertBillingEvents(
        new BillingEvent.OneTime.Builder()
            .setReason(Reason.SERVER_STATUS)
            .setTargetId(domain.getForeignKey())
            .setRegistrarId(domain.getCurrentSponsorRegistrarId())
            .setCost(Registry.get(domain.getTld()).getRegistryLockOrUnlockBillingCost())
            .setEventTime(fakeClock.nowUtc())
            .setBillingTime(fakeClock.nowUtc())
            .setDomainHistory(historyEntry)
            .build());
  }

  private RegistryLockVerifyAction createAction(String lockVerificationCode, Boolean isLock) {
    response = new FakeResponse();
    RegistryLockVerifyAction action =
        new RegistryLockVerifyAction(
            new DomainLockUtils(
                stringGenerator, "adminreg", cloudTasksHelper.getTestCloudTasksUtils()),
            lockVerificationCode,
            isLock);
    authResult = AuthResult.create(AuthLevel.USER, UserAuthInfo.create(user, false));
    action.req = request;
    action.response = response;
    action.authResult = authResult;
    action.userService = userService;
    action.logoFilename = "logo.png";
    action.productName = "Nomulus";
    action.analyticsConfig = ImmutableMap.of("googleAnalyticsId", "sampleId");
    action.xsrfTokenManager = new XsrfTokenManager(new FakeClock(), action.userService);
    return action;
  }
}
