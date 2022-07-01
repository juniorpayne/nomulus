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

package google.registry.model.tld.label;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatabaseHelper.newRegistry;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.joda.money.CurrencyUnit.JPY;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import google.registry.persistence.transaction.TransactionManagerFactory;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.TestCacheExtension;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link PremiumListDao}. */
public class PremiumListDaoTest {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final FakeClock fakeClock = new FakeClock();

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withCloudSql()
          .enableJpaEntityCoverageCheck(true)
          .withClock(fakeClock)
          .build();

  // Set long persist times on caches so they can be tested (cache times default to 0 in tests).
  @RegisterExtension
  public final TestCacheExtension testCacheExtension =
      new TestCacheExtension.Builder().withPremiumListsCache(Duration.ofDays(1)).build();

  private static final ImmutableMap<String, BigDecimal> TEST_PRICES =
      ImmutableMap.of(
          "silver",
          BigDecimal.valueOf(10.23),
          "gold",
          BigDecimal.valueOf(1305.47),
          "palladium",
          BigDecimal.valueOf(1552.78));

  private PremiumList testList;

  @BeforeEach
  void beforeEach() {
    testList =
        new PremiumList.Builder()
            .setName("testname")
            .setCurrency(USD)
            .setLabelsToPrices(TEST_PRICES)
            .setCreationTimestamp(fakeClock.nowUtc())
            .build();
  }

  @Test
  void saveNew_worksSuccessfully() {
    PremiumListDao.save(testList);
    jpaTm()
        .transact(
            () -> {
              Optional<PremiumList> persistedListOpt = PremiumListDao.getLatestRevision("testname");
              assertThat(persistedListOpt).isPresent();
              PremiumList persistedList = persistedListOpt.get();
              assertThat(persistedList.getLabelsToPrices()).containsExactlyEntriesIn(TEST_PRICES);
              assertThat(persistedList.getCreationTimestamp()).isEqualTo(fakeClock.nowUtc());
            });
  }

  @Test
  void update_worksSuccessfully() {
    PremiumListDao.save(testList);
    Optional<PremiumList> persistedList = PremiumListDao.getLatestRevision("testname");
    assertThat(persistedList).isPresent();
    long firstRevisionId = persistedList.get().getRevisionId();
    PremiumListDao.save(
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
            .setCreationTimestamp(fakeClock.nowUtc())
            .build());
    jpaTm()
        .transact(
            () -> {
              Optional<PremiumList> savedListOpt = PremiumListDao.getLatestRevision("testname");
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
              assertThat(savedList.getCreationTimestamp()).isEqualTo(fakeClock.nowUtc());
              assertThat(savedList.getRevisionId()).isGreaterThan(firstRevisionId);
            });
  }

  @Test
  void checkExists_worksSuccessfully() {
    assertThat(PremiumListDao.getLatestRevision("testname")).isEmpty();
    PremiumListDao.save(testList);
    assertThat(PremiumListDao.getLatestRevision("testname")).isPresent();
  }

  @Test
  void getLatestRevision_returnsEmptyForNonexistentList() {
    assertThat(PremiumListDao.getLatestRevision("nonexistentlist")).isEmpty();
  }

  @Test
  void getLatestRevision_worksSuccessfully() {
    PremiumListDao.save(
        new PremiumList.Builder()
            .setName("list1")
            .setCurrency(USD)
            .setLabelsToPrices(ImmutableMap.of("wrong", BigDecimal.valueOf(1000.50)))
            .setCreationTimestamp(fakeClock.nowUtc())
            .build());
    PremiumListDao.save(
        new PremiumList.Builder()
            .setName("list1")
            .setCurrency(USD)
            .setLabelsToPrices(TEST_PRICES)
            .setCreationTimestamp(fakeClock.nowUtc())
            .build());
    jpaTm()
        .transact(
            () -> {
              Optional<PremiumList> persistedList = PremiumListDao.getLatestRevision("list1");
              assertThat(persistedList).isPresent();
              assertThat(persistedList.get().getName()).isEqualTo("list1");
              assertThat(persistedList.get().getCurrency()).isEqualTo(USD);
              assertThat(persistedList.get().getLabelsToPrices())
                  .containsExactlyEntriesIn(TEST_PRICES);
            });
  }

