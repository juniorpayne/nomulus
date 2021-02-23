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

package google.registry.schema.tld;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static google.registry.testing.DatabaseHelper.newRegistry;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.joda.money.CurrencyUnit.JPY;
import static org.joda.money.CurrencyUnit.USD;
import static org.joda.time.Duration.standardDays;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.Key;
import google.registry.model.registry.label.PremiumList;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.TestCacheExtension;
import java.math.BigDecimal;
import java.util.Optional;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link PremiumListSqlDao}. */
public class PremiumListSqlDaoTest {

  private final FakeClock fakeClock = new FakeClock();

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .enableJpaEntityCoverageCheck(true)
          .withClock(fakeClock)
          .build();

  // Set long persist times on caches so they can be tested (cache times default to 0 in tests).
  @RegisterExtension
  public final TestCacheExtension testCacheExtension =
      new TestCacheExtension.Builder()
          .withPremiumListsCache(standardDays(1))
          .withPremiumListEntriesCache(standardDays(1))
          .build();

  private ImmutableMap<String, BigDecimal> testPrices;

  private PremiumList testList;

  @BeforeEach
  void beforeEach() {
    testPrices =
        ImmutableMap.of(
            "silver",
            BigDecimal.valueOf(10.23),
            "gold",
            BigDecimal.valueOf(1305.47),
            "palladium",
            BigDecimal.valueOf(1552.78));
    testList =
        new PremiumList.Builder()
            .setName("testname")
            .setCurrency(USD)
            .setLabelsToPrices(testPrices)
            .setCreationTime(fakeClock.nowUtc())
            .build();
  }

  @Test
  void saveNew_worksSuccessfully() {
    PremiumListSqlDao.save(testList);
    jpaTm()
        .transact(
            () -> {
              Optional<PremiumList> persistedListOpt =
                  PremiumListSqlDao.getLatestRevision("testname");
              assertThat(persistedListOpt).isPresent();
              PremiumList persistedList = persistedListOpt.get();
              assertThat(persistedList.getLabelsToPrices()).containsExactlyEntriesIn(testPrices);
              assertThat(persistedList.getCreationTime()).isEqualTo(fakeClock.nowUtc());
            });
  }

  @Test
  void update_worksSuccessfully() {
    PremiumListSqlDao.save(testList);
    Optional<PremiumList> persistedList = PremiumListSqlDao.getLatestRevision("testname");
    assertThat(persistedList).isPresent();
    long firstRevisionId = persistedList.get().getRevisionId();
    PremiumListSqlDao.save(
        new PremiumList.Builder()
            .setName("testname")
            .setCurrency(USD)
            .setLabelsToPrices(
                ImmutableMap.of(
                    "save",
                    BigDecimal.valueOf(55343.12),
                    "new",
                    BigDecimal.valueOf(0.01),
                    "silver",
                    BigDecimal.valueOf(30.03)))
            .setCreationTime(fakeClock.nowUtc())
            .build());
    jpaTm()
        .transact(
            () -> {
              Optional<PremiumList> savedListOpt = PremiumListSqlDao.getLatestRevision("testname");
              assertThat(savedListOpt).isPresent();
              PremiumList savedList = savedListOpt.get();
              assertThat(savedList.getLabelsToPrices())
                  .containsExactlyEntriesIn(
                      ImmutableMap.of(
                          "save",
                          BigDecimal.valueOf(55343.12),
                          "new",
                          BigDecimal.valueOf(0.01),
                          "silver",
                          BigDecimal.valueOf(30.03)));
              assertThat(savedList.getCreationTime()).isEqualTo(fakeClock.nowUtc());
              assertThat(savedList.getRevisionId()).isGreaterThan(firstRevisionId);
              assertThat(savedList.getCreationTime()).isEqualTo(fakeClock.nowUtc());
            });
  }

  @Test
  void checkExists_worksSuccessfully() {
    assertThat(PremiumListSqlDao.getLatestRevision("testname")).isEmpty();
    PremiumListSqlDao.save(testList);
    assertThat(PremiumListSqlDao.getLatestRevision("testname")).isPresent();
  }

  @Test
  void getLatestRevision_returnsEmptyForNonexistentList() {
    assertThat(PremiumListSqlDao.getLatestRevision("nonexistentlist")).isEmpty();
  }

