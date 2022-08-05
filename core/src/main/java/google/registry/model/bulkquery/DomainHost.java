// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.bulkquery;

import com.google.common.base.Objects;
import google.registry.model.host.Host;
import google.registry.persistence.VKey;
import java.io.Serializable;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;

/** A name server host of a domain. Please refer to {@link BulkQueryEntities} for usage. */
@Entity
@Access(AccessType.FIELD)
@IdClass(DomainHost.class)
public class DomainHost implements Serializable {

  @Id private String domainRepoId;

  @Id private String hostRepoId;

  DomainHost() {}

  public String getDomainRepoId() {
    return domainRepoId;
  }

  public VKey<Host> getHostVKey() {
    return VKey.create(Host.class, hostRepoId);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DomainHost)) {
      return false;
    }
    DomainHost that = (DomainHost) o;
    return Objects.equal(domainRepoId, that.domainRepoId)
        && Objects.equal(hostRepoId, that.hostRepoId);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(domainRepoId, hostRepoId);
  }
}