  @Test
  void getLabelsToPrices_worksForJpy() {
    PremiumListDao.save(
        new PremiumList.Builder()
            .setName("list1")
            .setCurrency(JPY)
            .setLabelsToPrices(TEST_PRICES)
            .setCreationTimestamp(fakeClock.nowUtc())
            .build());
    jpaTm()
        .transact(
            () -> {
              PremiumList premiumList = PremiumListDao.getLatestRevision("list1").get();
              assertThat(premiumList.getLabelsToPrices())
                  .containsExactly(
                      "silver",
                      BigDecimal.valueOf(10),
                      "gold",
                      BigDecimal.valueOf(1305),
                      "palladium",
                      BigDecimal.valueOf(1553));
            });
  }

  @Test
  void getPremiumPrice_worksSuccessfully() {
    PremiumList premiumList =
        PremiumListDao.save(
            new PremiumList.Builder()
                .setName("premlist")
                .setCurrency(USD)
                .setLabelsToPrices(TEST_PRICES)
                .setCreationTimestamp(fakeClock.nowUtc())
                .build());
    persistResource(
        newRegistry("foobar", "FOOBAR").asBuilder().setPremiumList(premiumList).build());
    assertThat(PremiumListDao.getPremiumPrice("premlist", "silver")).hasValue(Money.of(USD, 10.23));
    assertThat(PremiumListDao.getPremiumPrice("premlist", "gold")).hasValue(Money.of(USD, 1305.47));
    assertThat(PremiumListDao.getPremiumPrice("premlist", "zirconium")).isEmpty();
  }

  @Test
  void testGetPremiumPrice_worksForJPY() {
    PremiumList premiumList =
        PremiumListDao.save(
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
                .setCreationTimestamp(fakeClock.nowUtc())
                .build());
    persistResource(
        newRegistry("foobar", "FOOBAR").asBuilder().setPremiumList(premiumList).build());
    assertThat(PremiumListDao.getPremiumPrice("premlist", "silver")).hasValue(moneyOf(JPY, 10));
    assertThat(PremiumListDao.getPremiumPrice("premlist", "gold")).hasValue(moneyOf(JPY, 1000));
    assertThat(PremiumListDao.getPremiumPrice("premlist", "palladium"))
        .hasValue(moneyOf(JPY, 15000));
  }

  @Test
  void testSave_throwsOnEmptyInputData() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> PremiumListDao.save("test-list", CurrencyUnit.GBP, ImmutableList.of()));
    assertThat(thrown).hasMessageThat().isEqualTo("New premium list data cannot be empty");
  }

  @Test
  void test_savePremiumList_clearsCache() {
    assertThat(PremiumListDao.premiumListCache.getIfPresent("testname")).isNull();
    PremiumListDao.save(testList);
    PremiumList pl = PremiumListDao.getLatestRevision("testname").get();
    assertThat(PremiumListDao.premiumListCache.getIfPresent("testname")).hasValue(pl);
    TransactionManagerFactory.tm()
        .transact(() -> PremiumListDao.save("testname", USD, ImmutableList.of("test,USD 1")));
    assertThat(PremiumListDao.premiumListCache.getIfPresent("testname")).isNull();
  }

  @Test
  void testSave_largeSize_savedQuickly() {
    Stopwatch stopwatch = Stopwatch.createStarted();
    ImmutableMap<String, BigDecimal> prices =
        IntStream.range(0, 20000).boxed().collect(toImmutableMap(String::valueOf, BigDecimal::new));
    PremiumList list =
        new PremiumList.Builder()
            .setName("testname")
            .setCurrency(USD)
            .setLabelsToPrices(prices)
            .setCreationTimestamp(fakeClock.nowUtc())
            .build();
    PremiumListDao.save(list);
    long duration = stopwatch.stop().elapsed(TimeUnit.MILLISECONDS);
    if (duration >= 6000) {
      // Don't fail directly since we can't rely on what sort of machines the test is running on
      logger.atSevere().log(
          "Expected premium list update to take 2-3 seconds but it took %d ms", duration);
    }
  }

  private static Money moneyOf(CurrencyUnit unit, double amount) {
    return Money.of(unit, BigDecimal.valueOf(amount).setScale(unit.getDecimalPlaces()));
  }
}
