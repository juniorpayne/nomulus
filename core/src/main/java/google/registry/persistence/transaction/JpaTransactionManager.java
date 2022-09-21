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

import google.registry.persistence.VKey;
import java.util.function.Supplier;
import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaQuery;

/** Sub-interface of {@link TransactionManager} which defines JPA related methods. */
public interface JpaTransactionManager extends TransactionManager {

  /**
   * Returns a long-lived {@link EntityManager} not bound to a particular transaction.
   *
   * <p>Caller is responsible for closing the returned instance.
   */
  EntityManager getStandaloneEntityManager();

  /**
   * Specifies a database snapshot exported by another transaction to use in the current
   * transaction.
   *
   * <p>This is a Postgresql-specific feature. This method must be called before any other SQL
   * commands in a transaction.
   *
   * <p>To support large queries, transaction isolation level is fixed at the REPEATABLE_READ to
   * avoid exhausting predicate locks at the SERIALIZABLE level.
   *
   * @see google.registry.beam.common.DatabaseSnapshot
   */
  // TODO(b/193662898): vendor-independent support for richer transaction semantics.
  JpaTransactionManager setDatabaseSnapshot(String snapshotId);

  /**
   * Returns the {@link EntityManager} for the current request.
   *
   * <p>The returned instance is closed when the current transaction completes.
   */
  EntityManager getEntityManager();

  /**
   * Creates a JPA SQL query for the given query string and result class.
   *
   * <p>This is a convenience method for the longer <code>
   * jpaTm().getEntityManager().createQuery(...)</code>.
   */
  <T> TypedQuery<T> query(String sqlString, Class<T> resultClass);

  /** Creates a JPA SQL query for the given criteria query. */
  <T> TypedQuery<T> criteriaQuery(CriteriaQuery<T> criteriaQuery);

  /**
   * Creates a JPA SQL query for the given query string.
   *
   * <p>This is a convenience method for the longer <code>
   * jpaTm().getEntityManager().createQuery(...)</code>.
   *
   * <p>Note that while this method can legally be used for queries that return results, <u>it
   * should not be</u>, as it does not correctly detach entities as must be done for nomulus model
   * objects.
   */
  Query query(String sqlString);

  /**
   * Execute the work in a transaction without recording the transaction for replay to datastore.
   */
  <T> T transactWithoutBackup(Supplier<T> work);

  /** Executes the work in a transaction with no retries and returns the result. */
  <T> T transactNoRetry(Supplier<T> work);

  /** Executes the work in a transaction with no retries. */
  void transactNoRetry(Runnable work);

  /** Deletes the entity by its id, throws exception if the entity is not deleted. */
  <T> void assertDelete(VKey<T> key);

  /**
   * Releases all resources and shuts down.
   *
   * <p>The errorprone check forbids injection of {@link java.io.Closeable} resources.
   */
  void teardown();

  /**
   * Sets the JDBC driver fetch size for the {@code query}. This overrides the default
   * configuration.
   */
  static Query setQueryFetchSize(Query query, int fetchSize) {
    return query.setHint("org.hibernate.fetchSize", fetchSize);
  }
}
