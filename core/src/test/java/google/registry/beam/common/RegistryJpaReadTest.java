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

package google.registry.beam.common;

import static google.registry.testing.AppEngineExtension.makeRegistrar1;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static google.registry.testing.DatabaseHelper.newContact;
import static google.registry.testing.DatabaseHelper.newRegistry;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.beam.TestPipelineExtension;
import google.registry.beam.common.RegistryJpaIO.Read;
import google.registry.model.contact.Contact;
import google.registry.model.contact.ContactBase;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DomainDsData;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.registrar.Registrar;
import google.registry.model.tld.Registry;
import google.registry.model.transfer.ContactTransferData;
import google.registry.persistence.transaction.CriteriaQueryBuilder;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RegistryJpaIO.Read}. */
public class RegistryJpaReadTest {

  private static final DateTime START_TIME = DateTime.parse("2000-01-01T00:00:00.0Z");

  private final FakeClock fakeClock = new FakeClock(START_TIME);

  @RegisterExtension
  final transient JpaIntegrationTestExtension database =
      new JpaTestExtensions.Builder()
          .withClock(fakeClock)
          .withoutCannedData()
          .buildIntegrationTestExtension();

  @RegisterExtension
  final transient TestPipelineExtension testPipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  private transient ImmutableList<Contact> contacts;

  @BeforeEach
  void beforeEach() {
    Registrar ofyRegistrar = AppEngineExtension.makeRegistrar2();
    insertInDb(ofyRegistrar);

    ImmutableList.Builder<Contact> builder = new ImmutableList.Builder<>();

    for (int i = 0; i < 3; i++) {
      Contact contact = newContact("contact_" + i);
      builder.add(contact);
    }
    contacts = builder.build();
    insertInDb(contacts);
  }

  @Test
  void readWithCriteriaQuery() {
    Read<Contact, String> read =
        RegistryJpaIO.read(
                () -> CriteriaQueryBuilder.create(Contact.class).build(), ContactBase::getContactId)
            .withCoder(StringUtf8Coder.of());
    PCollection<String> repoIds = testPipeline.apply(read);

    PAssert.that(repoIds).containsInAnyOrder("contact_0", "contact_1", "contact_2");
    testPipeline.run();
  }

  @Test
  void readWithStringQuery() {
    setupForJoinQuery();
    Read<Object[], String> read =
        RegistryJpaIO.read(
                "select d, r.emailAddress from Domain d join Registrar r on"
                    + " d.currentSponsorRegistrarId = r.registrarId where r.type = :type"
                    + " and d.deletionTime > now()",
                ImmutableMap.of("type", Registrar.Type.REAL),
                false,
                (Object[] row) -> {
                  Domain domain = (Domain) row[0];
                  String emailAddress = (String) row[1];
                  return domain.getRepoId() + "-" + emailAddress;
                })
            .withCoder(StringUtf8Coder.of());
    PCollection<String> joinedStrings = testPipeline.apply(read);

    PAssert.that(joinedStrings).containsInAnyOrder("4-COM-me@google.com");
    testPipeline.run();
  }

  @Test
  void readWithStringNativeQuery() {
    setupForJoinQuery();
    Read<Object[], String> read =
        RegistryJpaIO.read(
                "select d.repo_id, r.email_address from \"Domain\" d join \"Registrar\" r on"
                    + " d.current_sponsor_registrar_id = r.registrar_id where r.type = :type"
                    + " and d.deletion_time > now()",
                ImmutableMap.of("type", "REAL"),
                true,
                (Object[] row) -> {
                  String repoId = (String) row[0];
                  String emailAddress = (String) row[1];
                  return repoId + "-" + emailAddress;
                })
            .withCoder(StringUtf8Coder.of());
    PCollection<String> joinedStrings = testPipeline.apply(read);

    PAssert.that(joinedStrings).containsInAnyOrder("4-COM-me@google.com");
    testPipeline.run();
  }

  @Test
  void readWithStringTypedQuery() {
    setupForJoinQuery();
    Read<Domain, String> read =
        RegistryJpaIO.read(
                "select d from Domain d join Registrar r on"
                    + " d.currentSponsorRegistrarId = r.registrarId where r.type = :type"
                    + " and d.deletionTime > now()",
                ImmutableMap.of("type", Registrar.Type.REAL),
                Domain.class,
                Domain::getRepoId)
            .withCoder(StringUtf8Coder.of());
    PCollection<String> repoIds = testPipeline.apply(read);

    PAssert.that(repoIds).containsInAnyOrder("4-COM");
    testPipeline.run();
  }

  private void setupForJoinQuery() {
    Registry registry = newRegistry("com", "ABCD_APP");
    Registrar registrar =
        makeRegistrar1()
            .asBuilder()
            .setRegistrarId("registrar1")
            .setEmailAddress("me@google.com")
            .build();
    Contact contact =
        new Contact.Builder()
            .setRepoId("contactid_1")
            .setCreationRegistrarId(registrar.getRegistrarId())
            .setTransferData(new ContactTransferData.Builder().build())
            .setPersistedCurrentSponsorRegistrarId(registrar.getRegistrarId())
            .build();
    Domain domain =
        new Domain.Builder()
            .setDomainName("example.com")
            .setRepoId("4-COM")
            .setCreationRegistrarId(registrar.getRegistrarId())
            .setLastEppUpdateTime(fakeClock.nowUtc())
            .setLastEppUpdateRegistrarId(registrar.getRegistrarId())
            .setLastTransferTime(fakeClock.nowUtc())
            .setStatusValues(
                ImmutableSet.of(
                    StatusValue.CLIENT_DELETE_PROHIBITED,
                    StatusValue.SERVER_DELETE_PROHIBITED,
                    StatusValue.SERVER_TRANSFER_PROHIBITED,
                    StatusValue.SERVER_UPDATE_PROHIBITED,
                    StatusValue.SERVER_RENEW_PROHIBITED,
                    StatusValue.SERVER_HOLD))
            .setRegistrant(contact.createVKey())
            .setContacts(ImmutableSet.of())
            .setSubordinateHosts(ImmutableSet.of("ns1.example.com"))
            .setPersistedCurrentSponsorRegistrarId(registrar.getRegistrarId())
            .setRegistrationExpirationTime(fakeClock.nowUtc().plusYears(1))
            .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("password")))
            .setDsData(ImmutableSet.of(DomainDsData.create(1, 2, 3, new byte[] {0, 1, 2})))
            .setLaunchNotice(
                LaunchNotice.create("tcnid", "validatorId", START_OF_TIME, START_OF_TIME))
            .setSmdId("smdid")
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.ADD,
                    "4-COM",
                    END_OF_TIME,
                    registrar.getRegistrarId(),
                    null,
                    100L))
            .build();
    insertInDb(registry, registrar, contact, domain);
  }
}
