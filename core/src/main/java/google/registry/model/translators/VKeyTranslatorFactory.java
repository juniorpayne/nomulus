// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.translators;

import static com.google.common.base.Functions.identity;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static google.registry.model.EntityClasses.ALL_CLASSES;

import com.google.appengine.api.datastore.Key;
import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.annotation.EntitySubclass;
import google.registry.persistence.VKey;

/**
 * Translator factory for VKey.
 *
 * <p>These get translated to a string containing the URL safe encoding of the objectify key
 * followed by a (url-unsafe) ampersand delimiter and the SQL key.
 */
public class VKeyTranslatorFactory extends AbstractSimpleTranslatorFactory<VKey, Key> {

  // Class registry allowing us to restore the original class object from the unqualified class
  // name, which is all the datastore key gives us.
  // Note that entities annotated with @EntitySubclass are removed because they share the same
  // kind of the key with their parent class.
  private static final ImmutableMap<String, Class> CLASS_REGISTRY =
      ALL_CLASSES.stream()
          .filter(clazz -> !clazz.isAnnotationPresent(EntitySubclass.class))
          .collect(toImmutableMap(com.googlecode.objectify.Key::getKind, identity()));
  ;

  public VKeyTranslatorFactory() {
    super(VKey.class);
  }

  @Override
  public SimpleTranslator<VKey, Key> createTranslator() {
    return new SimpleTranslator<VKey, Key>() {
      @Override
      public VKey loadValue(Key datastoreValue) {
        // TODO(mmuller): we need to call a method on refClass to also reconstitute the SQL key.
        return datastoreValue == null
            ? null
            : VKey.createOfy(
                CLASS_REGISTRY.get(datastoreValue.getKind()),
                com.googlecode.objectify.Key.create(datastoreValue));
      }

      @Override
      public Key saveValue(VKey key) {
        return key == null ? null : key.getOfyKey().getRaw();
      }
    };
  }
}
