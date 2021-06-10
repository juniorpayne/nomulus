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

package google.registry.export;

import static com.google.appengine.tools.cloudstorage.GcsServiceFactory.createGcsService;
import static com.google.common.base.Verify.verifyNotNull;
import static google.registry.mapreduce.inputs.EppResourceInputs.createEntityInput;
import static google.registry.model.EppResourceUtils.isActive;
import static google.registry.model.registry.Registries.getTldsOfType;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.POST;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.RetryParams;
import com.google.appengine.tools.mapreduce.Mapper;
import com.google.appengine.tools.mapreduce.Reducer;
import com.google.appengine.tools.mapreduce.ReducerInput;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.model.domain.DomainBase;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldType;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.storage.drive.DriveConnection;
import google.registry.util.Clock;
import google.registry.util.NonFinalForTesting;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;
import java.util.function.Supplier;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * A mapreduce that exports the list of active domains on all real TLDs to Google Drive and GCS.
 *
 * <p>Each TLD's active domain names are exported as a newline-delimited flat text file with the
 * name TLD.txt into the domain-lists bucket. Note that this overwrites the files in place.
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/exportDomainLists",
    method = POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class ExportDomainListsAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final int MAX_NUM_REDUCE_SHARDS = 100;
  public static final String REGISTERED_DOMAINS_FILENAME = "registered_domains.txt";

  @Inject MapreduceRunner mrRunner;
  @Inject Response response;
  @Inject Clock clock;
  @Inject DriveConnection driveConnection;

  @Inject @Config("domainListsGcsBucket") String gcsBucket;
  @Inject @Config("gcsBufferSize") int gcsBufferSize;
  @Inject ExportDomainListsAction() {}

  @Override
  public void run() {
    ImmutableSet<String> realTlds = getTldsOfType(TldType.REAL);
    logger.atInfo().log("Exporting domain lists for tlds %s", realTlds);
    if (tm().isOfy()) {
      mrRunner
          .setJobName("Export domain lists")
          .setModuleName("backend")
          .setDefaultReduceShards(Math.min(realTlds.size(), MAX_NUM_REDUCE_SHARDS))
          .runMapreduce(
              new ExportDomainListsMapper(clock.nowUtc(), realTlds),
              new ExportDomainListsReducer(gcsBucket, gcsBufferSize),
              ImmutableList.of(createEntityInput(DomainBase.class)))
          .sendLinkToMapreduceConsole(response);
    } else {
      realTlds.forEach(
          tld -> {
            List<String> domains =
                tm().transact(
                        () ->
                            // Note that if we had "creationTime <= :now" in the condition (not
                            // necessary as there is no pending creation, the order of deletionTime
                            // and creationTime in the query would have been significant and it
                            // should come after deletionTime. When Hibernate substitutes "now" it
                            // will first validate that the **first** field that is to be compared
                            // with it (deletionTime) is assignable from the substituted Java object
                            // (click.nowUtc()). Since creationTime is a CreateAutoTimestamp, if it
                            // comes first, we will need to substitute "now" with
                            // CreateAutoTimestamp.create(clock.nowUtc()). This might look a bit
                            // strange as the Java object type is clearly incompatible between the
                            // two fields deletionTime (DateTime) and creationTime, yet they are
                            // compared with the same "now". It is actually OK because in the end
                            // Hibernate converts everything to SQL types (and Java field names to
                            // SQL column names) to run the query. Both CreateAutoTimestamp and
                            // DateTime are persisted as timestamp_z in SQL. It is only the
                            // validation that compares the Java types, and only with the first
                            // field that compares with the substituted value.
                            jpaTm()
                                .query(
                                    "SELECT fullyQualifiedDomainName FROM Domain "
                                        + "WHERE tld = :tld "
                                        + "AND deletionTime > :now "
                                        + "ORDER by fullyQualifiedDomainName ASC",
                                    String.class)
                                .setParameter("tld", tld)
                                .setParameter("now", clock.nowUtc())
                                .getResultList());
            String domainsList = Joiner.on("\n").join(domains);
            logger.atInfo().log(
                "Exporting %d domains for TLD %s to GCS and Drive.", domains.size(), tld);
            exportToGcs(tld, domainsList, gcsBucket, gcsBufferSize);
            exportToDrive(tld, domainsList, driveConnection);
          });
    }
  }

  protected static boolean exportToDrive(
      String tld, String domains, DriveConnection driveConnection) {
    verifyNotNull(driveConnection, "Expecting non-null driveConnection");
    try {
      Registry registry = Registry.get(tld);
      if (registry.getDriveFolderId() == null) {
        logger.atInfo().log(
            "Skipping registered domains export for TLD %s because Drive folder isn't specified",
            tld);
      } else {
        String resultMsg =
            driveConnection.createOrUpdateFile(
                REGISTERED_DOMAINS_FILENAME,
                MediaType.PLAIN_TEXT_UTF_8,
                registry.getDriveFolderId(),
                domains.getBytes(UTF_8));
        logger.atInfo().log(
            "Exporting registered domains succeeded for TLD %s, response was: %s", tld, resultMsg);
      }
    } catch (Throwable e) {
      logger.atSevere().withCause(e).log(
          "Error exporting registered domains for TLD %s to Drive, skipping...", tld);
      return false;
    }
    return true;
  }

  protected static boolean exportToGcs(
      String tld, String domains, String gcsBucket, int gcsBufferSize) {
    GcsFilename filename = new GcsFilename(gcsBucket, tld + ".txt");
    GcsUtils cloudStorage =
        new GcsUtils(createGcsService(RetryParams.getDefaultInstance()), gcsBufferSize);
    try (OutputStream gcsOutput = cloudStorage.openOutputStream(filename);
        Writer osWriter = new OutputStreamWriter(gcsOutput, UTF_8)) {
      osWriter.write(domains);
    } catch (Throwable e) {
      logger.atSevere().withCause(e).log(
          "Error exporting registered domains for TLD %s to GCS, skipping...", tld);
      return false;
    }
    return true;
  }

  static class ExportDomainListsMapper extends Mapper<DomainBase, String, String> {

    private static final long serialVersionUID = -7312206212434039854L;

    private final DateTime exportTime;
    private final ImmutableSet<String> realTlds;

    ExportDomainListsMapper(DateTime exportTime, ImmutableSet<String> realTlds) {
      this.exportTime = exportTime;
      this.realTlds = realTlds;
    }

    @Override
    public void map(DomainBase domain) {
      if (realTlds.contains(domain.getTld()) && isActive(domain, exportTime)) {
        emit(domain.getTld(), domain.getDomainName());
        getContext().incrementCounter(String.format("domains in tld %s", domain.getTld()));
      }
    }
  }

  static class ExportDomainListsReducer extends Reducer<String, String, Void> {

    private static final long serialVersionUID = 7035260977259119087L;

    /** Allows overriding the default {@link DriveConnection} in tests. */
    @NonFinalForTesting
    private static Supplier<DriveConnection> driveConnectionSupplier =
        Suppliers.memoize(() -> DaggerDriveModule_DriveComponent.create().driveConnection());

    private final String gcsBucket;
    private final int gcsBufferSize;

    /**
     * Non-serializable {@link DriveConnection} that will be created when an instance of {@link
     * ExportDomainListsReducer} is deserialized in a MR pipeline worker.
     *
     * <p>See {@link #readObject(ObjectInputStream)}.
     */
    private transient DriveConnection driveConnection;

    public ExportDomainListsReducer(String gcsBucket, int gcsBufferSize) {
      this.gcsBucket = gcsBucket;
      this.gcsBufferSize = gcsBufferSize;
    }

    @SuppressWarnings("unused")
    private void readObject(ObjectInputStream is) throws IOException, ClassNotFoundException {
      is.defaultReadObject();
      driveConnection = driveConnectionSupplier.get();
    }

    @Override
    public void reduce(String tld, ReducerInput<String> fqdns) {
      ImmutableList<String> domains = ImmutableList.sortedCopyOf(() -> fqdns);
      String domainsList = Joiner.on('\n').join(domains);
      logger.atInfo().log("Exporting %d domains for TLD %s to GCS and Drive.", domains.size(), tld);
      if (exportToGcs(tld, domainsList, gcsBucket, gcsBufferSize)) {
        getContext().incrementCounter("domain lists successful written out to GCS");
      } else {
        getContext().incrementCounter("domain lists failed to write out to GCS");
      }
      if (exportToDrive(tld, domainsList, driveConnection)) {
        getContext().incrementCounter("domain lists successfully written out to Drive");
      } else {
        getContext().incrementCounter("domain lists failed to write out to Drive");
      }
    }

    @VisibleForTesting
    static void setDriveConnectionForTesting(
        Supplier<DriveConnection> testDriveConnectionSupplier) {
      driveConnectionSupplier = testDriveConnectionSupplier;
    }
  }
}
