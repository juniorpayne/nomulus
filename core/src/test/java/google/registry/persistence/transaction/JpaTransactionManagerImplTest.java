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

package google.registry.persistence.transaction;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.assertDetachedFromEntityManager;
import static google.registry.testing.DatabaseHelper.existsInDb;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.TestDataHelper.fileClassPath;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.model.ImmutableObject;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import javax.persistence.Entity;
import javax.persistence.EntityManager;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.OptimisticLockException;
import javax.persistence.RollbackException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Unit tests for SQL only APIs defined in {@link JpaTransactionManagerImpl}. Note that the tests
 * for common APIs in {@link TransactionManager} are added in {@link TransactionManagerTest}.
 *
 * <p>TODO(b/177587763): Remove duplicate tests that covered by TransactionManagerTest by
 * refactoring the test schema.
 */
class JpaTransactionManagerImplTest {

  private final FakeClock fakeClock = new FakeClock();
  private final TestEntity theEntity = new TestEntity("theEntity", "foo");
  private final VKey<TestEntity> theEntityKey = VKey.create(TestEntity.class, "theEntity");
  private final TestCompoundIdEntity compoundIdEntity =
      new TestCompoundIdEntity("compoundIdEntity", 10, "foo");
  private final VKey<TestCompoundIdEntity> compoundIdEntityKey =
      VKey.create(TestCompoundIdEntity.class, new CompoundId("compoundIdEntity", 10));
  private final ImmutableList<TestEntity> moreEntities =
      ImmutableList.of(
          new TestEntity("entity1", "foo"),
          new TestEntity("entity2", "bar"),
          new TestEntity("entity3", "qux"));

  @RegisterExtension
  final JpaUnitTestExtension jpaExtension =
      new JpaTestExtensions.Builder()
          .withInitScript(fileClassPath(getClass(), "test_schema.sql"))
          .withClock(fakeClock)
          .withEntityClass(
              TestEntity.class, TestCompoundIdEntity.class, TestNamedCompoundIdEntity.class)
          .buildUnitTestExtension();

  @Test
  void transact_succeeds() {
    assertPersonEmpty();
    assertCompanyEmpty();
    tm().transact(
            () -> {
              insertPerson(10);
              insertCompany("Foo");
              insertCompany("Bar");
            });
    assertPersonCount(1);
    assertPersonExist(10);
    assertCompanyCount(2);
    assertCompanyExist("Foo");
    assertCompanyExist("Bar");
  }

  @Test
  void transact_hasNoEffectWithPartialSuccess() {
    assertPersonEmpty();
    assertCompanyEmpty();
    assertThrows(
        RuntimeException.class,
        () ->
            tm().transact(
                    () -> {
                      insertPerson(10);
                      insertCompany("Foo");
                      throw new RuntimeException();
                    }));
    assertPersonEmpty();
    assertCompanyEmpty();
  }

  @Test
  void transact_reusesExistingTransaction() {
    assertPersonEmpty();
    assertCompanyEmpty();
    tm().transact(
            () ->
                tm().transact(
                        () -> {
                          insertPerson(10);
                          insertCompany("Foo");
                          insertCompany("Bar");
                        }));
    assertPersonCount(1);
    assertPersonExist(10);
    assertCompanyCount(2);
    assertCompanyExist("Foo");
    assertCompanyExist("Bar");
  }

  @Test
  void insert_succeeds() {
    assertThat(existsInDb(theEntity)).isFalse();
    tm().transact(() -> tm().insert(theEntity));
    assertThat(existsInDb(theEntity)).isTrue();
    assertThat(loadByKey(theEntityKey)).isEqualTo(theEntity);
  }

  @Test
  void transact_retriesOptimisticLockExceptions() {
    JpaTransactionManager spyJpaTm = spy(tm());
    doThrow(OptimisticLockException.class).when(spyJpaTm).delete(any(VKey.class));
    spyJpaTm.transact(() -> spyJpaTm.insert(theEntity));
    assertThrows(
        OptimisticLockException.class,
        () -> spyJpaTm.transact(() -> spyJpaTm.delete(theEntityKey)));
    verify(spyJpaTm, times(3)).delete(theEntityKey);
    Supplier<Runnable> supplier =
        () -> {
          Runnable work = () -> spyJpaTm.delete(theEntityKey);
          work.run();
          return null;
        };
    assertThrows(OptimisticLockException.class, () -> spyJpaTm.transact(supplier));
    verify(spyJpaTm, times(6)).delete(theEntityKey);
  }

