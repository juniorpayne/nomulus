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

package google.registry.model.domain;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.EntitySubclass;
import com.googlecode.objectify.annotation.Ignore;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.DomainHistory.DomainHistoryId;
import google.registry.model.domain.GracePeriod.GracePeriodHistory;
import google.registry.model.domain.secdns.DomainDsDataHistory;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.schema.replay.DatastoreEntity;
import google.registry.schema.replay.SqlEntity;
import java.io.Serializable;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.JoinColumns;
import javax.persistence.JoinTable;
import javax.persistence.OneToMany;
import javax.persistence.PostLoad;
import javax.persistence.Table;

/**
 * A persisted history entry representing an EPP modification to a domain.
 *
 * <p>In addition to the general history fields (e.g. action time, registrar ID) we also persist a
 * copy of the domain entity at this point in time. We persist a raw {@link DomainContent} so that
 * the foreign-keyed fields in that class can refer to this object.
 */
@Entity
@Table(
    indexes = {
      @Index(columnList = "creationTime"),
      @Index(columnList = "historyRegistrarId"),
      @Index(columnList = "historyType"),
      @Index(columnList = "historyModificationTime")
    })
@EntitySubclass
@Access(AccessType.FIELD)
@IdClass(DomainHistoryId.class)
public class DomainHistory extends HistoryEntry implements SqlEntity {

  // Store DomainContent instead of DomainBase so we don't pick up its @Id
  @Nullable DomainContent domainContent;

  @Id
  @Access(AccessType.PROPERTY)
  public String getDomainRepoId() {
    // We need to handle null case here because Hibernate sometimes accesses this method before
    // parent gets initialized
    return parent == null ? null : parent.getName();
  }

  /** This method is private because it is only used by Hibernate. */
  @SuppressWarnings("unused")
  private void setDomainRepoId(String domainRepoId) {
    parent = Key.create(DomainBase.class, domainRepoId);
  }

  // We could have reused domainContent.nsHosts here, but Hibernate throws a weird exception after
  // we change to use a composite primary key.
  // TODO(b/166776754): Investigate if we can reuse domainContent.nsHosts for storing host keys.
  @Ignore
  @ElementCollection
  @JoinTable(
      name = "DomainHistoryHost",
      indexes = {
        @Index(
            columnList =
                "domain_history_history_revision_id,domain_history_domain_repo_id,host_repo_id",
            unique = true),
      })
  @ImmutableObject.EmptySetToNull
  @Column(name = "host_repo_id")
  Set<VKey<HostResource>> nsHosts;

  @Ignore
  @OneToMany(
      cascade = {CascadeType.ALL},
      fetch = FetchType.EAGER,
      orphanRemoval = true)
  @JoinColumns({
    @JoinColumn(
        name = "domainHistoryRevisionId",
        referencedColumnName = "historyRevisionId",
        insertable = false,
        updatable = false),
    @JoinColumn(
        name = "domainRepoId",
        referencedColumnName = "domainRepoId",
        insertable = false,
        updatable = false)
  })
  Set<DomainDsDataHistory> dsDataHistories = ImmutableSet.of();

  @Ignore
  @OneToMany(
      cascade = {CascadeType.ALL},
      fetch = FetchType.EAGER,
      orphanRemoval = true)
  @JoinColumns({
    @JoinColumn(
        name = "domainHistoryRevisionId",
        referencedColumnName = "historyRevisionId",
        insertable = false,
        updatable = false),
    @JoinColumn(
        name = "domainRepoId",
        referencedColumnName = "domainRepoId",
        insertable = false,
        updatable = false)
  })
  Set<GracePeriodHistory> gracePeriodHistories = ImmutableSet.of();

  @Override
  @Nullable
  @Access(AccessType.PROPERTY)
  @AttributeOverrides({
    @AttributeOverride(name = "unit", column = @Column(name = "historyPeriodUnit")),
    @AttributeOverride(name = "value", column = @Column(name = "historyPeriodValue"))
  })
  public Period getPeriod() {
    return super.getPeriod();
  }

  /**
   * For transfers, the id of the other registrar.
   *
   * <p>For requests and cancels, the other registrar is the losing party (because the registrar
   * sending the EPP transfer command is the gaining party). For approves and rejects, the other
   * registrar is the gaining party.
   */
  @Nullable
  @Access(AccessType.PROPERTY)
  @Column(name = "historyOtherRegistrarId")
  public String getOtherRegistrarId() {
    return super.getOtherClientId();
  }

  /**
   * Logging field for transaction reporting.
   *
   * <p>This will be empty for any DomainHistory/HistoryEntry generated before this field was added,
   * mid-2017, as well as any action that does not generate billable events (e.g. updates).
   *
   * <p>This method is dedicated for Hibernate, external caller should use {@link
   * #getDomainTransactionRecords()}.
   */
  @Access(AccessType.PROPERTY)
  @OneToMany(
      cascade = {CascadeType.ALL},
      fetch = FetchType.EAGER)
  @JoinColumn(name = "historyRevisionId", referencedColumnName = "historyRevisionId")
  @JoinColumn(name = "domainRepoId", referencedColumnName = "domainRepoId")
  @SuppressWarnings("unused")
  private Set<DomainTransactionRecord> getInternalDomainTransactionRecords() {
    return domainTransactionRecords;
  }

