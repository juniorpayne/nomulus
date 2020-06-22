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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.reporting.SafeBrowsingThreat.ThreatType.MALWARE;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.SqlHelper.assertThrowForeignKeyViolation;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableSet;
import google.registry.model.EntityTestCase;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.model.transfer.ContactTransferData;
import google.registry.persistence.VKey;
import org.joda.time.LocalDate;
import org.joda.time.format.ISODateTimeFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SafeBrowsingThreat}. */
public class SafeBrowsingThreatTest extends EntityTestCase {

  private static final String REGISTRAR_ID = "registrar";
  private static final LocalDate DATE = LocalDate.parse("2020-06-10", ISODateTimeFormat.date());

  private SafeBrowsingThreat threat;
  private DomainBase domain;
  private HostResource host;
  private ContactResource registrantContact;

  public SafeBrowsingThreatTest() {
    super(true);
  }

  @BeforeEach
  public void setUp() {
    VKey<HostResource> hostVKey = VKey.createSql(HostResource.class, "host");
    VKey<ContactResource> registrantContactVKey =
        VKey.createSql(ContactResource.class, "contact_id");
    String domainRepoId = "4-TLD";
    createTld("tld");

    /** Create a domain for the purpose of testing a foreign key reference in the Threat table. */
    domain =
        new DomainBase()
            .asBuilder()
            .setCreationClientId(REGISTRAR_ID)
            .setPersistedCurrentSponsorClientId(REGISTRAR_ID)
            .setDomainName("foo.tld")
            .setRepoId(domainRepoId)
            .setNameservers(hostVKey)
            .setRegistrant(registrantContactVKey)
            .setContacts(ImmutableSet.of())
            .build();

    /** Create a contact for the purpose of testing a foreign key reference in the Domain table. */
    registrantContact =
        new ContactResource.Builder()
            .setRepoId("contact_id")
            .setCreationClientId(REGISTRAR_ID)
            .setTransferData(new ContactTransferData.Builder().build())
            .setPersistedCurrentSponsorClientId(REGISTRAR_ID)
            .build();

    /** Create a host for the purpose of testing a foreign key reference in the Domain table. */
    host =
        new HostResource.Builder()
            .setRepoId("host")
            .setHostName("ns1.example.com")
            .setCreationClientId(REGISTRAR_ID)
            .setPersistedCurrentSponsorClientId(REGISTRAR_ID)
            .build();

    threat =
        new SafeBrowsingThreat.Builder()
            .setThreatType(MALWARE)
            .setCheckDate(DATE)
            .setDomainName("foo.tld")
            .setDomainRepoId(domainRepoId)
            .setRegistrarId(REGISTRAR_ID)
            .build();
  }

  @Test
  public void testPersistence() {
    saveRegistrar(REGISTRAR_ID);

    jpaTm()
        .transact(
            () -> {
              jpaTm().saveNew(registrantContact);
              jpaTm().saveNew(domain);
              jpaTm().saveNew(host);
              jpaTm().saveNew(threat);
            });

    VKey<SafeBrowsingThreat> threatVKey = VKey.createSql(SafeBrowsingThreat.class, threat.getId());
    SafeBrowsingThreat persistedThreat = jpaTm().transact(() -> jpaTm().load(threatVKey));
    threat.id = persistedThreat.id;
    assertThat(threat).isEqualTo(persistedThreat);
  }

  @Test
  public void testThreatForeignKeyConstraints() {
    assertThrowForeignKeyViolation(
        () -> {
          jpaTm()
              .transact(
                  () -> {
                    // Persist the threat without the associated registrar.
                    jpaTm().saveNew(host);
                    jpaTm().saveNew(registrantContact);
                    jpaTm().saveNew(domain);
                    jpaTm().saveNew(threat);
                  });
        });

    saveRegistrar(REGISTRAR_ID);

    assertThrowForeignKeyViolation(
        () -> {
          jpaTm()
              .transact(
                  () -> {
                    // Persist the threat without the associated domain.
                    jpaTm().saveNew(registrantContact);
                    jpaTm().saveNew(host);
                    jpaTm().saveNew(threat);
                  });
        });
  }

  @Test
  public void testFailure_threatsWithNullFields() {
    assertThrows(
        IllegalArgumentException.class, () -> threat.asBuilder().setRegistrarId(null).build());

    assertThrows(
        IllegalArgumentException.class, () -> threat.asBuilder().setDomainName(null).build());

    assertThrows(
        IllegalArgumentException.class, () -> threat.asBuilder().setCheckDate(null).build());

    assertThrows(
        IllegalArgumentException.class, () -> threat.asBuilder().setThreatType(null).build());

    assertThrows(
        IllegalArgumentException.class, () -> threat.asBuilder().setDomainRepoId(null).build());
  }
}