  @Test
  void transactNoRetry_doesNotRetryOptimisticLockException() {
    JpaTransactionManager spyJpaTm = spy(tm());
    doThrow(OptimisticLockException.class).when(spyJpaTm).delete(any(VKey.class));
    spyJpaTm.transactNoRetry(() -> spyJpaTm.insert(theEntity));
    assertThrows(
        OptimisticLockException.class,
        () -> spyJpaTm.transactNoRetry(() -> spyJpaTm.delete(theEntityKey)));
    verify(spyJpaTm, times(1)).delete(theEntityKey);
    Supplier<Runnable> supplier =
        () -> {
          Runnable work = () -> spyJpaTm.delete(theEntityKey);
          work.run();
          return null;
        };
    assertThrows(OptimisticLockException.class, () -> spyJpaTm.transactNoRetry(supplier));
    verify(spyJpaTm, times(2)).delete(theEntityKey);
  }

  @Test
  void transact_retriesNestedOptimisticLockExceptions() {
    JpaTransactionManager spyJpaTm = spy(tm());
    doThrow(new RuntimeException(new OptimisticLockException()))
        .when(spyJpaTm)
        .delete(any(VKey.class));
    spyJpaTm.transact(() -> spyJpaTm.insert(theEntity));
    assertThrows(
        RuntimeException.class, () -> spyJpaTm.transact(() -> spyJpaTm.delete(theEntityKey)));
    verify(spyJpaTm, times(3)).delete(theEntityKey);
    Supplier<Runnable> supplier =
        () -> {
          Runnable work = () -> spyJpaTm.delete(theEntityKey);
          work.run();
          return null;
        };
    assertThrows(RuntimeException.class, () -> spyJpaTm.transact(supplier));
    verify(spyJpaTm, times(6)).delete(theEntityKey);
  }

  @Test
  void insert_throwsExceptionIfEntityExists() {
    assertThat(existsInDb(theEntity)).isFalse();
    tm().transact(() -> tm().insert(theEntity));
    assertThat(existsInDb(theEntity)).isTrue();
    assertThat(loadByKey(theEntityKey)).isEqualTo(theEntity);
    assertThrows(RollbackException.class, () -> tm().transact(() -> tm().insert(theEntity)));
  }

  @Test
  void createCompoundIdEntity_succeeds() {
    assertThat(tm().transact(() -> tm().exists(compoundIdEntity))).isFalse();
    tm().transact(() -> tm().insert(compoundIdEntity));
    assertThat(tm().transact(() -> tm().exists(compoundIdEntity))).isTrue();
    assertThat(tm().transact(() -> tm().loadByKey(compoundIdEntityKey)))
        .isEqualTo(compoundIdEntity);
  }

  @Test
  void createNamedCompoundIdEntity_succeeds() {
    // Compound IDs should also work even if the field names don't match up exactly
    TestNamedCompoundIdEntity entity = new TestNamedCompoundIdEntity("foo", 1);
    tm().transact(() -> tm().insert(entity));
    assertThat(existsInDb(entity)).isTrue();
    assertThat(
            loadByKey(VKey.create(TestNamedCompoundIdEntity.class, new NamedCompoundId("foo", 1))))
        .isEqualTo(entity);
  }

  @Test
  void saveAllNew_succeeds() {
    moreEntities.forEach(entity -> assertThat(tm().transact(() -> tm().exists(entity))).isFalse());
    insertInDb(moreEntities);
    moreEntities.forEach(entity -> assertThat(tm().transact(() -> tm().exists(entity))).isTrue());
    assertThat(tm().transact(() -> tm().loadAllOf(TestEntity.class)))
        .containsExactlyElementsIn(moreEntities);
  }

  @Test
  void saveAllNew_rollsBackWhenFailure() {
    moreEntities.forEach(entity -> assertThat(tm().transact(() -> tm().exists(entity))).isFalse());
    insertInDb(moreEntities.get(0));
    assertThrows(RollbackException.class, () -> insertInDb(moreEntities));
    assertThat(tm().transact(() -> tm().exists(moreEntities.get(0)))).isTrue();
    assertThat(tm().transact(() -> tm().exists(moreEntities.get(1)))).isFalse();
    assertThat(tm().transact(() -> tm().exists(moreEntities.get(2)))).isFalse();
  }

