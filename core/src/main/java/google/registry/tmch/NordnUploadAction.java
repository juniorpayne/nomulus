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

package google.registry.tmch;

import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.net.HttpHeaders.LOCATION;
import static com.google.common.net.MediaType.CSV_UTF_8;
import static google.registry.persistence.transaction.QueryComposer.Comparator.EQ;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.UrlConnectionUtils.getResponseBytes;
import static google.registry.tmch.LordnTaskUtils.COLUMNS_CLAIMS;
import static google.registry.tmch.LordnTaskUtils.COLUMNS_SUNRISE;
import static google.registry.tmch.LordnTaskUtils.getCsvLineForClaimsDomain;
import static google.registry.tmch.LordnTaskUtils.getCsvLineForSunriseDomain;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_ACCEPTED;

import com.google.api.client.http.HttpMethods;
import com.google.appengine.api.taskqueue.LeaseOptions;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.TaskHandle;
import com.google.appengine.api.taskqueue.TransientFailureException;
import com.google.apphosting.api.DeadlineExceededException;
import com.google.cloud.tasks.v2.Task;
import com.google.common.base.Ascii;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.flogger.FluentLogger;
import google.registry.batch.CloudTasksUtils;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.domain.Domain;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.Parameter;
import google.registry.request.RequestParameters;
import google.registry.request.UrlConnectionService;
import google.registry.request.UrlConnectionUtils;
import google.registry.request.auth.Auth;
import google.registry.tmch.LordnTaskUtils.LordnPhase;
import google.registry.util.Clock;
import google.registry.util.Retrier;
import google.registry.util.UrlConnectionException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Action that reads the NORDN pull queues, uploads claims and sunrise marks data to TMCH, and
 * enqueues subsequent upload verification tasks. A unique actionLogId is generated and passed along
 * to the verify action so that connected verify tasks can be identified by looking at logs.
 *
 * @see NordnVerifyAction
 */
