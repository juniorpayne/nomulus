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

package google.registry.model.contact;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.EntitySubclass;
import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.UnsafeSerializable;
import google.registry.model.contact.ContactHistory.ContactHistoryId;
import google.registry.model.replay.DatastoreEntity;
import google.registry.model.replay.SqlEntity;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import java.io.Serializable;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.PostLoad;

/**
 * A persisted history entry representing an EPP modification to a contact.
 *
 * <p>In addition to the general history fields (e.g. action time, registrar ID) we also persist a
 * copy of the contact entity at this point in time. We persist a raw {@link ContactBase} so that
 * the foreign-keyed fields in that class can refer to this object.
 *
 * <p>This class is only marked as a Datastore entity subclass and registered with Objectify so that
 * when building it its ID can be auto-populated by Objectify. It is converted to its superclass
 * {@link HistoryEntry} when persisted to Datastore using {@link
 * google.registry.persistence.transaction.TransactionManager}.
 */
@Entity
@javax.persistence.Table(
    indexes = {
      @javax.persistence.Index(columnList = "creationTime"),
      @javax.persistence.Index(columnList = "historyRegistrarId"),
      @javax.persistence.Index(columnList = "historyType"),
      @javax.persistence.Index(columnList = "historyModificationTime")
    })
@EntitySubclass
@Access(AccessType.FIELD)
@IdClass(ContactHistoryId.class)
public class ContactHistory extends HistoryEntry implements SqlEntity, UnsafeSerializable {

  // Store ContactBase instead of ContactResource so we don't pick up its @Id
  // Nullable for the sake of pre-Registry-3.0 history objects
  @DoNotCompare @Nullable ContactBase contactBase;

  @Id
  @Access(AccessType.PROPERTY)
  public String getContactRepoId() {
    // We need to handle null case here because Hibernate sometimes accesses this method before
    // parent gets initialized
    return parent == null ? null : parent.getName();
  }

  /** This method is private because it is only used by Hibernate. */
  @SuppressWarnings("unused")
  private void setContactRepoId(String contactRepoId) {
    parent = Key.create(ContactResource.class, contactRepoId);
  }

  @Id
  @Column(name = "historyRevisionId")
  @Access(AccessType.PROPERTY)
  @Override
  public long getId() {
    return super.getId();
  }

  /**
   * The values of all the fields on the {@link ContactBase} object after the action represented by
   * this history object was executed.
   *
   * <p>Will be absent for objects created prior to the Registry 3.0 SQL migration.
   */
  public Optional<ContactBase> getContactBase() {
    return Optional.ofNullable(contactBase);
  }

  /** The key to the {@link ContactResource} this is based off of. */
  public VKey<ContactResource> getParentVKey() {
    return VKey.create(ContactResource.class, getContactRepoId());
  }

  /** Creates a {@link VKey} instance for this entity. */
  @SuppressWarnings("unchecked")
  public VKey<ContactHistory> createVKey() {
    return (VKey<ContactHistory>) createVKey(Key.create(this));
  }

  @Override
  public Optional<? extends EppResource> getResourceAtPointInTime() {
    return getContactBase()
        .map(contactBase -> new ContactResource.Builder().copyFrom(contactBase).build());
  }

  @PostLoad
  void postLoad() {
    // Normally Hibernate would see that the contact fields are all null and would fill contactBase
    // with a null object. Unfortunately, the updateTimestamp is never null in SQL.
    if (contactBase != null && contactBase.getContactId() == null) {
      contactBase = null;
    }
    if (contactBase != null && contactBase.getRepoId() == null) {
      // contactBase hasn't been fully constructed yet, so it's ok to go in and mutate it.  Though
      // the use of the Builder is not necessarily problematic in this case, this is still safer as
      // the Builder can do things like comparisons that compute the hash code.
      contactBase.setRepoId(parent.getName());
    }
  }

  // In Datastore, save as a HistoryEntry object regardless of this object's type
  @Override
  public Optional<DatastoreEntity> toDatastoreEntity() {
    return Optional.of(asHistoryEntry());
  }

  /** Class to represent the composite primary key of {@link ContactHistory} entity. */
  public static class ContactHistoryId extends ImmutableObject implements Serializable {

    private String contactRepoId;

    private Long id;

    /** Hibernate requires this default constructor. */
    private ContactHistoryId() {}

    public ContactHistoryId(String contactRepoId, long id) {
      this.contactRepoId = contactRepoId;
      this.id = id;
    }

    /**
     * Returns the contact repository id.
     *
     * <p>This method is private because it is only used by Hibernate.
     */
    @SuppressWarnings("unused")
    private String getContactRepoId() {
      return contactRepoId;
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
     * Sets the contact repository id.
     *
     * <p>This method is private because it is only used by Hibernate and should not be used
     * externally to keep immutability.
     */
    @SuppressWarnings("unused")
    private void setContactRepoId(String contactRepoId) {
      this.contactRepoId = contactRepoId;
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

  public static class Builder extends HistoryEntry.Builder<ContactHistory, ContactHistory.Builder> {

    public Builder() {}

    public Builder(ContactHistory instance) {
      super(instance);
    }

    public Builder setContact(@Nullable ContactBase contactBase) {
      // Nullable for the sake of pre-Registry-3.0 history objects
      if (contactBase == null) {
        return this;
      }
      getInstance().contactBase = contactBase;
      return super.setParent(contactBase);
    }

    public Builder setContactRepoId(String contactRepoId) {
      getInstance().parent = Key.create(ContactResource.class, contactRepoId);
      return this;
    }

    public Builder wipeOutPii() {
      getInstance().contactBase =
          getInstance().getContactBase().get().asBuilder().wipeOut().build();
      return this;
    }
  }
}