  @Test
  void put_persistsNewEntity() {
    assertThat(tm().transact(() -> tm().exists(theEntity))).isFalse();
    tm().transact(() -> tm().put(theEntity));
    assertThat(tm().transact(() -> tm().exists(theEntity))).isTrue();
    assertThat(tm().transact(() -> tm().loadByKey(theEntityKey))).isEqualTo(theEntity);
  }

  @Test
  void put_updatesExistingEntity() {
    insertInDb(theEntity);
    TestEntity persisted = tm().transact(() -> tm().loadByKey(theEntityKey));
    assertThat(persisted.data).isEqualTo("foo");
    theEntity.data = "bar";
    tm().transact(() -> tm().put(theEntity));
    persisted = tm().transact(() -> tm().loadByKey(theEntityKey));
    assertThat(persisted.data).isEqualTo("bar");
  }

  @Test
  void putAll_succeeds() {
    moreEntities.forEach(entity -> assertThat(tm().transact(() -> tm().exists(entity))).isFalse());
    tm().transact(() -> tm().putAll(moreEntities));
    moreEntities.forEach(entity -> assertThat(tm().transact(() -> tm().exists(entity))).isTrue());
    assertThat(tm().transact(() -> tm().loadAllOf(TestEntity.class)))
        .containsExactlyElementsIn(moreEntities);
  }

  @Test
  void update_succeeds() {
    insertInDb(theEntity);
    TestEntity persisted =
        tm().transact(() -> tm().loadByKey(VKey.create(TestEntity.class, "theEntity")));
    assertThat(persisted.data).isEqualTo("foo");
    theEntity.data = "bar";
    tm().transact(() -> tm().update(theEntity));
    persisted = tm().transact(() -> tm().loadByKey(theEntityKey));
    assertThat(persisted.data).isEqualTo("bar");
  }

  @Test
  void updateCompoundIdEntity_succeeds() {
    insertInDb(compoundIdEntity);
    TestCompoundIdEntity persisted = tm().transact(() -> tm().loadByKey(compoundIdEntityKey));
    assertThat(persisted.data).isEqualTo("foo");
    compoundIdEntity.data = "bar";
    tm().transact(() -> tm().update(compoundIdEntity));
    persisted = tm().transact(() -> tm().loadByKey(compoundIdEntityKey));
    assertThat(persisted.data).isEqualTo("bar");
  }

  @Test
  void update_throwsExceptionWhenEntityDoesNotExist() {
    assertThat(tm().transact(() -> tm().exists(theEntity))).isFalse();
    assertThrows(IllegalArgumentException.class, () -> tm().transact(() -> tm().update(theEntity)));
    assertThat(tm().transact(() -> tm().exists(theEntity))).isFalse();
  }

  @Test
  void updateAll_succeeds() {
    insertInDb(moreEntities);
    ImmutableList<TestEntity> updated =
        ImmutableList.of(
            new TestEntity("entity1", "foo_updated"),
            new TestEntity("entity2", "bar_updated"),
            new TestEntity("entity3", "qux_updated"));
    tm().transact(() -> tm().updateAll(updated));
    assertThat(tm().transact(() -> tm().loadAllOf(TestEntity.class)))
        .containsExactlyElementsIn(updated);
  }

  @Test
  void updateAll_rollsBackWhenFailure() {
    insertInDb(moreEntities);
    ImmutableList<TestEntity> updated =
        ImmutableList.of(
            new TestEntity("entity1", "foo_updated"),
            new TestEntity("entity2", "bar_updated"),
            new TestEntity("entity3", "qux_updated"),
            theEntity);
    assertThrows(
        IllegalArgumentException.class, () -> tm().transact(() -> tm().updateAll(updated)));
    assertThat(tm().transact(() -> tm().loadAllOf(TestEntity.class)))
        .containsExactlyElementsIn(moreEntities);
  }

  @Test
  void load_succeeds() {
    assertThat(tm().transact(() -> tm().exists(theEntity))).isFalse();
    insertInDb(theEntity);
    TestEntity persisted =
        tm().transact(() -> assertDetachedFromEntityManager(tm().loadByKey(theEntityKey)));
    assertThat(persisted.name).isEqualTo("theEntity");
    assertThat(persisted.data).isEqualTo("foo");
  }

