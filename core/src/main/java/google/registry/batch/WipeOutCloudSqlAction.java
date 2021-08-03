// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.batch;

import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryEnvironment;
import google.registry.persistence.PersistenceModule.SchemaManagerConnection;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Retrier;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Supplier;
import javax.inject.Inject;

/**
 * Wipes out all Cloud SQL data in a Nomulus GCP environment.
 *
 * <p>This class is created for the QA environment, where migration testing with production data
 * will happen. A regularly scheduled wipeout is a prerequisite to using production data there.
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/wipeOutCloudSql",
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class WipeOutCloudSqlAction implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final ImmutableSet<RegistryEnvironment> FORBIDDEN_ENVIRONMENTS =
      ImmutableSet.of(RegistryEnvironment.PRODUCTION, RegistryEnvironment.SANDBOX);

  private final Supplier<Connection> connectionSupplier;
  private final Response response;
  private final Retrier retrier;

  @Inject
  WipeOutCloudSqlAction(
      @SchemaManagerConnection Supplier<Connection> connectionSupplier,
      Response response,
      Retrier retrier) {
    this.connectionSupplier = connectionSupplier;
    this.response = response;
    this.retrier = retrier;
  }

  @Override
  public void run() {
    response.setContentType(PLAIN_TEXT_UTF_8);

    if (FORBIDDEN_ENVIRONMENTS.contains(RegistryEnvironment.get())) {
      response.setStatus(SC_FORBIDDEN);
      response.setPayload("Wipeout is not allowed in " + RegistryEnvironment.get());
      return;
    }

    try {
      retrier.callWithRetry(
          () -> {
            try (Connection conn = connectionSupplier.get()) {
              dropAllTables(conn, listTables(conn));
              dropAllSequences(conn, listSequences(conn));
            }
            return null;
          },
          e -> !(e instanceof SQLException));
      response.setStatus(SC_OK);
      response.setPayload("Wiped out Cloud SQL in " + RegistryEnvironment.get());
    } catch (RuntimeException e) {
      logger.atSevere().withCause(e).log("Failed to wipe out Cloud SQL data.");
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setPayload("Failed to wipe out Cloud SQL in " + RegistryEnvironment.get());
    }
  }

  /** Returns a list of all tables in the public schema of a Postgresql database. */
  static ImmutableList<String> listTables(Connection connection) throws SQLException {
    try (ResultSet resultSet =
        connection.getMetaData().getTables(null, null, null, new String[] {"TABLE"})) {
      ImmutableList.Builder<String> tables = new ImmutableList.Builder<>();
      while (resultSet.next()) {
        String schema = resultSet.getString("TABLE_SCHEM");
        if (schema == null || !schema.equalsIgnoreCase("public")) {
          continue;
        }
        String tableName = resultSet.getString("TABLE_NAME");
        tables.add("public.\"" + tableName + "\"");
      }
      return tables.build();
    }
  }

  static void dropAllTables(Connection conn, ImmutableList<String> tables) throws SQLException {
    if (tables.isEmpty()) {
      return;
    }

    try (Statement statement = conn.createStatement()) {
      for (String table : tables) {
        statement.addBatch(String.format("DROP TABLE IF EXISTS %s CASCADE;", table));
      }
      for (int code : statement.executeBatch()) {
        if (code == Statement.EXECUTE_FAILED) {
          throw new RuntimeException("Failed to drop some tables. Please check.");
        }
      }
    }
  }

  /** Returns a list of all sequences in a Postgresql database. */
  static ImmutableList<String> listSequences(Connection conn) throws SQLException {
    try (Statement statement = conn.createStatement();
        ResultSet resultSet =
            statement.executeQuery("SELECT c.relname FROM pg_class c WHERE c.relkind = 'S';")) {
      ImmutableList.Builder<String> sequences = new ImmutableList.Builder<>();
      while (resultSet.next()) {
        sequences.add('\"' + resultSet.getString(1) + '\"');
      }
      return sequences.build();
    }
  }

  static void dropAllSequences(Connection conn, ImmutableList<String> sequences)
      throws SQLException {
    if (sequences.isEmpty()) {
      return;
    }

    try (Statement statement = conn.createStatement()) {
      for (String sequence : sequences) {
        statement.addBatch(String.format("DROP SEQUENCE IF EXISTS %s CASCADE;", sequence));
      }
      for (int code : statement.executeBatch()) {
        if (code == Statement.EXECUTE_FAILED) {
          throw new RuntimeException("Failed to drop some sequences. Please check.");
        }
      }
    }
  }
}
