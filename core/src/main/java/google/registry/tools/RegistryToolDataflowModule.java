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

import com.google.api.services.dataflow.Dataflow;
import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule.LocalCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.GoogleCredentialsBundle;

/** Provides a {@link Dataflow} API client for use in {@link RegistryTool}. */
@Module
public class RegistryToolDataflowModule {

  @Provides
  static Dataflow provideDataflow(
      @LocalCredential GoogleCredentialsBundle credentialsBundle,
      @Config("projectId") String projectId) {
    return new Dataflow.Builder(
            credentialsBundle.getHttpTransport(),
            credentialsBundle.getJsonFactory(),
            credentialsBundle.getHttpRequestInitializer())
        .setApplicationName(String.format("%s nomulus", projectId))
        .build();
  }
}
