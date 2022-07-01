// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.testing.DatabaseHelper.createTld;
import static org.junit.Assert.assertThrows;

import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class GetPremiumListCommandTest extends CommandTestCase<GetPremiumListCommand> {

  private static final String BASE_LIST_CONTENTS =
      "tld:\n"
          + "aluminum,USD 11.00\n"
          + "brass,USD 20.00\n"
          + "copper,USD 15.00\n"
          + "diamond,USD 1000000.00\n"
          + "gold,USD 24317.00\n"
          + "iridium,USD 13117.00\n"
          + "palladium,USD 877.00\n"
          + "platinum,USD 87741.00\n"
          + "rhodium,USD 88415.00\n"
          + "rich,USD 100.00\n"
          + "richer,USD 1000.00\n"
          + "silver,USD 588.00\n";

  @BeforeEach
  void beforeEach() {
    createTld("tld");
  }

  @Test
  void testSuccess_list() throws Exception {
    runCommand("tld");
    assertStdoutIs(BASE_LIST_CONTENTS);
  }

  @Test
  void testSuccess_onlyOneExists() throws Exception {
    runCommand("tld", "nonexistent");
    assertStdoutIs(BASE_LIST_CONTENTS + "No list found with name nonexistent.\n");
  }

  @Test
  void testFailure_nonexistent() throws Exception {
    runCommand("nonexistent", "othernonexistent");
    assertStdoutIs(
        "No list found with name nonexistent.\nNo list found with name othernonexistent.\n");
  }

  @Test
  void testFailure_noArgs() {
    assertThrows(ParameterException.class, this::runCommand);
  }
}