  /** Sets the domain transaction records. This method is dedicated for Hibernate. */
  @SuppressWarnings("unused")
  private void setInternalDomainTransactionRecords(
      Set<DomainTransactionRecord> domainTransactionRecords) {
    this.domainTransactionRecords = domainTransactionRecords;
  }

  @Id
  @Column(name = "historyRevisionId")
  @Access(AccessType.PROPERTY)
  @Override
  public long getId() {
    return super.getId();
  }

  /** Returns keys to the {@link HostResource} that are the nameservers for the domain. */
  public Set<VKey<HostResource>> getNsHosts() {
    return nsHosts;
  }

  /** Returns the collection of {@link DomainDsDataHistory} instances. */
  public ImmutableSet<DomainDsDataHistory> getDsDataHistories() {
    return nullToEmptyImmutableCopy(dsDataHistories);
  }

  /**
   * The values of all the fields on the {@link DomainContent} object after the action represented
   * by this history object was executed.
   *
   * <p>Will be absent for objects created prior to the Registry 3.0 SQL migration.
   */
  public Optional<DomainContent> getDomainContent() {
    return Optional.ofNullable(domainContent);
  }

  /** The key to the {@link DomainBase} this is based off of. */
  public VKey<DomainBase> getParentVKey() {
    return VKey.create(DomainBase.class, getDomainRepoId());
  }

  public Set<GracePeriodHistory> getGracePeriodHistories() {
    return nullToEmptyImmutableCopy(gracePeriodHistories);
  }

  /** Creates a {@link VKey} instance for this entity. */
  @SuppressWarnings("unchecked")
  public VKey<DomainHistory> createVKey() {
    return (VKey<DomainHistory>) createVKey(Key.create(this));
  }

  @PostLoad
  void postLoad() {
    if (domainContent != null) {
      domainContent.nsHosts = nullToEmptyImmutableCopy(nsHosts);
      // Normally Hibernate would see that the domain fields are all null and would fill
      // domainContent with a null object. Unfortunately, the updateTimestamp is never null in SQL.
      if (domainContent.getDomainName() == null) {
        domainContent = null;
      } else {
        if (domainContent.getRepoId() == null) {
          domainContent = domainContent.asBuilder().setRepoId(parent.getName()).build();
        }
      }
    }
  }

  // In Datastore, save as a HistoryEntry object regardless of this object's type
  @Override
  public Optional<DatastoreEntity> toDatastoreEntity() {
    return Optional.of(asHistoryEntry());
  }

  /** Class to represent the composite primary key of {@link DomainHistory} entity. */
  public static class DomainHistoryId extends ImmutableObject implements Serializable {

    private String domainRepoId;

    private Long id;

    /** Hibernate requires this default constructor. */
    private DomainHistoryId() {}

    public DomainHistoryId(String domainRepoId, long id) {
      this.domainRepoId = domainRepoId;
      this.id = id;
    }

    /**
     * Returns the domain repository id.
     *
     * <p>This method is private because it is only used by Hibernate.
     */
    @SuppressWarnings("unused")
    private String getDomainRepoId() {
      return domainRepoId;
    }

    /**
     * Returns the history revision id.
     *
     * <p>This method is private because it is only used by Hibernate.
     */
    @SuppressWarnings("unused")
    private long getId() {
      return id;
    }

    /**
     * Sets the domain repository id.
     *
     * <p>This method is private because it is only used by Hibernate and should not be used
     * externally to keep immutability.
     */
    @SuppressWarnings("unused")
    private void setDomainRepoId(String domainRepoId) {
      this.domainRepoId = domainRepoId;
    }

    /**
     * Sets the history revision id.
     *
     * <p>This method is private because it is only used by Hibernate and should not be used
     * externally to keep immutability.
     */
    @SuppressWarnings("unused")
    private void setId(long id) {
      this.id = id;
    }
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  public static class Builder extends HistoryEntry.Builder<DomainHistory, DomainHistory.Builder> {

    public Builder() {}

    public Builder(DomainHistory instance) {
      super(instance);
    }

    public Builder setDomainContent(DomainContent domainContent) {
      getInstance().domainContent = domainContent;
      return this;
    }

    public Builder setDomainRepoId(String domainRepoId) {
      getInstance().parent = Key.create(DomainBase.class, domainRepoId);
      return this;
    }

    @Override
    public DomainHistory build() {
      DomainHistory instance = super.build();
      // TODO(b/171990736): Assert instance.domainContent is not null after database migration.
      // Note that we cannot assert that instance.domainContent is not null here because this
      // builder is also used to convert legacy HistoryEntry objects to DomainHistory, when
      // domainContent is not available.
      if (instance.domainContent != null) {
        instance.nsHosts = nullToEmptyImmutableCopy(instance.domainContent.nsHosts);
        instance.dsDataHistories =
            nullToEmptyImmutableCopy(instance.domainContent.getDsData()).stream()
                .map(dsData -> DomainDsDataHistory.createFrom(instance.id, dsData))
                .collect(toImmutableSet());
        instance.gracePeriodHistories =
            nullToEmptyImmutableCopy(instance.domainContent.getGracePeriods()).stream()
                .map(gracePeriod -> GracePeriodHistory.createFrom(instance.id, gracePeriod))
                .collect(toImmutableSet());
      }
      return instance;
    }
  }
}