  @Test
  void load_throwsOnMissingElement() {
    assertThat(tm().transact(() -> tm().exists(theEntity))).isFalse();
    assertThrows(
        NoSuchElementException.class, () -> tm().transact(() -> tm().loadByKey(theEntityKey)));
  }

  @Test
  void loadByEntity_succeeds() {
    insertInDb(theEntity);
    TestEntity persisted =
        tm().transact(() -> assertDetachedFromEntityManager(tm().loadByEntity(theEntity)));
    assertThat(persisted.name).isEqualTo("theEntity");
    assertThat(persisted.data).isEqualTo("foo");
  }

  @Test
  void maybeLoad_succeeds() {
    assertThat(tm().transact(() -> tm().exists(theEntity))).isFalse();
    insertInDb(theEntity);
    TestEntity persisted =
        tm().transact(
                () -> assertDetachedFromEntityManager(tm().loadByKeyIfPresent(theEntityKey).get()));
    assertThat(persisted.name).isEqualTo("theEntity");
    assertThat(persisted.data).isEqualTo("foo");
  }

  @Test
  void maybeLoad_nonExistentObject() {
    assertThat(tm().transact(() -> tm().exists(theEntity))).isFalse();
    assertThat(tm().transact(() -> tm().loadByKeyIfPresent(theEntityKey)).isPresent()).isFalse();
  }

  @Test
  void loadCompoundIdEntity_succeeds() {
    assertThat(tm().transact(() -> tm().exists(compoundIdEntity))).isFalse();
    insertInDb(compoundIdEntity);
    TestCompoundIdEntity persisted =
        tm().transact(() -> assertDetachedFromEntityManager(tm().loadByKey(compoundIdEntityKey)));
    assertThat(persisted.name).isEqualTo("compoundIdEntity");
    assertThat(persisted.age).isEqualTo(10);
    assertThat(persisted.data).isEqualTo("foo");
  }

  @Test
  void loadByKeysIfPresent() {
    insertInDb(theEntity);
    tm().transact(
            () -> {
              ImmutableMap<VKey<? extends TestEntity>, TestEntity> results =
                  tm().loadByKeysIfPresent(
                          ImmutableList.of(
                              theEntityKey, VKey.create(TestEntity.class, "does-not-exist")));

              assertThat(results).containsExactly(theEntityKey, theEntity);
              assertDetachedFromEntityManager(results.get(theEntityKey));
            });
  }

  @Test
  void loadByKeys_succeeds() {
    insertInDb(theEntity);
    tm().transact(
            () -> {
              ImmutableMap<VKey<? extends TestEntity>, TestEntity> results =
                  tm().loadByKeysIfPresent(ImmutableList.of(theEntityKey));
              assertThat(results).containsExactly(theEntityKey, theEntity);
              assertDetachedFromEntityManager(results.get(theEntityKey));
            });
  }

  @Test
  void loadByEntitiesIfPresent_succeeds() {
    insertInDb(theEntity);
    tm().transact(
            () -> {
              ImmutableList<TestEntity> results =
                  tm().loadByEntitiesIfPresent(
                          ImmutableList.of(theEntity, new TestEntity("does-not-exist", "bar")));
              assertThat(results).containsExactly(theEntity);
              assertDetachedFromEntityManager(results.get(0));
            });
  }

  @Test
  void loadByEntities_succeeds() {
    insertInDb(theEntity);
    tm().transact(
            () -> {
              ImmutableList<TestEntity> results = tm().loadByEntities(ImmutableList.of(theEntity));
              assertThat(results).containsExactly(theEntity);
              assertDetachedFromEntityManager(results.get(0));
            });
  }

  @Test
  void loadAll_succeeds() {
    insertInDb(moreEntities);
    ImmutableList<TestEntity> persisted =
        tm().transact(
                () ->
                    tm().loadAllOf(TestEntity.class).stream()
                        .map(DatabaseHelper::assertDetachedFromEntityManager)
                        .collect(toImmutableList()));
    assertThat(persisted).containsExactlyElementsIn(moreEntities);
  }

  @Test
  void loadSingleton_detaches() {
    insertInDb(theEntity);
    tm().transact(
            () ->
                assertThat(
                    tm().getEntityManager().contains(tm().loadSingleton(TestEntity.class).get())))
        .isFalse();
  }

