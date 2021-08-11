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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadPremiumEntries;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.tld.Registry;
import google.registry.model.tld.label.PremiumList;
import google.registry.model.tld.label.PremiumListDao;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DeletePremiumListCommand}. */
class DeletePremiumListCommandTest extends CommandTestCase<DeletePremiumListCommand> {

  @Test
  void testSuccess() throws Exception {
    PremiumList premiumList = persistPremiumList("xn--q9jyb4c", USD, "blah,USD 100");
    assertThat(loadPremiumEntries(premiumList)).hasSize(1);
    runCommand("--force", "--name=xn--q9jyb4c");
    assertThat(PremiumListDao.getLatestRevision("xn--q9jyb4c")).isEmpty();
  }

  @Test
  void testFailure_whenPremiumListDoesNotExist() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--name=foo"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Cannot delete the premium list foo because it doesn't exist.");
  }

  @Test
  void testFailure_whenPremiumListIsInUse() {
    PremiumList premiumList = persistPremiumList("xn--q9jyb4c", USD, "blah,USD 100");
    createTld("xn--q9jyb4c");
    persistResource(Registry.get("xn--q9jyb4c").asBuilder().setPremiumList(premiumList).build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--name=" + premiumList.getName()));
    assertThat(PremiumListDao.getLatestRevision(premiumList.getName())).isPresent();
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Cannot delete premium list because it is used on these tld(s): xn--q9jyb4c");
  }
}
