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

package google.registry.persistence.converter;

import google.registry.model.domain.fee.FeeQueryCommandExtensionItem;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import javax.persistence.Converter;

@Converter(autoApply = true)
public class CommandNameSetConverter
    extends StringSetConverterBase<FeeQueryCommandExtensionItem.CommandName> {

  @Override
  String toString(CommandName element) {
    return element.name();
  }

  @Override
  CommandName fromString(String value) {
    return FeeQueryCommandExtensionItem.CommandName.valueOf(value);
  }
}
