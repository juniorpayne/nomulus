// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.domain.token;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.CANCELLED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.ENDED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.NOT_STARTED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.VALID;
import static google.registry.model.domain.token.AllocationToken.TokenType.PACKAGE;
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.model.domain.token.AllocationToken.TokenType.UNLIMITED_USE;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.Buildable;
import google.registry.model.EntityTestCase;
import google.registry.model.billing.BillingEvent.RenewalPriceBehavior;
import google.registry.model.domain.Domain;
import google.registry.model.domain.token.AllocationToken.RegistrationBehavior;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.domain.token.AllocationToken.TokenType;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.util.SerializeUtils;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link AllocationToken}. */
public class AllocationTokenTest extends EntityTestCase {

  public AllocationTokenTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @BeforeEach
  void beforeEach() {
    createTld("foo");
  }

  @Test
  void testPersistence() {
    AllocationToken unlimitedUseToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123Unlimited")
                .setTokenType(UNLIMITED_USE)
                .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
                .setAllowedTlds(ImmutableSet.of("dev", "app"))
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar, NewRegistrar"))
                .setDiscountFraction(0.5)
                .setDiscountPremiums(true)
                .setDiscountYears(3)
                .setTokenStatusTransitions(
                    ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                        .put(START_OF_TIME, NOT_STARTED)
                        .put(DateTime.now(UTC), TokenStatus.VALID)
                        .put(DateTime.now(UTC).plusWeeks(8), TokenStatus.ENDED)
                        .build())
                .build());
    assertThat(loadByEntity(unlimitedUseToken)).isEqualTo(unlimitedUseToken);

