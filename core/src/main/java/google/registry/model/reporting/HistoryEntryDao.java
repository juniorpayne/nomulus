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

package google.registry.model.reporting;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.host.HostHistory;
import google.registry.model.host.HostResource;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.CriteriaQueryBuilder;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import org.joda.time.DateTime;

/**
 * Retrieves {@link HistoryEntry} descendants (e.g. {@link DomainHistory}).
 *
 * <p>This class is configured to retrieve either from Datastore or SQL, depending on which database
 * is currently considered the primary database.
 */
public class HistoryEntryDao {

  public static ImmutableMap<Class<? extends EppResource>, Class<? extends HistoryEntry>>
      RESOURCE_TYPES_TO_HISTORY_TYPES =
          ImmutableMap.of(
              ContactResource.class,
              ContactHistory.class,
              DomainBase.class,
              DomainHistory.class,
              HostResource.class,
              HostHistory.class);

  public static ImmutableMap<Class<? extends HistoryEntry>, String> REPO_ID_FIELD_NAMES =
      ImmutableMap.of(
          ContactHistory.class,
          "contactRepoId",
          DomainHistory.class,
          "domainRepoId",
          HostHistory.class,
          "hostRepoId");

  /** Loads all history objects in the times specified, including all types. */
  public static ImmutableList<HistoryEntry> loadAllHistoryObjects(
      DateTime afterTime, DateTime beforeTime) {
    return jpaTm()
        .transact(
            () ->
                new ImmutableList.Builder<HistoryEntry>()
                    .addAll(
                        loadAllHistoryObjectsFromSql(ContactHistory.class, afterTime, beforeTime))
                    .addAll(
                        loadAllHistoryObjectsFromSql(DomainHistory.class, afterTime, beforeTime))
                    .addAll(loadAllHistoryObjectsFromSql(HostHistory.class, afterTime, beforeTime))
                    .build());
  }

  /** Loads all history objects corresponding to the given {@link EppResource}. */
  public static ImmutableList<HistoryEntry> loadHistoryObjectsForResource(
      VKey<? extends EppResource> parentKey) {
    return loadHistoryObjectsForResource(parentKey, START_OF_TIME, END_OF_TIME);
  }

  /**
   * Loads all history objects corresponding to the given {@link EppResource} and casted to the
   * appropriate subclass.
   */
  public static <T extends HistoryEntry> ImmutableList<T> loadHistoryObjectsForResource(
      VKey<? extends EppResource> parentKey, Class<T> subclazz) {
    return loadHistoryObjectsForResource(parentKey, START_OF_TIME, END_OF_TIME, subclazz);
  }

  /** Loads all history objects in the time period specified for the given {@link EppResource}. */
  public static ImmutableList<HistoryEntry> loadHistoryObjectsForResource(
      VKey<? extends EppResource> parentKey, DateTime afterTime, DateTime beforeTime) {
    return jpaTm()
        .transact(() -> loadHistoryObjectsForResourceFromSql(parentKey, afterTime, beforeTime));
  }

  /**
   * Loads all history objects in the time period specified for the given {@link EppResource} and
   * casted to the appropriate subclass.
   *
   * <p>Note that the subclass must be explicitly provided because we need the compile time
   * information of T to return an {@code ImmutableList<T>}, even though at runtime we can call
   * {@link #getHistoryClassFromParent(Class)} to obtain it, which we also did to confirm that the
   * provided subclass is indeed correct.
   */
  public static <T extends HistoryEntry> ImmutableList<T> loadHistoryObjectsForResource(
      VKey<? extends EppResource> parentKey,
      DateTime afterTime,
      DateTime beforeTime,
      Class<T> subclazz) {
    Class<? extends HistoryEntry> expectedSubclazz = getHistoryClassFromParent(parentKey.getKind());
    checkArgument(
        subclazz.equals(expectedSubclazz),
        "The supplied HistoryEntry subclass %s is incompatible with the EppResource %s, "
            + "use %s instead",
        subclazz.getSimpleName(),
        parentKey.getKind().getSimpleName(),
        expectedSubclazz.getSimpleName());
    return loadHistoryObjectsForResource(parentKey, afterTime, beforeTime).stream()
        .map(subclazz::cast)
        .collect(toImmutableList());
  }

