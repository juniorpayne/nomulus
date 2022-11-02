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

package google.registry.batch;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.batch.BatchModule.PARAM_DRY_RUN;
import static google.registry.config.RegistryEnvironment.PRODUCTION;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_DELETE;
import static google.registry.model.tld.Registries.getTldsOfType;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.POST;
import static google.registry.request.RequestParameters.PARAM_TLDS;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryEnvironment;
import google.registry.dns.DnsQueue;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.EppResourceUtils;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.tld.Registry.TldType;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import org.hibernate.CacheMode;
import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;
import org.hibernate.query.Query;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Deletes all prober {@link Domain}s and their subordinate history entries, poll messages, and
 * billing events, along with their ForeignKeyDomainIndex entities.
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/deleteProberData",
    method = POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class DeleteProberDataAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * The maximum amount of time we allow a prober domain to be in use.
   *
   * <p>In practice, the prober's connection will time out well before this duration. This includes
   * a decent buffer.
   */
  private static final Duration DOMAIN_USED_DURATION = Duration.standardHours(1);

  /**
   * The minimum amount of time we want a domain to be "soft deleted".
   *
   * <p>The domain has to remain soft deleted for at least enough time for the DNS task to run and
   * remove it from DNS itself. This is probably on the order of minutes.
   */
  private static final Duration SOFT_DELETE_DELAY = Duration.standardHours(1);

  // Domains to delete must:
  // 1. Be in one of the prober TLDs
  // 2. Not be a nic domain
  // 3. Have no subordinate hosts
  // 4. Not still be used (within an hour of creation time)
  // 5. Either be active (creationTime <= now < deletionTime) or have been deleted a while ago (this
  //    prevents accidental double-map with the same key from immediately deleting active domains)
  //
  // Note: creationTime must be compared to a Java object (CreateAutoTimestamp) but deletionTime can
  // be compared directly to the SQL timestamp (it's a DateTime)
  private static final String DOMAIN_QUERY_STRING =
      "FROM Domain d WHERE d.tld IN :tlds AND d.domainName NOT LIKE 'nic.%' AND"
          + " (d.subordinateHosts IS EMPTY OR d.subordinateHosts IS NULL) AND d.creationTime <"
          + " :creationTimeCutoff AND ((d.creationTime <= :nowAutoTimestamp AND d.deletionTime >"
          + " current_timestamp()) OR d.deletionTime < :nowMinusSoftDeleteDelay) ORDER BY d.repoId";

  /** Number of domains to retrieve and delete per SQL transaction. */
  private static final int BATCH_SIZE = 1000;

  @Inject DnsQueue dnsQueue;

  @Inject
  @Parameter(PARAM_DRY_RUN)
  boolean isDryRun;
  /** List of TLDs to work on. If empty - will work on all TLDs that end with .test. */
  @Inject
  @Parameter(PARAM_TLDS)
  ImmutableSet<String> tlds;

  @Inject
  @Config("registryAdminClientId")
  String registryAdminRegistrarId;

  @Inject
  DeleteProberDataAction() {}

  @Override
  public void run() {
    checkState(
        !Strings.isNullOrEmpty(registryAdminRegistrarId),
        "Registry admin client ID must be configured for prober data deletion to work");
    checkArgument(
        !PRODUCTION.equals(RegistryEnvironment.get())
            || tlds.stream().allMatch(tld -> tld.endsWith(".test")),
        "On production, can only work on TLDs that end with .test");
    ImmutableSet<String> deletableTlds =
        getTldsOfType(TldType.TEST).stream()
            .filter(tld -> tlds.isEmpty() ? tld.endsWith(".test") : tlds.contains(tld))
            .collect(toImmutableSet());
    checkArgument(
        tlds.isEmpty() || deletableTlds.equals(tlds),
        "If tlds are given, they must all exist and be TEST tlds. Given: %s, not found: %s",
        tlds,
        Sets.difference(tlds, deletableTlds));
    runSqlJob(deletableTlds);
  }

  private void runSqlJob(ImmutableSet<String> deletableTlds) {
    AtomicInteger softDeletedDomains = new AtomicInteger();
    AtomicInteger hardDeletedDomains = new AtomicInteger();
    jpaTm().transact(() -> processDomains(deletableTlds, softDeletedDomains, hardDeletedDomains));
    logger.atInfo().log(
        "%s %d domains.",
        isDryRun ? "Would have soft-deleted" : "Soft-deleted", softDeletedDomains.get());
    logger.atInfo().log(
        "%s %d domains.",
        isDryRun ? "Would have hard-deleted" : "Hard-deleted", hardDeletedDomains.get());
  }

  private void processDomains(
      ImmutableSet<String> deletableTlds,
      AtomicInteger softDeletedDomains,
      AtomicInteger hardDeletedDomains) {
    DateTime now = tm().getTransactionTime();
    // Scroll through domains, soft-deleting as necessary (very few will be soft-deleted) and
    // keeping track of which domains to hard-delete (there can be many, so we batch them up)
    try (ScrollableResults scrollableResult =
        jpaTm()
            .query(DOMAIN_QUERY_STRING, Domain.class)
            .setParameter("tlds", deletableTlds)
            .setParameter(
                "creationTimeCutoff", CreateAutoTimestamp.create(now.minus(DOMAIN_USED_DURATION)))
            .setParameter("nowMinusSoftDeleteDelay", now.minus(SOFT_DELETE_DELAY))
            .setParameter("nowAutoTimestamp", CreateAutoTimestamp.create(now))
            .unwrap(Query.class)
            .setCacheMode(CacheMode.IGNORE)
            .scroll(ScrollMode.FORWARD_ONLY)) {
      ImmutableList.Builder<String> domainRepoIdsToHardDelete = new ImmutableList.Builder<>();
      ImmutableList.Builder<String> hostNamesToHardDelete = new ImmutableList.Builder<>();
      for (int i = 1; scrollableResult.next(); i = (i + 1) % BATCH_SIZE) {
        Domain domain = (Domain) scrollableResult.get(0);
        processDomain(
            domain,
            domainRepoIdsToHardDelete,
            hostNamesToHardDelete,
            softDeletedDomains,
            hardDeletedDomains);
        // Batch the deletion and DB flush + session clearing, so we don't OOM
        if (i == 0) {
          hardDeleteDomainsAndHosts(
              domainRepoIdsToHardDelete.build(), hostNamesToHardDelete.build());
          domainRepoIdsToHardDelete = new ImmutableList.Builder<>();
          hostNamesToHardDelete = new ImmutableList.Builder<>();
          jpaTm().getEntityManager().flush();
          jpaTm().getEntityManager().clear();
        }
      }
      // process the remainder
      hardDeleteDomainsAndHosts(domainRepoIdsToHardDelete.build(), hostNamesToHardDelete.build());
    }
  }

  private void processDomain(
      Domain domain,
      ImmutableList.Builder<String> domainRepoIdsToHardDelete,
      ImmutableList.Builder<String> hostNamesToHardDelete,
      AtomicInteger softDeletedDomains,
      AtomicInteger hardDeletedDomains) {
    // If the domain is still active, that means that the prober encountered a failure and did not
    // successfully soft-delete the domain (thus leaving its DNS entry published). We soft-delete
    // it now so that the DNS entry can be handled. The domain will then be hard-deleted the next
    // time the job is run.
    if (EppResourceUtils.isActive(domain, tm().getTransactionTime())) {
      if (isDryRun) {
        logger.atInfo().log(
            "Would soft-delete the active domain: %s (%s).",
            domain.getDomainName(), domain.getRepoId());
      } else {
        softDeleteDomain(domain);
      }
      softDeletedDomains.incrementAndGet();
    } else {
      if (isDryRun) {
        logger.atInfo().log(
            "Would hard-delete the non-active domain: %s (%s) and its dependents.",
            domain.getDomainName(), domain.getRepoId());
      } else {
        domainRepoIdsToHardDelete.add(domain.getRepoId());
        hostNamesToHardDelete.addAll(domain.getSubordinateHosts());
      }
      hardDeletedDomains.incrementAndGet();
    }
  }

  private void hardDeleteDomainsAndHosts(
      ImmutableList<String> domainRepoIds, ImmutableList<String> hostNames) {
    jpaTm()
        .query("DELETE FROM Host WHERE hostName IN :hostNames")
        .setParameter("hostNames", hostNames)
        .executeUpdate();
    jpaTm()
        .query("DELETE FROM BillingEvent WHERE domainRepoId IN :repoIds")
        .setParameter("repoIds", domainRepoIds)
        .executeUpdate();
    jpaTm()
        .query("DELETE FROM BillingRecurrence WHERE domainRepoId IN :repoIds")
        .setParameter("repoIds", domainRepoIds)
        .executeUpdate();
    jpaTm()
        .query("DELETE FROM BillingCancellation WHERE domainRepoId IN :repoIds")
        .setParameter("repoIds", domainRepoIds)
        .executeUpdate();
    jpaTm()
        .query("DELETE FROM DomainHistory WHERE repoId IN :repoIds")
        .setParameter("repoIds", domainRepoIds)
        .executeUpdate();
    jpaTm()
        .query("DELETE FROM PollMessage WHERE domainRepoId IN :repoIds")
        .setParameter("repoIds", domainRepoIds)
        .executeUpdate();
    jpaTm()
        .query("DELETE FROM Domain WHERE repoId IN :repoIds")
        .setParameter("repoIds", domainRepoIds)
        .executeUpdate();
  }

  // Take a DNS queue + admin registrar id as input so that it can be called from the mapper as well
  private void softDeleteDomain(Domain domain) {
    Domain deletedDomain =
        domain.asBuilder().setDeletionTime(tm().getTransactionTime()).setStatusValues(null).build();
    DomainHistory historyEntry =
        new DomainHistory.Builder()
            .setDomain(domain)
            .setType(DOMAIN_DELETE)
            .setModificationTime(tm().getTransactionTime())
            .setBySuperuser(true)
            .setReason("Deletion of prober data")
            .setRegistrarId(registryAdminRegistrarId)
            .build();
    // Note that we don't bother handling grace periods, billing events, pending transfers, poll
    // messages, or auto-renews because those will all be hard-deleted the next time the job runs
    // anyway.
    tm().putAll(ImmutableList.of(deletedDomain, historyEntry));
    dnsQueue.addDomainRefreshTask(deletedDomain.getDomainName());
  }
}