    Domain domain = persistActiveDomain("example.foo");
    HistoryEntryId historyEntryId = new HistoryEntryId(domain.getRepoId(), 1);
    AllocationToken singleUseToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123Single")
                .setRedemptionHistoryId(historyEntryId)
                .setDomainName("example.foo")
                .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
                .setTokenType(SINGLE_USE)
                .build());
    assertThat(loadByEntity(singleUseToken)).isEqualTo(singleUseToken);
  }

  @Test
  void testSerializable() {
    AllocationToken unlimitedUseToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123Unlimited")
                .setTokenType(UNLIMITED_USE)
                .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
                .setAllowedTlds(ImmutableSet.of("dev", "app"))
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar, NewRegistrar"))
                .setDiscountFraction(0.5)
                .setDiscountPremiums(true)
                .setDiscountYears(3)
                .setTokenStatusTransitions(
                    ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                        .put(START_OF_TIME, NOT_STARTED)
                        .put(DateTime.now(UTC), TokenStatus.VALID)
                        .put(DateTime.now(UTC).plusWeeks(8), TokenStatus.ENDED)
                        .build())
                .build());
    AllocationToken persisted = loadByEntity(unlimitedUseToken);
    assertThat(SerializeUtils.serializeDeserialize(persisted)).isEqualTo(persisted);

    Domain domain = persistActiveDomain("example.foo");
    HistoryEntryId historyEntryId = new HistoryEntryId(domain.getRepoId(), 1);
    AllocationToken singleUseToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123Single")
                .setRedemptionHistoryId(historyEntryId)
                .setDomainName("example.foo")
                .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
                .setTokenType(SINGLE_USE)
                .build());
    persisted = loadByEntity(singleUseToken);
    assertThat(SerializeUtils.serializeDeserialize(persisted)).isEqualTo(persisted);
  }

  @Test
  void testCreationTime_autoPopulates() {
    AllocationToken tokenBeforePersisting =
        new AllocationToken.Builder().setToken("abc123").setTokenType(SINGLE_USE).build();
    assertThat(tokenBeforePersisting.getCreationTime()).isEmpty();
    AllocationToken tokenAfterPersisting = persistResource(tokenBeforePersisting);
    assertThat(tokenAfterPersisting.getCreationTime()).hasValue(fakeClock.nowUtc());
  }

  @Test
  void testGetRenewalBehavior_returnsDefaultRenewBehavior() {
    assertThat(
            persistResource(
                    new AllocationToken.Builder()
                        .setToken("abc123")
                        .setTokenType(SINGLE_USE)
                        .build())
                .getRenewalPriceBehavior())
        .isEqualTo(RenewalPriceBehavior.DEFAULT);
  }

  @Test
  void testSetRenewalBehavior_assertsRenewalBehaviorIsNotDefault() {
    assertThat(
            persistResource(
                    new AllocationToken.Builder()
                        .setToken("abc123")
                        .setTokenType(SINGLE_USE)
                        .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                        .build())
                .getRenewalPriceBehavior())
        .isEqualTo(RenewalPriceBehavior.SPECIFIED);
  }

  @Test
  void testSetRenewalBehavior_assertRenewalBehaviorIsModified() {
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setRenewalPriceBehavior(RenewalPriceBehavior.NONPREMIUM)
                .build());
    AllocationToken loadedToken = loadByEntity(token);
    assertThat(token).isEqualTo(loadedToken);
    persistResource(
        loadedToken.asBuilder().setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED).build());
    assertThat(loadByEntity(token).getRenewalPriceBehavior())
        .isEqualTo(RenewalPriceBehavior.SPECIFIED);
  }

  @Test
  void testSetCreationTime_cantCallMoreThanOnce() {
    AllocationToken.Builder builder =
        new AllocationToken.Builder()
            .setToken("foobar")
            .setTokenType(SINGLE_USE)
            .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"));
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> builder.setCreationTimeForTest(DateTime.parse("2010-11-13T05:00:00Z")));
    assertThat(thrown).hasMessageThat().isEqualTo("Creation time can only be set once");
  }

  @Test
  void testSetToken_cantCallMoreThanOnce() {
    AllocationToken.Builder builder = new AllocationToken.Builder().setToken("foobar");
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> builder.setToken("barfoo"));
    assertThat(thrown).hasMessageThat().isEqualTo("Token can only be set once");
  }

  @Test
  void testSetTokenType_cantCallMoreThanOnce() {
    AllocationToken.Builder builder =
        new AllocationToken.Builder().setTokenType(TokenType.UNLIMITED_USE);
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> builder.setTokenType(SINGLE_USE));
    assertThat(thrown).hasMessageThat().isEqualTo("Token type can only be set once");
  }

  @Test
  void testFail_packageTokenNotSpecifiedRenewalBehavior() {
    AllocationToken.Builder builder =
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(TokenType.PACKAGE)
            .setRenewalPriceBehavior(RenewalPriceBehavior.DEFAULT);
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, builder::build);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Package tokens must have renewalPriceBehavior set to SPECIFIED");
  }

  @Test
  void testFail_packageTokenDiscountPremium() {
    AllocationToken.Builder builder =
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(TokenType.PACKAGE)
            .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
            .setDiscountPremiums(true);
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, builder::build);
    assertThat(thrown).hasMessageThat().isEqualTo("Package tokens cannot discount premium names");
  }

  @Test
  void testBuild_DomainNameWithLessThanTwoParts() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AllocationToken.Builder()
                    .setDomainName("example")
                    .setTokenType(SINGLE_USE)
                    .setToken("barfoo")
                    .build());
    assertThat(thrown)
        .hasCauseThat()
        .hasMessageThat()
        .isEqualTo("Domain name must have exactly one part above the TLD");
    assertThat(thrown).hasMessageThat().isEqualTo("Invalid domain name: example");
  }

  @Test
  void testBuild_invalidTld() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AllocationToken.Builder()
                    .setDomainName("example.nosuchtld")
                    .setTokenType(SINGLE_USE)
                    .setToken("barfoo")
                    .build());
    assertThat(thrown)
        .hasCauseThat()
        .hasMessageThat()
        .isEqualTo("Domain name is under tld nosuchtld which doesn't exist");
    assertThat(thrown).hasMessageThat().isEqualTo("Invalid domain name: example.nosuchtld");
  }

  @Test
  void testBuild_domainNameOnlyOnSingleUse() {
    AllocationToken.Builder builder =
        new AllocationToken.Builder()
            .setToken("foobar")
            .setTokenType(TokenType.UNLIMITED_USE)
            .setDomainName("example.foo");
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, builder::build);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Domain name can only be specified for SINGLE_USE tokens");
  }

  @Test
  void testBuild_onlyOneClientInPackage() {
    Buildable.Builder<AllocationToken> builder =
        new AllocationToken.Builder()
            .setToken("foobar")
            .setTokenType(PACKAGE)
            .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
            .setAllowedRegistrarIds(ImmutableSet.of("foo", "bar"));
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, builder::build);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("PACKAGE tokens must have exactly one allowed client registrar");
  }

  @Test
  void testBuild_redemptionHistoryEntryOnlyInSingleUse() {
    Domain domain = persistActiveDomain("blahdomain.foo");
    HistoryEntryId historyEntryId = new HistoryEntryId(domain.getRepoId(), 1);
    AllocationToken.Builder builder =
        new AllocationToken.Builder()
            .setToken("foobar")
            .setTokenType(TokenType.UNLIMITED_USE)
            .setRedemptionHistoryId(historyEntryId);
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, builder::build);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Redemption history entry can only be specified for SINGLE_USE tokens");
  }

  @Test
  void testSetTransitions_notStartOfTime() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AllocationToken.Builder()
                    .setTokenStatusTransitions(
                        ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                            .put(DateTime.now(UTC), NOT_STARTED)
                            .put(DateTime.now(UTC).plusDays(1), TokenStatus.VALID)
                            .put(DateTime.now(UTC).plusDays(2), TokenStatus.ENDED)
                            .build()));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("tokenStatusTransitions map must start at START_OF_TIME.");
  }

  @Test
  void testSetTransitions_badInitialValue() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AllocationToken.Builder()
                    .setTokenStatusTransitions(
                        ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                            .put(START_OF_TIME, TokenStatus.VALID)
                            .put(DateTime.now(UTC), TokenStatus.ENDED)
                            .build()));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("tokenStatusTransitions must start with NOT_STARTED");
  }

  @Test
  void testSetTransitions_invalidInitialTransitions() {
    // NOT_STARTED can only go to VALID or CANCELLED
    assertBadInitialTransition(NOT_STARTED);
    assertBadInitialTransition(ENDED);
  }

  @Test
  void testSetTransitions_badTransitionsFromValid() {
    // VALID can only go to ENDED or CANCELLED
    assertBadTransition(
        ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
            .put(START_OF_TIME, NOT_STARTED)
            .put(DateTime.now(UTC), VALID)
            .put(DateTime.now(UTC).plusDays(1), VALID)
            .build(),
        VALID,
        VALID);
    assertBadTransition(
        ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
            .put(START_OF_TIME, NOT_STARTED)
            .put(DateTime.now(UTC), VALID)
            .put(DateTime.now(UTC).plusDays(1), NOT_STARTED)
            .build(),
        VALID,
        NOT_STARTED);
  }

  @Test
  void testSetTransitions_terminalTransitions() {
    // both ENDED and CANCELLED are terminal
    assertTerminal(ENDED);
    assertTerminal(CANCELLED);
  }

  @Test
  void testSetDiscountFractionTooHigh() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new AllocationToken.Builder().setDiscountFraction(1.1));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Discount fraction must be between 0 and 1 inclusive");
  }

  @Test
  void testSetDiscountFractionTooLow() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new AllocationToken.Builder().setDiscountFraction(-.0001));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Discount fraction must be between 0 and 1 inclusive");
  }

  @Test
  void testSetDiscountYearsTooHigh() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new AllocationToken.Builder().setDiscountYears(11));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Discount years must be between 1 and 10 inclusive");
  }

  @Test
  void testSetDiscountYearsTooLow() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new AllocationToken.Builder().setDiscountYears(0));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Discount years must be between 1 and 10 inclusive");
  }

  @Test
  void testBuild_noTokenType() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new AllocationToken.Builder().setToken("foobar").build());
    assertThat(thrown).hasMessageThat().isEqualTo("Token type must be specified");
  }

  @Test
  void testBuild_noToken() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new AllocationToken.Builder().setTokenType(SINGLE_USE).build());
    assertThat(thrown).hasMessageThat().isEqualTo("Token must not be null or empty");
  }

  @Test
  void testBuild_emptyToken() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new AllocationToken.Builder().setToken("").setTokenType(SINGLE_USE).build());
    assertThat(thrown).hasMessageThat().isEqualTo("Token must not be blank");
  }

  @Test
  void testBuild_discountPremiumsRequiresDiscountFraction() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AllocationToken.Builder()
                    .setToken("abc")
                    .setTokenType(SINGLE_USE)
                    .setDiscountPremiums(true)
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Discount premiums can only be specified along with a discount fraction");
  }

  @Test
  void testBuild_discountYearsRequiresDiscountFraction() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AllocationToken.Builder()
                    .setToken("abc")
                    .setTokenType(SINGLE_USE)
                    .setDiscountYears(2)
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Discount years can only be specified along with a discount fraction");
  }

  @Test
  void testBuild_registrationBehaviors() {
    createTld("tld");
    // BYPASS_TLD_STATE doesn't require a domain
    AllocationToken token =
        new AllocationToken.Builder()
            .setToken("abc")
            .setTokenType(SINGLE_USE)
            .setRegistrationBehavior(RegistrationBehavior.BYPASS_TLD_STATE)
            .build();
    // ANCHOR_TENANT does
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    token
                        .asBuilder()
                        .setRegistrationBehavior(RegistrationBehavior.ANCHOR_TENANT)
                        .build()))
        .hasMessageThat()
        .isEqualTo("ANCHOR_TENANT tokens must be tied to a domain");
    token
        .asBuilder()
        .setRegistrationBehavior(RegistrationBehavior.ANCHOR_TENANT)
        .setDomainName("example.tld")
        .build();
  }

  private void assertBadInitialTransition(TokenStatus status) {
    assertBadTransition(
        ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
            .put(START_OF_TIME, NOT_STARTED)
            .put(DateTime.now(UTC), status)
            .build(),
        NOT_STARTED,
        status);
  }

  private void assertBadTransition(
      ImmutableSortedMap<DateTime, TokenStatus> map, TokenStatus from, TokenStatus to) {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new AllocationToken.Builder().setTokenStatusTransitions(map));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            String.format("tokenStatusTransitions map cannot transition from %s to %s.", from, to));
  }

  private void assertTerminal(TokenStatus status) {
    // The "terminal" message is slightly different, so it must be tested separately
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AllocationToken.Builder()
                    .setTokenStatusTransitions(
                        ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                            .put(START_OF_TIME, NOT_STARTED)
                            .put(DateTime.now(UTC), VALID)
                            .put(DateTime.now(UTC).plusDays(1), status)
                            .put(DateTime.now(UTC).plusDays(2), CANCELLED)
                            .build()));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(String.format("tokenStatusTransitions map cannot transition from %s.", status));
  }
}
