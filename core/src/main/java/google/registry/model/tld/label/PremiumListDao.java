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

package google.registry.model.tld.label;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.config.RegistryConfig.getDomainLabelListCacheDuration;
import static google.registry.config.RegistryConfig.getSingletonCachePersistDuration;
import static google.registry.config.RegistryConfig.getStaticPremiumListMaxCachedEntries;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.CollectionUtils.isNullOrEmpty;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.CacheUtils;
import google.registry.model.tld.label.PremiumList.PremiumEntry;
import google.registry.util.NonFinalForTesting;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;

/**
 * Data access object class for accessing {@link PremiumList} objects from Cloud SQL.
 *
 * <p>This class handles both the mapping from string to SQL-level PremiumList objects as well as
 * the mapping and retrieval of {@link PremiumEntry} objects that correspond to the particular
 * {@link PremiumList} object in SQL, and caching these entries so that future lookups can be
 * quicker.
 */
public class PremiumListDao {

  /**
   * In-memory cache for premium lists.
   *
   * <p>This is cached for a shorter duration because we need to periodically reload this entity to
   * check if a new revision has been published, and if so, then use that.
   *
   * <p>We also cache the absence of premium lists with a given name to avoid pointless lookups.
   */
  @NonFinalForTesting
  static LoadingCache<String, Optional<PremiumList>> premiumListCache =
      createPremiumListCache(getDomainLabelListCacheDuration());

  @VisibleForTesting
  public static void setPremiumListCacheForTest(Optional<Duration> expiry) {
    Duration effectiveExpiry = expiry.orElse(getDomainLabelListCacheDuration());
    premiumListCache = createPremiumListCache(effectiveExpiry);
  }

  @VisibleForTesting
  public static LoadingCache<String, Optional<PremiumList>> createPremiumListCache(
      Duration cachePersistDuration) {
    return CacheUtils.newCacheBuilder(cachePersistDuration)
        .build(PremiumListDao::getLatestRevisionUncached);
  }

  /**
   * In-memory price cache for a given premium list revision and domain label.
   *
   * <p>Note that premium list revision ids are globally unique, so this cache is specific to a
   * given premium list. Premium list entries might not be present, as indicated by the Optional
   * wrapper, and we want to cache that as well.
   *
   * <p>This is cached for a long duration (essentially indefinitely) because premium list revisions
   * are immutable and cannot ever be changed once created, so the cache need not ever expire.
   *
   * <p>A maximum size is set here on the cache because it can potentially grow too big to fit in
   * memory if there are a large number of distinct premium list entries being queried (both those
   * that exist, as well as those that might exist according to the Bloom filter, must be cached).
   * The entries judged least likely to be accessed again will be evicted first.
   */
  @NonFinalForTesting
  static LoadingCache<RevisionIdAndLabel, Optional<BigDecimal>> premiumEntryCache =
      createPremiumEntryCache(getSingletonCachePersistDuration());

  @VisibleForTesting
  static LoadingCache<RevisionIdAndLabel, Optional<BigDecimal>> createPremiumEntryCache(
      Duration cachePersistDuration) {
    return CacheUtils.newCacheBuilder(cachePersistDuration)
        .maximumSize(getStaticPremiumListMaxCachedEntries())
        .build(PremiumListDao::getPriceForLabelUncached);
  }

  /**
   * Returns the most recent revision of the PremiumList with the specified name, if it exists.
   *
   * <p>Note that this does not load <code>PremiumList.labelsToPrices</code>! If you need to check
   * prices, use {@link #getPremiumPrice}.
   */
  public static Optional<PremiumList> getLatestRevision(String premiumListName) {
    return premiumListCache.get(premiumListName);
  }

  /**
   * Returns the premium price for the specified label and registry, or absent if the label is not
   * premium.
   */
  public static Optional<Money> getPremiumPrice(String premiumListName, String label) {
    Optional<PremiumList> maybeLoadedList = getLatestRevision(premiumListName);
    if (!maybeLoadedList.isPresent()) {
      return Optional.empty();
    }
    PremiumList loadedList = maybeLoadedList.get();
    // Consult the bloom filter and immediately return if the label definitely isn't premium.
    if (!loadedList.getBloomFilter().mightContain(label)) {
      return Optional.empty();
    }
    RevisionIdAndLabel revisionIdAndLabel =
        RevisionIdAndLabel.create(loadedList.getRevisionId(), label);
    return premiumEntryCache.get(revisionIdAndLabel).map(loadedList::convertAmountToMoney);
  }

