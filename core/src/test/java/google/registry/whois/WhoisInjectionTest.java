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

package google.registry.whois;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import google.registry.request.RequestModule;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FullFieldsTestEntityHelper;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for Dagger injection of the whois package. */
final class WhoisInjectionTest {

  @RegisterExtension
  final AppEngineExtension appEngine = AppEngineExtension.builder().withCloudSql().build();

  private final HttpServletRequest req = mock(HttpServletRequest.class);
  private final HttpServletResponse rsp = mock(HttpServletResponse.class);
  private final StringWriter httpOutput = new StringWriter();

  @BeforeEach
  void beforeEach() throws Exception {
    when(rsp.getWriter()).thenReturn(new PrintWriter(httpOutput));
  }

  @Test
  void testWhoisAction_injectsAndWorks() throws Exception {
    createTld("lol");
    persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.lol", "1.2.3.4"));
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("ns1.cat.lol\r\n")));
    DaggerWhoisTestComponent.builder()
        .requestModule(new RequestModule(req, rsp))
        .build()
        .whoisAction()
        .run();
    verify(rsp).setStatus(200);
    assertThat(httpOutput.toString()).contains("ns1.cat.lol");
  }

  @Test
  void testWhoisHttpAction_injectsAndWorks() {
    createTld("lol");
    persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.lol", "1.2.3.4"));
    when(req.getRequestURI()).thenReturn("/whois/ns1.cat.lol");
    DaggerWhoisTestComponent.builder()
        .requestModule(new RequestModule(req, rsp))
        .build()
        .whoisHttpAction()
        .run();
    verify(rsp).setStatus(200);
    assertThat(httpOutput.toString()).contains("ns1.cat.lol");
  }
}
