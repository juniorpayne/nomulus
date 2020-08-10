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

package google.registry.webdriver;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Base class for tests that needs a {@link WebDriverPlusScreenDifferExtension}. */
@Timeout(120)
class WebDriverTestCase {

  @RegisterExtension
  static final DockerWebDriverExtension webDriverProvider = new DockerWebDriverExtension();

  @RegisterExtension
  final WebDriverPlusScreenDifferExtension driver =
      new WebDriverPlusScreenDifferExtension(webDriverProvider::getWebDriver);
}