  public static PremiumList save(String name, CurrencyUnit currencyUnit, List<String> inputData) {
    checkArgument(!inputData.isEmpty(), "New premium list data cannot be empty");
    return save(PremiumListUtils.parseToPremiumList(name, currencyUnit, inputData));
  }

  /** Saves the given premium list (and its premium list entries) to Cloud SQL. */
  public static PremiumList save(PremiumList premiumList) {
    jpaTm()
        .transact(
            () -> {
              jpaTm().insert(premiumList);
              jpaTm().getEntityManager().flush(); // This populates the revisionId.
              long revisionId = premiumList.getRevisionId();

              if (!isNullOrEmpty(premiumList.getLabelsToPrices())) {
                ImmutableSet.Builder<PremiumEntry> entries = new ImmutableSet.Builder<>();
                premiumList
                    .getLabelsToPrices()
                    .forEach(
                        (key, value) -> entries.add(PremiumEntry.create(revisionId, value, key)));
                jpaTm().insertAll(entries.build());
              }
            });
    premiumListCache.invalidate(premiumList.getName());
    return premiumList;
  }

  public static void delete(PremiumList premiumList) {
    jpaTm()
        .transact(
            () -> {
              Optional<PremiumList> persistedList = getLatestRevision(premiumList.getName());
              if (persistedList.isPresent()) {
                jpaTm()
                    .query("DELETE FROM PremiumEntry WHERE revisionId = :revisionId")
                    .setParameter("revisionId", persistedList.get().getRevisionId())
                    .executeUpdate();
                jpaTm().delete(persistedList.get());
              }
            });
    premiumListCache.invalidate(premiumList.getName());
  }

  private static Optional<PremiumList> getLatestRevisionUncached(String premiumListName) {
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                    .query(
                        "FROM PremiumList WHERE name = :name ORDER BY revisionId DESC",
                        PremiumList.class)
                    .setParameter("name", premiumListName)
                    .setMaxResults(1)
                    .getResultStream()
                    .findFirst());
  }

  /**
   * Returns all {@link PremiumEntry PremiumEntries} in the given {@code premiumList}.
   *
   * <p>This is an expensive operation and should only be used when the entire list is required.
   */
  public static List<PremiumEntry> loadPremiumEntries(PremiumList premiumList) {
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                    .query(
                        "FROM PremiumEntry pe WHERE pe.revisionId = :revisionId",
                        PremiumEntry.class)
                    .setParameter("revisionId", premiumList.getRevisionId())
                    .getResultList());
  }

  /**
   * Loads the price for the given revisionId + label combination. Note that this does a database
   * retrieval so it should only be done in a cached context.
   */
  static Optional<BigDecimal> getPriceForLabelUncached(RevisionIdAndLabel revisionIdAndLabel) {
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                    .query(
                        "SELECT pe.price FROM PremiumEntry pe WHERE pe.revisionId = :revisionId"
                            + " AND pe.domainLabel = :label",
                        BigDecimal.class)
                    .setParameter("revisionId", revisionIdAndLabel.revisionId())
                    .setParameter("label", revisionIdAndLabel.label())
                    .setMaxResults(1)
                    .getResultStream()
                    .findFirst());
  }

  /**
   * Returns all {@link PremiumEntry PremiumEntries} in the list with the given name.
   *
   * <p>This is an expensive operation and should only be used when the entire list is required.
   */
  public static ImmutableList<PremiumEntry> loadAllPremiumEntries(String premiumListName) {
    PremiumList premiumList =
        getLatestRevision(premiumListName)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format("No premium list with name %s.", premiumListName)));
    return loadPremiumEntries(premiumList).stream()
        .map(
            premiumEntry ->
                new PremiumEntry.Builder()
                    .setPrice(premiumEntry.getValue())
                    .setLabel(premiumEntry.getDomainLabel())
                    .setRevisionId(premiumList.getRevisionId())
                    .build())
        .collect(toImmutableList());
  }

  @AutoValue
  abstract static class RevisionIdAndLabel {
    abstract long revisionId();

    abstract String label();

    static RevisionIdAndLabel create(long revisionId, String label) {
      return new AutoValue_PremiumListDao_RevisionIdAndLabel(revisionId, label);
    }
  }

  private PremiumListDao() {}
}