  @Test
  void delete_succeeds() {
    insertInDb(theEntity);
    assertThat(tm().transact(() -> tm().exists(theEntity))).isTrue();
    tm().transact(() -> tm().delete(theEntityKey));
    assertThat(tm().transact(() -> tm().exists(theEntity))).isFalse();
  }

  @Test
  void delete_returnsZeroWhenNoEntity() {
    assertThat(tm().transact(() -> tm().exists(theEntity))).isFalse();
    tm().transact(() -> tm().delete(theEntityKey));
    assertThat(tm().transact(() -> tm().exists(theEntity))).isFalse();
  }

  @Test
  void deleteCompoundIdEntity_succeeds() {
    insertInDb(compoundIdEntity);
    assertThat(tm().transact(() -> tm().exists(compoundIdEntity))).isTrue();
    tm().transact(() -> tm().delete(compoundIdEntityKey));
    assertThat(tm().transact(() -> tm().exists(compoundIdEntity))).isFalse();
  }

  @Test
  void assertDelete_throwsExceptionWhenEntityNotDeleted() {
    assertThat(tm().transact(() -> tm().exists(theEntity))).isFalse();
    assertThrows(
        IllegalArgumentException.class, () -> tm().transact(() -> tm().assertDelete(theEntityKey)));
  }

  @Test
  void loadAfterInsert_fails() {
    assertThat(
            assertThrows(
                IllegalStateException.class,
                () ->
                    tm().transact(
                            () -> {
                              tm().insert(theEntity);
                              tm().loadByKey(theEntityKey);
                            })))
        .hasMessageThat()
        .contains("Inserted/updated object reloaded: ");
  }

  @Test
  void loadAfterUpdate_fails() {
    insertInDb(theEntity);
    assertThat(
            assertThrows(
                IllegalStateException.class,
                () ->
                    tm().transact(
                            () -> {
                              tm().update(theEntity);
                              tm().loadByKey(theEntityKey);
                            })))
        .hasMessageThat()
        .contains("Inserted/updated object reloaded: ");
  }

  @Test
  void cqQuery_detaches() {
    insertInDb(moreEntities);
    tm().transact(
            () ->
                assertThat(
                        tm().getEntityManager()
                            .contains(
                                tm().criteriaQuery(
                                        CriteriaQueryBuilder.create(TestEntity.class)
                                            .where(
                                                "name",
                                                tm().getEntityManager().getCriteriaBuilder()::equal,
                                                "entity1")
                                            .build())
                                    .getSingleResult()))
                    .isFalse());
  }

  @Test
  void loadAfterPut_fails() {
    assertThat(
            assertThrows(
                IllegalStateException.class,
                () ->
                    tm().transact(
                            () -> {
                              tm().put(theEntity);
                              tm().loadByKey(theEntityKey);
                            })))
        .hasMessageThat()
        .contains("Inserted/updated object reloaded: ");
  }

  @Test
  void query_detachesResults() {
    insertInDb(moreEntities);
    tm().transact(
            () ->
                tm().query("FROM TestEntity", TestEntity.class)
                    .getResultList()
                    .forEach(e -> assertThat(tm().getEntityManager().contains(e)).isFalse()));
    tm().transact(
            () ->
                tm().query("FROM TestEntity", TestEntity.class)
                    .getResultStream()
                    .forEach(e -> assertThat(tm().getEntityManager().contains(e)).isFalse()));

    tm().transact(
            () ->
                assertThat(
                        tm().getEntityManager()
                            .contains(
                                tm().query(
                                        "FROM TestEntity WHERE name = 'entity1'", TestEntity.class)
                                    .getSingleResult()))
                    .isFalse());
  }

  @Test
  void innerTransactions_noRetry() {
    JpaTransactionManager spyJpaTm = spy(tm());
    doThrow(OptimisticLockException.class).when(spyJpaTm).delete(any(VKey.class));
    spyJpaTm.transact(() -> spyJpaTm.insert(theEntity));

    Supplier<Runnable> supplier =
        () -> {
          Runnable work = () -> spyJpaTm.delete(theEntityKey);
          work.run();
          return null;
        };

    assertThrows(
        OptimisticLockException.class,
        () ->
            spyJpaTm.transact(
                () -> {
                  spyJpaTm.exists(theEntity);
                  spyJpaTm.transact(supplier);
                }));

    verify(spyJpaTm, times(3)).exists(theEntity);
    verify(spyJpaTm, times(3)).delete(theEntityKey);
  }