@Action(
    service = Action.Service.BACKEND,
    path = NordnUploadAction.PATH,
    method = Action.Method.POST,
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class NordnUploadAction implements Runnable {

  static final String PATH = "/_dr/task/nordnUpload";
  static final String LORDN_PHASE_PARAM = "lordnPhase";
  // TODO: Delete after migrating off of pull queue.
  static final String PULL_QUEUE_PARAM = "pullQueue";

  private static final int BATCH_SIZE = 1000;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Duration LEASE_PERIOD = Duration.standardHours(1);

  /**
   * A unique (enough) id that is outputted in log lines to make it clear which log lines are
   * associated with a given invocation of the NordnUploadAction in the event that multiple
   * instances execute simultaneously.
   */
  private final String actionLogId = String.valueOf(1000000000 + new Random().nextInt(1000000000));

  @Inject Clock clock;
  @Inject Retrier retrier;
  @Inject SecureRandom random;
  @Inject LordnRequestInitializer lordnRequestInitializer;
  @Inject UrlConnectionService urlConnectionService;

  @Inject
  @Config("tmchMarksdbUrl")
  String tmchMarksdbUrl;

  @Inject
  @Parameter(LORDN_PHASE_PARAM)
  String phase;

  @Inject
  @Parameter(RequestParameters.PARAM_TLD)
  String tld;

  @Inject
  @Parameter(PULL_QUEUE_PARAM)
  Optional<Boolean> usePullQueue;

  @Inject CloudTasksUtils cloudTasksUtils;

  @Inject
  NordnUploadAction() {}

  /**
   * These LORDN parameter names correspond to the relative paths in LORDN URLs and cannot be
   * changed on our end.
   */
  private static final String PARAM_LORDN_PHASE_SUNRISE =
      Ascii.toLowerCase(LordnPhase.SUNRISE.toString());

  private static final String PARAM_LORDN_PHASE_CLAIMS =
      Ascii.toLowerCase(LordnPhase.CLAIMS.toString());

  /** How long to wait before attempting to verify an upload by fetching the log. */
  private static final Duration VERIFY_DELAY = Duration.standardMinutes(30);

  @Override
  public void run() {
    if (usePullQueue.orElse(false)) {
      try {
        processLordnTasks();
      } catch (IOException | GeneralSecurityException e) {
        throw new RuntimeException(e);
      }
    } else {
      checkArgument(
          phase.equals(PARAM_LORDN_PHASE_SUNRISE) || phase.equals(PARAM_LORDN_PHASE_CLAIMS),
          "Invalid phase specified to NordnUploadAction: %s.",
          phase);
      tm().transact(
              () -> {
                // Note here that we load all domains pending Nordn in one batch, which should not
                // be a problem for the rate of domain registration that we see. If we anticipate
                // a peak in claims during TLD launch (sunrise is NOT first-come-first-serve, so
                // there should be no expectation of a peak during it), we can consider temporarily
                // increasing the frequency of Nordn upload to reduce the size of each batch.
                //
                // We did not further divide the domains into smaller batches because the
                // read-upload-write operation per small batch needs to be inside a single
                // transaction to prevent race conditions, and running several uploads in rapid
                // sucession will likely overwhelm the MarksDB upload server, which recommands a
                // maximum upload frequency of every 3 hours.
                //
                // See:
                // https://datatracker.ietf.org/doc/html/draft-ietf-regext-tmch-func-spec-01#section-5.2.3.3
                List<Domain> domains =
                    tm().createQueryComposer(Domain.class)
                        .where("lordnPhase", EQ, LordnPhase.valueOf(Ascii.toUpperCase(phase)))
                        .where("tld", EQ, tld)
                        .orderBy("creationTime")
                        .list();
                if (domains.isEmpty()) {
                  return;
                }
                StringBuilder csv = new StringBuilder();
                ImmutableList.Builder<Domain> newDomains = new ImmutableList.Builder<>();

                domains.forEach(
                    domain -> {
                      if (phase.equals(PARAM_LORDN_PHASE_SUNRISE)) {
                        csv.append(getCsvLineForSunriseDomain(domain)).append('\n');
                      } else {
                        csv.append(getCsvLineForClaimsDomain(domain)).append('\n');
                      }
                      Domain newDomain = domain.asBuilder().setLordnPhase(LordnPhase.NONE).build();
                      newDomains.add(newDomain);
                    });
                String columns =
                    phase.equals(PARAM_LORDN_PHASE_SUNRISE) ? COLUMNS_SUNRISE : COLUMNS_CLAIMS;
                String header =
                    String.format("1,%s,%d\n%s\n", clock.nowUtc(), domains.size(), columns);
                try {
                  uploadCsvToLordn(String.format("/LORDN/%s/%s", tld, phase), header + csv);
                } catch (IOException | GeneralSecurityException e) {
                  throw new RuntimeException(e);
                }
                tm().updateAll(newDomains.build());
              });
    }
  }

  /**
   * Converts a list of queue tasks, each containing a row of CSV data, into a single newline-
   * delimited String.
   */
  static String convertTasksToCsv(List<TaskHandle> tasks, DateTime now, String columns) {
    // Use a Set for deduping purposes, so we can be idempotent in case tasks happened to be
    // enqueued multiple times for a given domain create.
    ImmutableSortedSet.Builder<String> builder =
        new ImmutableSortedSet.Builder<>(Ordering.natural());
    for (TaskHandle task : checkNotNull(tasks)) {
      String payload = new String(task.getPayload(), UTF_8);
      if (!Strings.isNullOrEmpty(payload)) {
        builder.add(payload + '\n');
      }
    }
    ImmutableSortedSet<String> csvLines = builder.build();
    String header = String.format("1,%s,%d\n%s\n", now, csvLines.size(), columns);
    return header + Joiner.on("").join(csvLines);
  }

  /** Leases and returns all tasks from the queue with the specified tag tld, in batches. */
  List<TaskHandle> loadAllTasks(Queue queue, String tld) {
    ImmutableList.Builder<TaskHandle> allTasks = new ImmutableList.Builder<>();
    while (true) {
      List<TaskHandle> tasks =
          retrier.callWithRetry(
              () ->
                  queue.leaseTasks(
                      LeaseOptions.Builder.withTag(tld)
                          .leasePeriod(LEASE_PERIOD.getMillis(), TimeUnit.MILLISECONDS)
                          .countLimit(BATCH_SIZE)),
              TransientFailureException.class,
              DeadlineExceededException.class);
      if (tasks.isEmpty()) {
        return allTasks.build();
      }
      allTasks.addAll(tasks);
    }
  }

  private void processLordnTasks() throws IOException, GeneralSecurityException {
    checkArgument(
        phase.equals(PARAM_LORDN_PHASE_SUNRISE) || phase.equals(PARAM_LORDN_PHASE_CLAIMS),
        "Invalid phase specified to NordnUploadAction: %s.",
        phase);
    DateTime now = clock.nowUtc();
    Queue queue =
        getQueue(
            phase.equals(PARAM_LORDN_PHASE_SUNRISE)
                ? LordnTaskUtils.QUEUE_SUNRISE
                : LordnTaskUtils.QUEUE_CLAIMS);
    String columns = phase.equals(PARAM_LORDN_PHASE_SUNRISE) ? COLUMNS_SUNRISE : COLUMNS_CLAIMS;
    List<TaskHandle> tasks = loadAllTasks(queue, tld);
    // Note: This upload/task deletion isn't done atomically (it's not clear how one would do so
    // anyway). As a result, it is possible that the upload might succeed yet the deletion of
    // enqueued tasks might fail. If so, this would result in the same lines being uploaded to NORDN
    // across multiple uploads. This is probably OK; all that we really cannot have is a missing
    // line.
    if (!tasks.isEmpty()) {
      String csvData = convertTasksToCsv(tasks, now, columns);
      uploadCsvToLordn(String.format("/LORDN/%s/%s", tld, phase), csvData);
      Lists.partition(tasks, BATCH_SIZE)
          .forEach(
              batch ->
                  retrier.callWithRetry(
                      () -> queue.deleteTask(batch), TransientFailureException.class));
    }
  }

  /**
   * Upload LORDN file to MarksDB.
   *
   * <p>Idempotency: If the exact same LORDN report is uploaded twice, the MarksDB server will
   * return the same confirmation number.
   *
   * @see <a href="http://tools.ietf.org/html/draft-lozano-tmch-func-spec-08#section-6.3">TMCH
   *     functional specifications - LORDN File</a>
   */
  private void uploadCsvToLordn(String urlPath, String csvData)
      throws IOException, GeneralSecurityException {
    String url = tmchMarksdbUrl + urlPath;
    logger.atInfo().log(
        "LORDN upload task %s: Sending to URL: %s ; data: %s", actionLogId, url, csvData);
    HttpURLConnection connection = urlConnectionService.createConnection(new URL(url));
    connection.setRequestMethod(HttpMethods.POST);
    lordnRequestInitializer.initialize(connection, tld);
    UrlConnectionUtils.setPayloadMultipart(
        connection, "file", "claims.csv", CSV_UTF_8, csvData, random);
    try {
      int responseCode = connection.getResponseCode();
      if (logger.atInfo().isEnabled()) {
        String responseContent = new String(getResponseBytes(connection), US_ASCII);
        if (responseContent.isEmpty()) {
          responseContent = "(null)";
        }
        logger.atInfo().log(
            "LORDN upload task %s response: HTTP response code %d, response data: %s",
            actionLogId, responseCode, responseContent);
      }
      if (responseCode != SC_ACCEPTED) {
        throw new UrlConnectionException(
            String.format(
                "LORDN upload task %s error: Failed to upload LORDN claims to MarksDB",
                actionLogId),
            connection);
      }
      String location = connection.getHeaderField(LOCATION);
      if (location == null) {
        throw new UrlConnectionException(
            String.format(
                "LORDN upload task %s error: MarksDB failed to provide a Location header",
                actionLogId),
            connection);
      }
      cloudTasksUtils.enqueue(NordnVerifyAction.QUEUE, makeVerifyTask(new URL(location)));
    } catch (IOException e) {
      throw new IOException(String.format("Error connecting to MarksDB at URL %s", url), e);
    }
  }

  private Task makeVerifyTask(URL url) {
    // The actionLogId is used to uniquely associate the verify task back to the upload task.
    return cloudTasksUtils.createPostTaskWithDelay(
        NordnVerifyAction.PATH,
        Service.BACKEND,
        ImmutableMultimap.<String, String>builder()
            .put(NordnVerifyAction.NORDN_URL_PARAM, url.toString())
            .put(NordnVerifyAction.NORDN_LOG_ID_PARAM, actionLogId)
            .put(RequestParameters.PARAM_TLD, tld)
            .build(),
        Duration.millis(VERIFY_DELAY.getMillis()));
  }
}
