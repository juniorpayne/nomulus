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

package google.registry.tools.server;

import static com.google.appengine.repackaged.com.google.common.collect.Sets.difference;
import static com.google.common.base.Predicates.in;
import static com.google.common.base.Predicates.instanceOf;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Multimaps.filterKeys;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static java.util.Arrays.asList;

import com.google.appengine.api.datastore.Entity;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.googlecode.objectify.Key;
import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.host.HostResource;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.ForeignKeyIndex.ForeignKeyContactIndex;
import google.registry.model.index.ForeignKeyIndex.ForeignKeyDomainIndex;
import google.registry.model.index.ForeignKeyIndex.ForeignKeyHostIndex;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeResponse;
import google.registry.testing.TmOverrideExtension;
import google.registry.testing.mapreduce.MapreduceTestCase;
import java.util.stream.Stream;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link KillAllEppResourcesAction}. */
class KillAllEppResourcesActionTest extends MapreduceTestCase<KillAllEppResourcesAction> {

  @RegisterExtension
  @Order(Order.DEFAULT - 1)
  TmOverrideExtension tmOverrideExtension = TmOverrideExtension.withOfy();

  private static final ImmutableSet<String> AFFECTED_KINDS =
      Stream.of(
              EppResourceIndex.class,
              ForeignKeyContactIndex.class,
              ForeignKeyDomainIndex.class,
              ForeignKeyHostIndex.class,
              DomainBase.class,
              ContactResource.class,
              HostResource.class,
              HistoryEntry.class,
              PollMessage.class,
              BillingEvent.OneTime.class,
              BillingEvent.Recurring.class)
          .map(Key::getKind)
          .collect(toImmutableSet());

  private void runMapreduce() throws Exception {
    action = new KillAllEppResourcesAction();
    action.mrRunner = makeDefaultRunner();
    action.response = new FakeResponse();
    action.run();
    executeTasksUntilEmpty("mapreduce");
  }

  private static final ImmutableMap<Class<? extends EppResource>, HistoryEntry.Type>
      HISTORY_ENTRY_CREATE_TYPES =
          ImmutableMap.of(
              DomainBase.class,
              HistoryEntry.Type.DOMAIN_CREATE,
              ContactResource.class,
              HistoryEntry.Type.CONTACT_CREATE,
              HostResource.class,
              HistoryEntry.Type.HOST_CREATE);

  @Test
  void testKill() throws Exception {
    createTld("tld1");
    createTld("tld2");
    for (EppResource resource :
        asList(
            persistActiveDomain("foo.tld1"),
            persistActiveDomain("foo.tld2"),
            persistActiveContact("foo"),
            persistActiveContact("foo"),
            persistActiveHost("ns.foo.tld1"),
            persistActiveHost("ns.foo.tld2"))) {
      HistoryEntry history =
          HistoryEntry.createBuilderForResource(resource)
              .setRegistrarId(resource.getCreationRegistrarId())
              .setModificationTime(resource.getCreationTime())
              .setType(HISTORY_ENTRY_CREATE_TYPES.get(resource.getClass()))
              .build();
      ImmutableList.Builder<ImmutableObject> descendantBuilder =
          new ImmutableList.Builder<ImmutableObject>()
              .add(
                  history,
                  new PollMessage.OneTime.Builder()
                      .setParent(history)
                      .setRegistrarId("")
                      .setEventTime(START_OF_TIME)
                      .build(),
                  new PollMessage.Autorenew.Builder()
                      .setParent(history)
                      .setRegistrarId("")
                      .setEventTime(START_OF_TIME)
                      .build());
      if (history instanceof DomainHistory) {
        descendantBuilder.add(
            new BillingEvent.OneTime.Builder()
                .setParent((DomainHistory) history)
                .setBillingTime(START_OF_TIME)
                .setEventTime(START_OF_TIME)
                .setRegistrarId("")
                .setTargetId("")
                .setReason(Reason.CREATE)
                .setPeriodYears(1)
                .setCost(Money.of(CurrencyUnit.USD, 1))
                .build(),
            new BillingEvent.Recurring.Builder()
                .setParent((DomainHistory) history)
                .setEventTime(START_OF_TIME)
                .setRegistrarId("")
                .setTargetId("")
                .setReason(Reason.RENEW)
                .build());
      }
      descendantBuilder.build().forEach(DatabaseHelper::persistResource);
    }
    ImmutableMultimap<String, Object> beforeContents = getDatastoreContents();
    assertThat(beforeContents.keySet()).containsAtLeastElementsIn(AFFECTED_KINDS);
    assertThat(difference(beforeContents.keySet(), AFFECTED_KINDS)).isNotEmpty();
    runMapreduce();
    auditedOfy().clearSessionCache();
    ImmutableMultimap<String, Object> afterContents = getDatastoreContents();
    assertThat(afterContents.keySet()).containsNoneIn(AFFECTED_KINDS);
    assertThat(afterContents)
        .containsExactlyEntriesIn(filterKeys(beforeContents, not(in(AFFECTED_KINDS))));
  }

  private ImmutableMultimap<String, Object> getDatastoreContents() {
    ImmutableMultimap.Builder<String, Object> contentsBuilder = new ImmutableMultimap.Builder<>();
    // Filter out raw Entity objects created by the mapreduce.
    for (Object obj : Iterables.filter(auditedOfy().load(), not(instanceOf(Entity.class)))) {
      contentsBuilder.put(Key.getKind(obj.getClass()), obj);
    }
    return contentsBuilder.build();
  }
}
