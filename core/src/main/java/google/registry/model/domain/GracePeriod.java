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

package google.registry.model.domain;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.IdService.allocateId;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.annotations.VisibleForTesting;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Recurring;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.persistence.VKey;
import javax.annotation.Nullable;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import org.joda.time.DateTime;

/**
 * A domain grace period with an expiration time.
 *
 * <p>When a grace period expires, it is lazily removed from the {@link Domain} the next time the
 * resource is loaded from Datastore.
 */
@Entity
@Table(
    indexes = {
      @Index(columnList = "domainRepoId"),
      @Index(columnList = "billing_event_id"),
      @Index(columnList = "billing_recurrence_id")
    })
public class GracePeriod extends GracePeriodBase {

  @Id
  @Access(AccessType.PROPERTY)
  @Override
  public long getGracePeriodId() {
    return super.getGracePeriodId();
  }

  private static GracePeriod createInternal(
      GracePeriodStatus type,
      String domainRepoId,
      DateTime expirationTime,
      String registrarId,
      @Nullable VKey<BillingEvent.OneTime> billingEventOneTime,
      @Nullable VKey<BillingEvent.Recurring> billingEventRecurring,
      @Nullable Long gracePeriodId) {
    checkArgument(
        billingEventOneTime == null || billingEventRecurring == null,
        "A grace period can have at most one billing event");
    checkArgument(
        (billingEventRecurring != null) == GracePeriodStatus.AUTO_RENEW.equals(type),
        "Recurring billing events must be present on (and only on) autorenew grace periods");
    GracePeriod instance = new GracePeriod();
    instance.gracePeriodId = gracePeriodId == null ? allocateId() : gracePeriodId;
    instance.type = checkArgumentNotNull(type);
    instance.domainRepoId = checkArgumentNotNull(domainRepoId);
    instance.expirationTime = checkArgumentNotNull(expirationTime);
    instance.clientId = checkArgumentNotNull(registrarId);
    instance.billingEventOneTime = billingEventOneTime;
    instance.billingEventRecurring = billingEventRecurring;
    return instance;
  }

  /**
   * Creates a GracePeriod for an (optional) OneTime billing event.
   *
   * <p>Normal callers should always use {@link #forBillingEvent} instead, assuming they do not need
   * to avoid loading the BillingEvent from Datastore. This method should typically be called only
   * from test code to explicitly construct GracePeriods.
   */
  public static GracePeriod create(
      GracePeriodStatus type,
      String domainRepoId,
      DateTime expirationTime,
      String registrarId,
      @Nullable VKey<BillingEvent.OneTime> billingEventOneTime) {
    return createInternal(
        type, domainRepoId, expirationTime, registrarId, billingEventOneTime, null, null);
  }

  /**
   * Creates a GracePeriod for an (optional) OneTime billing event and a given {@link
   * #gracePeriodId}.
   *
   * <p>Normal callers should always use {@link #forBillingEvent} instead, assuming they do not need
   * to avoid loading the BillingEvent from Datastore. This method should typically be called only
   * from test code to explicitly construct GracePeriods.
   */
  @VisibleForTesting
  public static GracePeriod create(
      GracePeriodStatus type,
      String domainRepoId,
      DateTime expirationTime,
      String registrarId,
      @Nullable VKey<BillingEvent.OneTime> billingEventOneTime,
      @Nullable Long gracePeriodId) {
    return createInternal(
        type, domainRepoId, expirationTime, registrarId, billingEventOneTime, null, gracePeriodId);
  }

  public static GracePeriod createFromHistory(GracePeriodHistory history) {
    return createInternal(
        history.type,
        history.domainRepoId,
        history.expirationTime,
        history.clientId,
        history.billingEventOneTime,
        history.billingEventRecurring,
        history.gracePeriodId);
  }

  /** Creates a GracePeriod for a Recurring billing event. */
  public static GracePeriod createForRecurring(
      GracePeriodStatus type,
      String domainRepoId,
      DateTime expirationTime,
      String registrarId,
      VKey<Recurring> billingEventRecurring) {
    checkArgumentNotNull(billingEventRecurring, "billingEventRecurring cannot be null");
    return createInternal(
        type, domainRepoId, expirationTime, registrarId, null, billingEventRecurring, null);
  }

  /** Creates a GracePeriod for a Recurring billing event and a given {@link #gracePeriodId}. */
  @VisibleForTesting
  public static GracePeriod createForRecurring(
      GracePeriodStatus type,
      String domainRepoId,
      DateTime expirationTime,
      String registrarId,
      VKey<Recurring> billingEventRecurring,
      @Nullable Long gracePeriodId) {
    checkArgumentNotNull(billingEventRecurring, "billingEventRecurring cannot be null");
    return createInternal(
        type,
        domainRepoId,
        expirationTime,
        registrarId,
        null,
        billingEventRecurring,
        gracePeriodId);
  }

  /** Creates a GracePeriod with no billing event. */
  public static GracePeriod createWithoutBillingEvent(
      GracePeriodStatus type, String domainRepoId, DateTime expirationTime, String registrarId) {
    return createInternal(type, domainRepoId, expirationTime, registrarId, null, null, null);
  }

  /** Constructs a GracePeriod of the given type from the provided one-time BillingEvent. */
  public static GracePeriod forBillingEvent(
      GracePeriodStatus type, String domainRepoId, BillingEvent.OneTime billingEvent) {
    return create(
        type,
        domainRepoId,
        billingEvent.getBillingTime(),
        billingEvent.getRegistrarId(),
        billingEvent.createVKey());
  }

  /** Entity class to represent a historic {@link GracePeriod}. */
  @Entity(name = "GracePeriodHistory")
  @Table(indexes = @Index(columnList = "domainRepoId"))
  public static class GracePeriodHistory extends GracePeriodBase {
    @Id Long gracePeriodHistoryRevisionId;

    /** ID for the associated {@link DomainHistory} entity. */
    Long domainHistoryRevisionId;

    @Override
    @Access(AccessType.PROPERTY)
    public long getGracePeriodId() {
      return super.getGracePeriodId();
    }

    public HistoryEntryId getHistoryEntryId() {
      return new HistoryEntryId(getDomainRepoId(), domainHistoryRevisionId);
    }

    static GracePeriodHistory createFrom(long historyRevisionId, GracePeriod gracePeriod) {
      GracePeriodHistory instance = new GracePeriodHistory();
      instance.gracePeriodHistoryRevisionId = allocateId();
      instance.domainHistoryRevisionId = historyRevisionId;
      instance.gracePeriodId = gracePeriod.gracePeriodId;
      instance.type = gracePeriod.type;
      instance.domainRepoId = gracePeriod.domainRepoId;
      instance.expirationTime = gracePeriod.expirationTime;
      instance.clientId = gracePeriod.clientId;
      instance.billingEventOneTime = gracePeriod.billingEventOneTime;
      instance.billingEventRecurring = gracePeriod.billingEventRecurring;
      return instance;
    }
  }
}
