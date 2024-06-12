// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.common;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.FeatureFlag.FeatureStatus.ACTIVE;
import static google.registry.model.common.FeatureFlag.FeatureStatus.INACTIVE;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.EntityTestCase;
import google.registry.model.common.FeatureFlag.FeatureFlagNotFoundException;
import google.registry.model.common.FeatureFlag.FeatureStatus;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link FeatureFlag}. */
public class FeatureFlagTest extends EntityTestCase {

  public FeatureFlagTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @Test
  void testSuccess_persistence() {
    FeatureFlag featureFlag =
        new FeatureFlag.Builder()
            .setFeatureName("testFlag")
            .setStatus(
                ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                    .put(START_OF_TIME, INACTIVE)
                    .put(DateTime.now(UTC).plusWeeks(8), ACTIVE)
                    .build())
            .build();
    persistResource(featureFlag);
    FeatureFlag flagFromDb = loadByEntity(featureFlag);
    assertThat(featureFlag).isEqualTo(flagFromDb);
  }

  @Test
  void testSuccess_getSingleFlag() {
    FeatureFlag featureFlag =
        new FeatureFlag.Builder()
            .setFeatureName("testFlag")
            .setStatus(
                ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                    .put(START_OF_TIME, INACTIVE)
                    .put(DateTime.now(UTC).plusWeeks(8), ACTIVE)
                    .build())
            .build();
    persistResource(featureFlag);
    assertThat(FeatureFlag.get("testFlag")).isEqualTo(featureFlag);
  }

  @Test
  void testSuccess_getMultipleFlags() {
    FeatureFlag featureFlag1 =
        persistResource(
            new FeatureFlag.Builder()
                .setFeatureName("testFlag1")
                .setStatus(
                    ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                        .put(START_OF_TIME, INACTIVE)
                        .put(DateTime.now(UTC).plusWeeks(8), ACTIVE)
                        .build())
                .build());
    FeatureFlag featureFlag2 =
        persistResource(
            new FeatureFlag.Builder()
                .setFeatureName("testFlag2")
                .setStatus(
                    ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                        .put(START_OF_TIME, INACTIVE)
                        .put(DateTime.now(UTC).plusWeeks(3), INACTIVE)
                        .build())
                .build());
    FeatureFlag featureFlag3 =
        persistResource(
            new FeatureFlag.Builder()
                .setFeatureName("testFlag3")
                .setStatus(
                    ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                        .put(START_OF_TIME, INACTIVE)
                        .build())
                .build());
    ImmutableSet<FeatureFlag> featureFlags =
        FeatureFlag.get(ImmutableSet.of("testFlag1", "testFlag2", "testFlag3"));
    assertThat(featureFlags.size()).isEqualTo(3);
    assertThat(featureFlags).containsExactly(featureFlag1, featureFlag2, featureFlag3);
  }

  @Test
  void testFailure_getMultipleFlagsOneMissing() {
    persistResource(
        new FeatureFlag.Builder()
            .setFeatureName("testFlag1")
            .setStatus(
                ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                    .put(START_OF_TIME, INACTIVE)
                    .put(DateTime.now(UTC).plusWeeks(8), ACTIVE)
                    .build())
            .build());
    persistResource(
        new FeatureFlag.Builder()
            .setFeatureName("testFlag2")
            .setStatus(
                ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                    .put(START_OF_TIME, INACTIVE)
                    .put(DateTime.now(UTC).plusWeeks(3), INACTIVE)
                    .build())
            .build());
    FeatureFlagNotFoundException thrown =
        assertThrows(
            FeatureFlagNotFoundException.class,
            () -> FeatureFlag.get(ImmutableSet.of("missingFlag", "testFlag1", "testFlag2")));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("No feature flag object(s) found for missingFlag");
  }

  @Test
  void testFailure_featureFlagNotPresent() {
    FeatureFlagNotFoundException thrown =
        assertThrows(FeatureFlagNotFoundException.class, () -> FeatureFlag.get("fakeFlag"));
    assertThat(thrown).hasMessageThat().isEqualTo("No feature flag object(s) found for fakeFlag");
  }

  @Test
  void testFailure_resetFeatureName() {
    FeatureFlag featureFlag =
        new FeatureFlag.Builder()
            .setFeatureName("testFlag")
            .setStatus(
                ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                    .put(START_OF_TIME, INACTIVE)
                    .put(DateTime.now(UTC).plusWeeks(8), ACTIVE)
                    .build())
            .build();
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> featureFlag.asBuilder().setFeatureName("differentName"));
    assertThat(thrown).hasMessageThat().isEqualTo("Feature name can only be set once");
  }

  @Test
  void testFailure_nullFeatureName() {
    FeatureFlag.Builder featureFlagBuilder =
        new FeatureFlag.Builder()
            .setStatus(
                ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                    .put(START_OF_TIME, INACTIVE)
                    .put(DateTime.now(UTC).plusWeeks(8), ACTIVE)
                    .build());
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> featureFlagBuilder.build());
    assertThat(thrown).hasMessageThat().isEqualTo("Feature name must not be null or empty");
  }

  @Test
  void testFailure_emptyFeatureName() {
    FeatureFlag.Builder featureFlagBuilder =
        new FeatureFlag.Builder()
            .setFeatureName("")
            .setStatus(
                ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                    .put(START_OF_TIME, INACTIVE)
                    .put(DateTime.now(UTC).plusWeeks(8), ACTIVE)
                    .build());
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> featureFlagBuilder.build());
    assertThat(thrown).hasMessageThat().isEqualTo("Feature name must not be null or empty");
  }

  @Test
  void testFailure_invalidStatusMap() {
    FeatureFlag.Builder featureFlagBuilder = new FeatureFlag.Builder().setFeatureName("testFlag");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                featureFlagBuilder.setStatus(
                    ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                        .put(DateTime.now(UTC).plusWeeks(8), ACTIVE)
                        .build()));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Must provide transition entry for the start of time (Unix Epoch)");
  }
}
