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

package google.registry.tools.server;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.tld.Registries.assertTldsExist;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.RequestParameters.PARAM_TLDS;

import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.dns.DnsQueue;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import java.util.Random;
import javax.inject.Inject;
import org.apache.http.HttpStatus;
import org.joda.time.Duration;

/**
 * A task that enqueues DNS publish tasks on all active domains on the specified TLD(s).
 *
 * <p>This refreshes DNS both for all domain names and all in-bailiwick hostnames, as DNS writers
 * are responsible for enqueuing refresh tasks for subordinate hosts. So this action thus refreshes
 * DNS for everything applicable under all TLDs under management.
 *
 * <p>Because there are no auth settings in the {@link Action} annotation, this command can only be
 * run internally, or by pretending to be internal by setting the X-AppEngine-QueueName header,
 * which only admin users can do.
 *
 * <p>You must pass in a number of <code>smearMinutes</code> as a URL parameter so that the DNS
 * queue doesn't get overloaded. A rough rule of thumb for Cloud DNS is 1 minute per every 1,000
 * domains. This smears the updates out over the next N minutes. For small TLDs consisting of fewer
 * than 1,000 domains, passing in 1 is fine (which will execute all the updates immediately).
 */
@Action(
    service = Action.Service.TOOLS,
    path = "/_dr/task/refreshDnsForAllDomains",
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class RefreshDnsForAllDomainsAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject Response response;

  @Inject
  @Parameter(PARAM_TLDS)
  ImmutableSet<String> tlds;

  @Inject
  @Parameter("smearMinutes")
  int smearMinutes;

  @Inject DnsQueue dnsQueue;
  @Inject Clock clock;
  @Inject Random random;

  @Inject
  RefreshDnsForAllDomainsAction() {}

  @Override
  public void run() {
    assertTldsExist(tlds);
    checkArgument(smearMinutes > 0, "Must specify a positive number of smear minutes");
    tm().transact(
            () ->
                jpaTm()
                    .query(
                        "SELECT domainName FROM Domain "
                            + "WHERE tld IN (:tlds) "
                            + "AND deletionTime > :now",
                        String.class)
                    .setParameter("tlds", tlds)
                    .setParameter("now", clock.nowUtc())
                    .getResultStream()
                    .forEach(
                        domainName -> {
                          try {
                            // Smear the task execution time over the next N minutes.
                            dnsQueue.addDomainRefreshTask(
                                domainName, Duration.standardMinutes(random.nextInt(smearMinutes)));
                          } catch (Throwable t) {
                            logger.atSevere().withCause(t).log(
                                "Error while enqueuing DNS refresh for domain '%s'.", domainName);
                            response.setStatus(HttpStatus.SC_INTERNAL_SERVER_ERROR);
                          }
                        }));
  }
}
