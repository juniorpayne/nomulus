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

package google.registry.webdriver;

import static google.registry.server.Fixture.BASIC;
import static google.registry.server.Route.route;

import google.registry.module.frontend.FrontendServlet;
import google.registry.server.RegistryTestServer;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junitpioneer.jupiter.RetryingTest;
import org.openqa.selenium.By;

/** Registrar Console Screenshot Differ tests. */
public class OteSetupConsoleScreenshotTest extends WebDriverTestCase {

  @RegisterExtension
  final TestServerExtension server =
      new TestServerExtension.Builder()
          .setRunfiles(RegistryTestServer.RUNFILES)
          .setRoutes(route("/registrar-ote-setup", FrontendServlet.class))
          .setFixtures(BASIC)
          .setEmail("Marla.Singer@google.com")
          .build();

  @RetryingTest(3)
  void get_owner_fails() throws Throwable {
    driver.get(server.getUrl("/registrar-ote-setup"));
    driver.waitForDisplayedElement(By.tagName("h1"));
    driver.diffPage("unauthorized");
  }

  @RetryingTest(3)
  void get_admin_succeeds() throws Throwable {
    server.setIsAdmin(true);
    driver.get(server.getUrl("/registrar-ote-setup"));
    driver.waitForDisplayedElement(By.tagName("h1"));
    driver.diffPage("formEmpty");
    driver.findElement(By.id("clientId")).sendKeys("acmereg");
    driver.findElement(By.id("email")).sendKeys("acmereg@registry.example");
    driver.findElement(By.id("password")).sendKeys("StRoNgPaSsWoRd");
    driver.diffPage("formFilled");
    driver.findElement(By.id("submit-button")).click();
    driver.waitForDisplayedElement(By.tagName("h1"));
    driver.diffPage("oteResult");
  }
}
