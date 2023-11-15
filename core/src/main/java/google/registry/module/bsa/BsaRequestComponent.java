// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.module.bsa;

import dagger.Module;
import dagger.Subcomponent;
import google.registry.bsa.PlaceholderAction;
import google.registry.request.RequestComponentBuilder;
import google.registry.request.RequestModule;
import google.registry.request.RequestScope;

@RequestScope
@Subcomponent(
    modules = {
      RequestModule.class,
    })
interface BsaRequestComponent {

  PlaceholderAction bsaAction();

  @Subcomponent.Builder
  abstract class Builder implements RequestComponentBuilder<BsaRequestComponent> {

    @Override
    public abstract Builder requestModule(RequestModule requestModule);

    @Override
    public abstract BsaRequestComponent build();
  }

  @Module(subcomponents = BsaRequestComponent.class)
  class BsaRequestComponentModule {}
}
