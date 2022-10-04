// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableSet;
import google.registry.model.billing.BillingEvent.RenewalPriceBehavior;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenType;
import google.registry.model.domain.token.PackagePromotion;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GetPackagePromotionCommand}. */
public class GetPackagePromotionCommandTest extends CommandTestCase<GetPackagePromotionCommand> {

  @BeforeEach
  void beforeEach() {
    command.clock = fakeClock;
  }

  @Test
  void testSuccess() throws Exception {
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(TokenType.PACKAGE)
                .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
                .setAllowedTlds(ImmutableSet.of("foo"))
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                .setDiscountFraction(1)
                .build());
    PackagePromotion packagePromotion =
        new PackagePromotion.Builder()
            .setToken(token)
            .setMaxDomains(100)
            .setMaxCreates(500)
            .setPackagePrice(Money.of(CurrencyUnit.USD, 1000))
            .setNextBillingDate(DateTime.parse("2012-11-12T05:00:00Z"))
            .setLastNotificationSent(DateTime.parse("2010-11-12T05:00:00Z"))
            .build();
    jpaTm().transact(() -> jpaTm().put(packagePromotion));
    runCommand("abc123");
  }

  @Test
  void testSuccessMultiplePackages() throws Exception {
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(TokenType.PACKAGE)
                .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
                .setAllowedTlds(ImmutableSet.of("foo"))
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                .setDiscountFraction(1)
                .build());
    jpaTm()
        .transact(
            () ->
                jpaTm()
                    .put(
                        new PackagePromotion.Builder()
                            .setToken(token)
                            .setMaxDomains(100)
                            .setMaxCreates(500)
                            .setPackagePrice(Money.of(CurrencyUnit.USD, 1000))
                            .setNextBillingDate(DateTime.parse("2012-11-12T05:00:00Z"))
                            .setLastNotificationSent(DateTime.parse("2010-11-12T05:00:00Z"))
                            .build()));
    AllocationToken token2 =
        persistResource(
            new AllocationToken.Builder()
                .setToken("123abc")
                .setTokenType(TokenType.PACKAGE)
                .setCreationTimeForTest(DateTime.parse("2012-11-12T05:00:00Z"))
                .setAllowedTlds(ImmutableSet.of("foo"))
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                .setDiscountFraction(1)
                .build());
    jpaTm()
        .transact(
            () ->
                jpaTm()
                    .put(
                        new PackagePromotion.Builder()
                            .setToken(token2)
                            .setMaxDomains(1000)
                            .setMaxCreates(700)
                            .setPackagePrice(Money.of(CurrencyUnit.USD, 3000))
                            .setNextBillingDate(DateTime.parse("2014-11-12T05:00:00Z"))
                            .setLastNotificationSent(DateTime.parse("2013-11-12T05:00:00Z"))
                            .build()));

    runCommand("abc123", "123abc");
  }

  @Test
  void testFailure_packageDoesNotExist() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommand("fakeToken"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("PackagePromotion with package token fakeToken does not exist");
  }

  @Test
  void testFailure_noToken() {
    assertThrows(ParameterException.class, this::runCommand);
  }
}