  private void insertPerson(int age) {
    tm().getEntityManager()
        .createNativeQuery(String.format("INSERT INTO Person (age) VALUES (%d)", age))
        .executeUpdate();
  }

  private void insertCompany(String name) {
    tm().getEntityManager()
        .createNativeQuery(String.format("INSERT INTO Company (name) VALUES ('%s')", name))
        .executeUpdate();
  }

  private void assertPersonExist(int age) {
    tm().transact(
            () -> {
              EntityManager em = tm().getEntityManager();
              Integer maybeAge =
                  (Integer)
                      em.createNativeQuery(
                              String.format("SELECT age FROM Person WHERE age = %d", age))
                          .getSingleResult();
              assertThat(maybeAge).isEqualTo(age);
            });
  }

  private void assertCompanyExist(String name) {
    tm().transact(
            () -> {
              String maybeName =
                  (String)
                      tm().getEntityManager()
                          .createNativeQuery(
                              String.format("SELECT name FROM Company WHERE name = '%s'", name))
                          .getSingleResult();
              assertThat(maybeName).isEqualTo(name);
            });
  }

  private void assertPersonCount(int count) {
    assertThat(countTable("Person")).isEqualTo(count);
  }

  private void assertCompanyCount(int count) {
    assertThat(countTable("Company")).isEqualTo(count);
  }

  private void assertPersonEmpty() {
    assertPersonCount(0);
  }

  private void assertCompanyEmpty() {
    assertCompanyCount(0);
  }

  private int countTable(String tableName) {
    return tm().transact(
            () -> {
              BigInteger colCount =
                  (BigInteger)
                      tm().getEntityManager()
                          .createNativeQuery(String.format("SELECT COUNT(*) FROM %s", tableName))
                          .getSingleResult();
              return colCount.intValue();
            });
  }

  @Entity(name = "TestEntity")
  private static class TestEntity extends ImmutableObject {
    @Id private String name;

    private String data;

    private TestEntity() {}

    private TestEntity(String name, String data) {
      this.name = name;
      this.data = data;
    }
  }

  @Entity(name = "TestCompoundIdEntity")
  @IdClass(CompoundId.class)
  private static class TestCompoundIdEntity extends ImmutableObject {
    @Id private String name;
    @Id private int age;

    private String data;

    private TestCompoundIdEntity() {}

    private TestCompoundIdEntity(String name, int age, String data) {
      this.name = name;
      this.age = age;
      this.data = data;
    }
  }

  private static class CompoundId implements Serializable {
    String name;
    int age;

    @SuppressWarnings("unused")
    private CompoundId() {}

    private CompoundId(String name, int age) {
      this.name = name;
      this.age = age;
    }
  }

  // An entity should still behave properly if the name fields in the ID are different
  @Entity(name = "TestNamedCompoundIdEntity")
  @IdClass(NamedCompoundId.class)
  private static class TestNamedCompoundIdEntity extends ImmutableObject {
    private String name;
    private int age;

    private TestNamedCompoundIdEntity() {}

    private TestNamedCompoundIdEntity(String name, int age) {
      this.name = name;
      this.age = age;
    }

    @Id
    public String getNameField() {
      return name;
    }

    @Id
    public int getAgeField() {
      return age;
    }

    @SuppressWarnings("unused")
    private void setNameField(String name) {
      this.name = name;
    }

    @SuppressWarnings("unused")
    private void setAgeField(int age) {
      this.age = age;
    }
  }

  private static class NamedCompoundId implements Serializable {
    String nameField;
    int ageField;

    @SuppressWarnings("unused")
    private NamedCompoundId() {}

    private NamedCompoundId(String nameField, int ageField) {
      this.nameField = nameField;
      this.ageField = ageField;
    }

    @SuppressWarnings("unused")
    private String getNameField() {
      return nameField;
    }

    @SuppressWarnings("unused")
    private int getAgeField() {
      return ageField;
    }

    @SuppressWarnings("unused")
    private void setNameField(String nameField) {
      this.nameField = nameField;
    }

    @SuppressWarnings("unused")
    private void setAgeField(int ageField) {
      this.ageField = ageField;
    }
  }
}
