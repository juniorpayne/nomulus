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

package google.registry.persistence.converter;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatabaseHelper.insertInDb;

import com.google.common.collect.ImmutableMap;
import google.registry.model.ImmutableObject;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import java.util.Map;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.joda.money.CurrencyUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link CurrencyToBillingConverter}. */
public class CurrencyToBillingConverterTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestExtensions.Builder().withEntityClass(TestEntity.class).buildUnitTestExtension();

  @Test
  void roundTripConversion_returnsSameCurrencyToBillingMap() {
    ImmutableMap<CurrencyUnit, String> currencyToBilling =
        ImmutableMap.of(
            CurrencyUnit.of("USD"), "accountId1",
            CurrencyUnit.of("CNY"), "accountId2");
    TestEntity testEntity = new TestEntity(currencyToBilling);
    insertInDb(testEntity);
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.currencyToBilling).containsExactlyEntriesIn(currencyToBilling);
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  private static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    Map<CurrencyUnit, String> currencyToBilling;

    private TestEntity() {}

    private TestEntity(Map<CurrencyUnit, String> currencyToBilling) {
      this.currencyToBilling = currencyToBilling;
    }
  }
}
