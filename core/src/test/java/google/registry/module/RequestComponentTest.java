// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.module;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.module.backend.BackendRequestComponent;
import google.registry.module.bsa.BsaRequestComponent;
import google.registry.module.frontend.FrontendRequestComponent;
import google.registry.module.pubapi.PubApiRequestComponent;
import google.registry.module.tools.ToolsRequestComponent;
import google.registry.testing.GoldenFileTestHelper;
import google.registry.testing.TestDataHelper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RequestComponent}. */
public class RequestComponentTest {
  private static final ImmutableMap<Class<?>, String> GaeComponents =
      ImmutableMap.of(
          FrontendRequestComponent.class, "frontend",
          BackendRequestComponent.class, "backend",
          ToolsRequestComponent.class, "tools",
          PubApiRequestComponent.class, "pubapi",
          BsaRequestComponent.class, "bsa");

  @Test
  void testRoutingMap() {
    GoldenFileTestHelper.assertThatRoutesFromComponent(RequestComponent.class)
        .describedAs("routing map")
        .isEqualToGolden(RequestComponentTest.class, "routing.txt");
  }

  @Test
  void testGaeToJettyRoutingCoverage() {
    List<Route> jettyRoutes = getRoutes(RequestComponent.class, "routing.txt");
    List<Route> gaeRoutes = new ArrayList<>();
    for (var component : GaeComponents.entrySet()) {
      gaeRoutes.addAll(getRoutes(component.getKey(), component.getValue() + "_routing.txt"));
    }
    assertThat(jettyRoutes).containsExactlyElementsIn(gaeRoutes);
  }

  private List<Route> getRoutes(Class<?> context, String filename) {
    return TestDataHelper.loadFile(context, filename)
        .trim()
        .lines()
        .skip(1) // Skip the headers
        .map(Route::create)
        .toList();
  }

  private record Route(
      String path, String clazz, String methods, String ok, String min, String userPolicy) {
    private static final Splitter splitter = Splitter.on(' ').omitEmptyStrings().trimResults();

    static Route create(String line) {
      ImmutableList<String> parts = ImmutableList.copyOf(splitter.split(line));
      assertThat(parts.size()).isEqualTo(6);
      return new Route(
          parts.get(0), parts.get(1), parts.get(2), parts.get(3), parts.get(4), parts.get(5));
    }
  }
}
