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

package google.registry.model.host;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import google.registry.model.EppResource.ForeignKeyedEppResource;
import google.registry.model.annotations.ExternalMessagingName;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.replay.DatastoreAndSqlEntity;
import google.registry.persistence.VKey;
import google.registry.persistence.WithStringVKey;
import google.registry.util.DomainNameUtils;
import javax.persistence.Access;
import javax.persistence.AccessType;

/**
 * A persistable Host resource including mutable and non-mutable fields.
 *
 * <p>The {@link javax.persistence.Id} of the HostResource is the repoId.
 */
@ReportedOn
@Entity
@javax.persistence.Entity(name = "Host")
@javax.persistence.Table(
    name = "Host",
    /**
     * A gin index defined on the inet_addresses field ({@link HostBase#inetAddresses} cannot be
     * declared here because JPA/Hibernate does not support index type specification. As a result,
     * the hibernate-generated schema (which is for reference only) does not have this index.
     *
     * <p>There are Hibernate-specific solutions for adding this index to Hibernate's domain model.
     * We could either declare the index in hibernate.cfg.xml or add it to the {@link
     * org.hibernate.cfg.Configuration} instance for {@link SessionFactory} instantiation (which
     * would prevent us from using JPA standard bootstrapping). For now, there is no obvious benefit
     * doing either.
     */
    indexes = {
      @javax.persistence.Index(columnList = "hostName"),
      @javax.persistence.Index(columnList = "creationTime"),
      @javax.persistence.Index(columnList = "deletionTime"),
      @javax.persistence.Index(columnList = "currentSponsorRegistrarId")
    })
@ExternalMessagingName("host")
@WithStringVKey
@Access(AccessType.FIELD) // otherwise it'll use the default if the repoId (property)
public class HostResource extends HostBase
    implements DatastoreAndSqlEntity, ForeignKeyedEppResource {

  @Override
  @javax.persistence.Id
  @Access(AccessType.PROPERTY) // to tell it to use the non-default property-as-ID
  public String getRepoId() {
    return super.getRepoId();
  }

  @Override
  public VKey<HostResource> createVKey() {
    return VKey.create(HostResource.class, getRepoId(), Key.create(this));
  }

  @Override
  public void beforeSqlSaveOnReplay() {
    fullyQualifiedHostName = DomainNameUtils.canonicalizeDomainName(fullyQualifiedHostName);
  }

  @Override
  public void beforeDatastoreSaveOnReplay() {
    saveIndexesToDatastore();
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link HostResource}, since it is immutable. */
  public static class Builder extends HostBase.Builder<HostResource, Builder> {
    public Builder() {}

    private Builder(HostResource instance) {
      super(instance);
    }

    public Builder copyFrom(HostBase hostBase) {
      return this.setCreationRegistrarId(hostBase.getCreationRegistrarId())
          .setCreationTime(hostBase.getCreationTime())
          .setDeletionTime(hostBase.getDeletionTime())
          .setHostName(hostBase.getHostName())
          .setInetAddresses(hostBase.getInetAddresses())
          .setLastTransferTime(hostBase.getLastTransferTime())
          .setLastSuperordinateChange(hostBase.getLastSuperordinateChange())
          .setLastEppUpdateRegistrarId(hostBase.getLastEppUpdateRegistrarId())
          .setLastEppUpdateTime(hostBase.getLastEppUpdateTime())
          .setPersistedCurrentSponsorRegistrarId(hostBase.getPersistedCurrentSponsorRegistrarId())
          .setRepoId(hostBase.getRepoId())
          .setSuperordinateDomain(hostBase.getSuperordinateDomain())
          .setStatusValues(hostBase.getStatusValues());
    }
  }
}
