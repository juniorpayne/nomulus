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

package google.registry.batch;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.batch.BatchModule.PARAM_FAST;
import static google.registry.batch.ResaveAllEppResourcesPipelineAction.PIPELINE_NAME;
import static google.registry.beam.BeamUtils.createJobName;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.verify;

import com.google.api.services.dataflow.model.LaunchFlexTemplateParameter;
import com.google.api.services.dataflow.model.LaunchFlexTemplateRequest;
import com.google.common.collect.ImmutableMap;
import google.registry.beam.BeamActionTestBase;
import google.registry.config.RegistryEnvironment;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ResaveAllEppResourcesPipelineAction}. */
public class ResaveAllEppResourcesPipelineActionTest extends BeamActionTestBase {

  @RegisterExtension
  final AppEngineExtension appEngine = AppEngineExtension.builder().withCloudSql().build();

  private final FakeClock fakeClock = new FakeClock();

  private ResaveAllEppResourcesPipelineAction createAction(boolean isFast) {
    return new ResaveAllEppResourcesPipelineAction(
        "test-project", "test-region", "staging-bucket", isFast, fakeClock, response, dataflow);
  }

  @Test
  void testLaunch_notFast() throws Exception {
    createAction(false).run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).isEqualTo("Launched resaveAllEppResources pipeline: jobid");
    verify(templates).launch("test-project", "test-region", createLaunchTemplateRequest(false));
  }

  @Test
  void testLaunch_fast() throws Exception {
    createAction(true).run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).isEqualTo("Launched resaveAllEppResources pipeline: jobid");
    verify(templates).launch("test-project", "test-region", createLaunchTemplateRequest(true));
  }

  private LaunchFlexTemplateRequest createLaunchTemplateRequest(boolean isFast) {
    return new LaunchFlexTemplateRequest()
        .setLaunchParameter(
            new LaunchFlexTemplateParameter()
                .setJobName(createJobName("resave-all-epp-resources", fakeClock))
                .setContainerSpecGcsPath(
                    String.format("%s/%s_metadata.json", "staging-bucket", PIPELINE_NAME))
                .setParameters(
                    new ImmutableMap.Builder<String, String>()
                        .put(PARAM_FAST, Boolean.toString(isFast))
                        .put("registryEnvironment", RegistryEnvironment.get().name())
                        .build()));
  }
}
