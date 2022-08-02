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

package google.registry.flows.domain;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.flows.domain.DomainFlowUtils.checkHasBillingAccount;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newRegistry;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.CHF;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSortedMap;
import google.registry.flows.EppException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.domain.DomainFlowUtils.BadDomainNameCharacterException;
import google.registry.flows.domain.DomainFlowUtils.BadDomainNamePartsCountException;
import google.registry.flows.domain.DomainFlowUtils.DashesInThirdAndFourthException;
import google.registry.flows.domain.DomainFlowUtils.DomainLabelTooLongException;
import google.registry.flows.domain.DomainFlowUtils.EmptyDomainNamePartException;
import google.registry.flows.domain.DomainFlowUtils.InvalidPunycodeException;
import google.registry.flows.domain.DomainFlowUtils.LeadingDashException;
import google.registry.flows.domain.DomainFlowUtils.MissingBillingAccountMapException;
import google.registry.flows.domain.DomainFlowUtils.TldDoesNotExistException;
import google.registry.flows.domain.DomainFlowUtils.TrailingDashException;
import google.registry.model.domain.Domain;
import google.registry.model.tld.Registry.TldType;
import google.registry.testing.AppEngineExtension;
import org.joda.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DomainFlowUtils}. */
class DomainFlowUtilsTest extends ResourceFlowTestCase<DomainInfoFlow, Domain> {

  @BeforeEach
  void setup() {
    setEppInput("domain_info.xml");
    createTld("tld");
    persistResource(AppEngineExtension.makeRegistrar1().asBuilder().build());
  }

  @Test
  void testValidateDomainNameAcceptsValidName() throws EppException {
    assertThat(DomainFlowUtils.validateDomainName("example.tld")).isNotNull();
  }

  @Test
  void testValidateDomainName_IllegalCharacters() {
    BadDomainNameCharacterException thrown =
        assertThrows(
            BadDomainNameCharacterException.class,
            () -> DomainFlowUtils.validateDomainName("$.foo"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Domain names can only contain a-z, 0-9, '.' and '-'");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testValidateDomainName_DomainNameWithEmptyParts() {
    EmptyDomainNamePartException thrown =
        assertThrows(
            EmptyDomainNamePartException.class,
            () -> DomainFlowUtils.validateDomainName("example."));
    assertThat(thrown).hasMessageThat().isEqualTo("No part of a domain name can be empty");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testValidateDomainName_DomainNameWithLessThanTwoParts() {
    BadDomainNamePartsCountException thrown =
        assertThrows(
            BadDomainNamePartsCountException.class,
            () -> DomainFlowUtils.validateDomainName("example"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Domain name must have exactly one part above the TLD");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testValidateDomainName_invalidTLD() {
    TldDoesNotExistException thrown =
        assertThrows(
            TldDoesNotExistException.class,
            () -> DomainFlowUtils.validateDomainName("example.nosuchtld"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Domain name is under tld nosuchtld which doesn't exist");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testValidateDomainName_DomainNameIsTooLong() {
    DomainLabelTooLongException thrown =
        assertThrows(
            DomainLabelTooLongException.class,
            () ->
                DomainFlowUtils.validateDomainName(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.foo"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Domain labels cannot be longer than 63 characters");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testValidateDomainName_leadingDash() {
    LeadingDashException thrown =
        assertThrows(
            LeadingDashException.class, () -> DomainFlowUtils.validateDomainName("-example.foo"));
    assertThat(thrown).hasMessageThat().isEqualTo("Domain labels cannot begin with a dash");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testValidateDomainName_trailingDash() {
    TrailingDashException thrown =
        assertThrows(
            TrailingDashException.class, () -> DomainFlowUtils.validateDomainName("example-.foo"));
    assertThat(thrown).hasMessageThat().isEqualTo("Domain labels cannot end with a dash");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testValidateDomainName_invalidIDN() {
    InvalidPunycodeException thrown =
        assertThrows(
            InvalidPunycodeException.class,
            () -> DomainFlowUtils.validateDomainName("xn--abcd.foo"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Domain name starts with xn-- but is not a valid IDN");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testValidateDomainName_containsInvalidDashes() {
    DashesInThirdAndFourthException thrown =
        assertThrows(
            DashesInThirdAndFourthException.class,
            () -> DomainFlowUtils.validateDomainName("ab--cd.foo"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Non-IDN domain names cannot contain dashes in the third or fourth position");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testCheckHasBillingAccount_ignoresTestTlds() throws EppException {
    persistFoobarTld(TldType.TEST);
    checkHasBillingAccount("TheRegistrar", "foobar");
  }

  @Test
  void testCheckHasBillingAccount_failsOnRealTld() throws EppException {
    persistFoobarTld(TldType.REAL);
    MissingBillingAccountMapException thrown =
        assertThrows(
            MissingBillingAccountMapException.class,
            () -> checkHasBillingAccount("TheRegistrar", "foobar"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Registrar is not fully onboarded for TLDs that bill in CHF");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  private void persistFoobarTld(TldType tldType) {
    persistResource(
        newRegistry("foobar", "FOOBAR")
            .asBuilder()
            .setTldType(tldType)
            .setCurrency(CHF)
            .setCreateBillingCost(Money.ofMajor(CHF, 800))
            .setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.ofMajor(CHF, 800)))
            .setRenewBillingCostTransitions(
                ImmutableSortedMap.of(START_OF_TIME, Money.ofMajor(CHF, 800)))
            .setRegistryLockOrUnlockBillingCost(Money.ofMajor(CHF, 800))
            .setServerStatusChangeBillingCost(Money.ofMajor(CHF, 800))
            .setRestoreBillingCost(Money.ofMajor(CHF, 800))
            .build());
  }
}
