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

package google.registry.xml;

import static com.google.common.truth.Truth.assertThat;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link UtcDateTimeAdapter}. */
class UtcDateTimeAdapterTest {

  @Test
  void testMarshal() {
    assertThat(new UtcDateTimeAdapter().marshal(new DateTime(2010, 10, 17, 4, 20, 0, UTC)))
        .isEqualTo("2010-10-17T04:20:00Z");
  }

  @Test
  void testMarshalConvertsToZuluTime() {
    assertThat(new UtcDateTimeAdapter().marshal(
        new DateTime(2010, 10, 17, 0, 20, 0, DateTimeZone.forOffsetHours(-4))))
            .isEqualTo("2010-10-17T04:20:00Z");
  }

  @Test
  void testMarshalEmpty() {
    assertThat(new UtcDateTimeAdapter().marshal(null)).isEmpty();
  }

  @Test
  void testUnmarshal() {
    assertThat(new UtcDateTimeAdapter().unmarshal("2010-10-17T04:20:00Z"))
        .isEqualTo(new DateTime(2010, 10, 17, 4, 20, 0, UTC));
  }

  @Test
  void testUnmarshalConvertsToZuluTime() {
    assertThat(new UtcDateTimeAdapter().unmarshal("2010-10-17T00:20:00-04:00"))
        .isEqualTo(new DateTime(2010, 10, 17, 4, 20, 0, UTC));
  }

  @Test
  void testUnmarshalEmpty() {
    assertThat(new UtcDateTimeAdapter().unmarshal(null)).isNull();
    assertThat(new UtcDateTimeAdapter().unmarshal("")).isNull();
  }

  @Test
  void testUnmarshalInvalid() {
    assertThrows(
        IllegalArgumentException.class,
        () -> assertThat(new UtcDateTimeAdapter().unmarshal("oh my goth")).isNull());
  }
}
