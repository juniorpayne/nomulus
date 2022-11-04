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

package google.registry.persistence;

import dagger.Component;
import google.registry.config.CredentialModule;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.keyring.secretmanager.SecretManagerKeyringModule;
import google.registry.persistence.PersistenceModule.AppEngineJpaTm;
import google.registry.persistence.PersistenceModule.ReadOnlyReplicaJpaTm;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.privileges.secretmanager.SecretManagerModule;
import google.registry.util.UtilsModule;
import javax.inject.Singleton;
import javax.persistence.EntityManagerFactory;

/** Dagger component to provide {@link EntityManagerFactory} instances. */
@Singleton
@Component(
    modules = {
      ConfigModule.class,
      CredentialModule.class,
      PersistenceModule.class,
      SecretManagerKeyringModule.class,
      SecretManagerModule.class,
      UtilsModule.class
    })
public interface PersistenceComponent {

  @AppEngineJpaTm
  JpaTransactionManager appEngineJpaTransactionManager();

  @ReadOnlyReplicaJpaTm
  JpaTransactionManager readOnlyReplicaJpaTransactionManager();
}
