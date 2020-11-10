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

package google.registry.model.server;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import google.registry.model.EntityTestCase;
import google.registry.model.ofy.RequestCapturingAsyncDatastoreService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ServerSecret}. */
public class ServerSecretTest extends EntityTestCase {

  ServerSecretTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @BeforeEach
  void beforeEach() {
    ServerSecret.resetCache();
  }

  @Test
  void testGet_bootstrapping_savesSecretToDatastore() {
    ServerSecret secret = ServerSecret.get();
    assertThat(secret).isNotNull();
    assertThat(ofy().load().entity(new ServerSecret()).now()).isEqualTo(secret);
    assertThat(loadFromSql()).isEqualTo(secret);
  }

  @Test
  void testGet_existingSecret_returned() {
    ServerSecret secret = ServerSecret.create(new UUID(123, 456));
    ofy().saveWithoutBackup().entity(secret).now();
    assertThat(ServerSecret.get()).isEqualTo(secret);
    assertThat(ofy().load().entity(new ServerSecret()).now()).isEqualTo(secret);
    assertThat(loadFromSql()).isEqualTo(secret);
  }

  @Test
  void testGet_cachedSecret() {
    int numInitialReads = RequestCapturingAsyncDatastoreService.getReads().size();
    ServerSecret secret = ServerSecret.get();
    int numReads = RequestCapturingAsyncDatastoreService.getReads().size();
    assertThat(numReads).isGreaterThan(numInitialReads);
    assertThat(ServerSecret.get()).isEqualTo(secret);
    assertThat(RequestCapturingAsyncDatastoreService.getReads()).hasSize(numReads);
  }

  @Test
  void testAsBytes() {
    byte[] bytes = ServerSecret.create(new UUID(123, 0x456)).asBytes();
    assertThat(bytes).isEqualTo(new byte[] {0, 0, 0, 0, 0, 0, 0, 123, 0, 0, 0, 0, 0, 0, 0x4, 0x56});
  }

  private static ServerSecret loadFromSql() {
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                    .getEntityManager()
                    .createQuery("FROM ServerSecret", ServerSecret.class)
                    .setMaxResults(1)
                    .getResultStream()
                    .findFirst()
                    .get());
  }
}