  @Test
  void getLatestRevision_worksSuccessfully() {
    PremiumListSqlDao.save(
        new PremiumList.Builder()
            .setName("list1")
            .setCurrency(JPY)
            .setLabelsToPrices(ImmutableMap.of("wrong", BigDecimal.valueOf(1000.50)))
            .setCreationTime(fakeClock.nowUtc())
            .build());
    PremiumListSqlDao.save(
        new PremiumList.Builder()
            .setName("list1")
            .setCurrency(JPY)
            .setLabelsToPrices(testPrices)
            .setCreationTime(fakeClock.nowUtc())
            .build());
    jpaTm()
        .transact(
            () -> {
              Optional<PremiumList> persistedList = PremiumListSqlDao.getLatestRevision("list1");
              assertThat(persistedList).isPresent();
              assertThat(persistedList.get().getName()).isEqualTo("list1");
              assertThat(persistedList.get().getCurrency()).isEqualTo(JPY);
              assertThat(persistedList.get().getLabelsToPrices())
                  .containsExactlyEntriesIn(testPrices);
            });
  }

  @Test
  void getPremiumPrice_worksSuccessfully() {
    persistResource(
        newRegistry("foobar", "FOOBAR")
            .asBuilder()
            .setPremiumListKey(
                Key.create(
                    getCrossTldKey(),
                    google.registry.model.registry.label.PremiumList.class,
                    "premlist"))
            .build());
    PremiumListSqlDao.save(
        new PremiumList.Builder()
            .setName("premlist")
            .setCurrency(USD)
            .setLabelsToPrices(testPrices)
            .setCreationTime(fakeClock.nowUtc())
            .build());
    assertThat(PremiumListSqlDao.getPremiumPrice("premlist", "silver"))
        .hasValue(Money.of(USD, 10.23));
    assertThat(PremiumListSqlDao.getPremiumPrice("premlist", "gold"))
        .hasValue(Money.of(USD, 1305.47));
    assertThat(PremiumListSqlDao.getPremiumPrice("premlist", "zirconium")).isEmpty();
  }

  @Test
  void testGetPremiumPrice_worksForJPY() {
    persistResource(
        newRegistry("foobar", "FOOBAR")
            .asBuilder()
            .setPremiumListKey(
                Key.create(
                    getCrossTldKey(),
                    google.registry.model.registry.label.PremiumList.class,
                    "premlist"))
            .build());
    PremiumListSqlDao.save(
        new PremiumList.Builder()
            .setName("premlist")
            .setCurrency(JPY)
            .setLabelsToPrices(
                ImmutableMap.of(
                    "silver",
                    BigDecimal.valueOf(10.00),
                    "gold",
                    BigDecimal.valueOf(1000.0),
                    "palladium",
                    BigDecimal.valueOf(15000)))
            .setCreationTime(fakeClock.nowUtc())
            .build());
    assertThat(PremiumListSqlDao.getPremiumPrice("premlist", "silver")).hasValue(moneyOf(JPY, 10));
    assertThat(PremiumListSqlDao.getPremiumPrice("premlist", "gold")).hasValue(moneyOf(JPY, 1000));
    assertThat(PremiumListSqlDao.getPremiumPrice("premlist", "palladium"))
        .hasValue(moneyOf(JPY, 15000));
  }

  @Test
  void test_savePremiumList_clearsCache() {
    assertThat(PremiumListSqlDao.premiumListCache.getIfPresent("testname")).isNull();
    PremiumListSqlDao.save(testList);
    PremiumList pl = PremiumListSqlDao.getLatestRevision("testname").get();
    assertThat(PremiumListSqlDao.premiumListCache.getIfPresent("testname").get()).isEqualTo(pl);
    transactIfJpaTm(() -> PremiumListSqlDao.save("testname", ImmutableList.of("test,USD 1")));
    assertThat(PremiumListSqlDao.premiumListCache.getIfPresent("testname")).isNull();
  }

  private static Money moneyOf(CurrencyUnit unit, double amount) {
    return Money.of(unit, BigDecimal.valueOf(amount).setScale(unit.getDecimalPlaces()));
  }
}
