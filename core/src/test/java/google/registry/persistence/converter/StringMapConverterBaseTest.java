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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import google.registry.model.ImmutableObject;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import java.util.Map;
import javax.persistence.Converter;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.NoResultException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link StringMapConverterBase}. */
public class StringMapConverterBaseTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestExtensions.Builder()
          .withEntityClass(TestStringMapConverter.class, TestEntity.class)
          .buildUnitTestExtension();

  private static final ImmutableMap<Key, Value> MAP =
      ImmutableMap.of(
          new Key("key1"), new Value("value1"),
          new Key("key2"), new Value("value2"),
          new Key("key3"), new Value("value3"));

  @Test
  void roundTripConversion_returnsSameMap() {
    TestEntity testEntity = new TestEntity(MAP);
    insertInDb(testEntity);
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.map).containsExactlyEntriesIn(MAP);
  }

  @Test
  void testUpdateColumn_succeeds() {
    TestEntity testEntity = new TestEntity(MAP);
    insertInDb(testEntity);
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.map).containsExactlyEntriesIn(MAP);
    persisted.map = ImmutableMap.of(new Key("key4"), new Value("value4"));
    jpaTm().transact(() -> jpaTm().getEntityManager().merge(persisted));
    TestEntity updated =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(updated.map).containsExactly(new Key("key4"), new Value("value4"));
  }

  @Test
  void testNullValue_writesAndReadsNullSuccessfully() {
    TestEntity testEntity = new TestEntity(null);
    insertInDb(testEntity);
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.map).isNull();
  }

  @Test
  void testEmptyMap_writesAndReadsEmptyCollectionSuccessfully() {
    TestEntity testEntity = new TestEntity(ImmutableMap.of());
    insertInDb(testEntity);
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.map).isEmpty();
  }

  @Test
  void testNativeQuery_succeeds() {
    executeNativeQuery(
        "INSERT INTO \"TestEntity\" (name, map) VALUES ('id', 'key1=>value1, key2=>value2')");

    assertThat(
            getSingleResultFromNativeQuery(
                "SELECT map -> 'key1' FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo("value1");
    assertThat(
            getSingleResultFromNativeQuery(
                "SELECT map -> 'key2' FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo("value2");

    executeNativeQuery("UPDATE \"TestEntity\" SET map = 'key3=>value3' WHERE name = 'id'");

    assertThat(
            getSingleResultFromNativeQuery(
                "SELECT map -> 'key3' FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo("value3");

    executeNativeQuery("DELETE FROM \"TestEntity\" WHERE name = 'id'");
    assertThrows(
        NoResultException.class,
        () ->
            getSingleResultFromNativeQuery(
                "SELECT map -> 'key3' FROM \"TestEntity\" WHERE name = 'id'"));
  }

  private static Object getSingleResultFromNativeQuery(String sql) {
    return jpaTm()
        .transact(() -> jpaTm().getEntityManager().createNativeQuery(sql).getSingleResult());
  }

  private static Object executeNativeQuery(String sql) {
    return jpaTm()
        .transact(() -> jpaTm().getEntityManager().createNativeQuery(sql).executeUpdate());
  }

  private static class Key extends ImmutableObject {
    private final String key;

    private Key(String key) {
      this.key = key;
    }
  }

  private static class Value extends ImmutableObject {
    private final String value;

    private Value(String value) {
      this.value = value;
    }
  }

  @Converter(autoApply = true)
  private static class TestStringMapConverter
      extends StringMapConverterBase<Key, Value, Map<Key, Value>> {

    @Override
    protected String convertKeyToString(Key key) {
      return key.key;
    }

    @Override
    protected String convertValueToString(Value value) {
      return value.value;
    }

    @Override
    protected Key convertStringToKey(String string) {
      return new Key(string);
    }

    @Override
    protected Value convertStringToValue(String string) {
      return new Value(string);
    }

    @Override
    protected Map<Key, Value> convertMapToDerivedType(Map<Key, Value> map) {
      return map;
    }
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  private static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    Map<Key, Value> map;

    private TestEntity() {}

    private TestEntity(Map<Key, Value> map) {
      this.map = map;
    }
  }
}