  /** Loads all history objects from all time from the given registrars. */
  public static Iterable<HistoryEntry> loadHistoryObjectsByRegistrars(
      ImmutableCollection<String> registrarIds) {
    return jpaTm()
        .transact(
            () ->
                Streams.concat(
                        loadHistoryObjectFromSqlByRegistrars(ContactHistory.class, registrarIds),
                        loadHistoryObjectFromSqlByRegistrars(DomainHistory.class, registrarIds),
                        loadHistoryObjectFromSqlByRegistrars(HostHistory.class, registrarIds))
                    .sorted(Comparator.comparing(HistoryEntry::getModificationTime))
                    .collect(toImmutableList()));
  }

  private static <T extends HistoryEntry> Stream<T> loadHistoryObjectFromSqlByRegistrars(
      Class<T> historyClass, ImmutableCollection<String> registrarIds) {
    return jpaTm()
        .criteriaQuery(
            CriteriaQueryBuilder.create(historyClass)
                .whereFieldIsIn("clientId", registrarIds)
                .build())
        .getResultStream();
  }

  private static ImmutableList<HistoryEntry> loadHistoryObjectsForResourceFromSql(
      VKey<? extends EppResource> parentKey, DateTime afterTime, DateTime beforeTime) {
    // The class we're searching from is based on which parent type (e.g. Domain) we have
    Class<? extends HistoryEntry> historyClass = getHistoryClassFromParent(parentKey.getKind());
    // The field representing repo ID unfortunately varies by history class
    String repoIdFieldName = getRepoIdFieldNameFromHistoryClass(historyClass);
    CriteriaBuilder criteriaBuilder = jpaTm().getEntityManager().getCriteriaBuilder();
    CriteriaQuery<? extends HistoryEntry> criteriaQuery =
        CriteriaQueryBuilder.create(historyClass)
            .where("modificationTime", criteriaBuilder::greaterThanOrEqualTo, afterTime)
            .where("modificationTime", criteriaBuilder::lessThanOrEqualTo, beforeTime)
            .where(repoIdFieldName, criteriaBuilder::equal, parentKey.getSqlKey().toString())
            .orderByAsc("id")
            .build();

    return ImmutableList.sortedCopyOf(
        Comparator.comparing(HistoryEntry::getModificationTime),
        jpaTm().criteriaQuery(criteriaQuery).getResultList());
  }

  public static Class<? extends HistoryEntry> getHistoryClassFromParent(
      Class<? extends EppResource> parent) {
    if (!RESOURCE_TYPES_TO_HISTORY_TYPES.containsKey(parent)) {
      throw new IllegalArgumentException(
          String.format("Unknown history type for parent %s", parent.getName()));
    }
    return RESOURCE_TYPES_TO_HISTORY_TYPES.get(parent);
  }

  public static String getRepoIdFieldNameFromHistoryClass(
      Class<? extends HistoryEntry> historyClass) {
    if (!REPO_ID_FIELD_NAMES.containsKey(historyClass)) {
      throw new IllegalArgumentException(
          String.format("Unknown history type %s", historyClass.getName()));
    }
    return REPO_ID_FIELD_NAMES.get(historyClass);
  }

  private static <T extends HistoryEntry> List<T> loadAllHistoryObjectsFromSql(
      Class<T> historyClass, DateTime afterTime, DateTime beforeTime) {
    CriteriaBuilder criteriaBuilder = jpaTm().getEntityManager().getCriteriaBuilder();
    return jpaTm()
        .criteriaQuery(
            CriteriaQueryBuilder.create(historyClass)
                .where("modificationTime", criteriaBuilder::greaterThanOrEqualTo, afterTime)
                .where("modificationTime", criteriaBuilder::lessThanOrEqualTo, beforeTime)
                .build())
        .getResultList();
  }
}
