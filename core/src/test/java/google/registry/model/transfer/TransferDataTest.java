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

package google.registry.model.transfer;

import static com.google.common.truth.Truth.assertThat;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableSet;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Cancellation;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.billing.BillingEvent.Recurring;
import google.registry.model.domain.Period;
import google.registry.model.eppcommon.Trid;
import google.registry.model.poll.PollMessage;
import google.registry.persistence.VKey;
import google.registry.testing.AppEngineExtension;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link TransferData}. */
public class TransferDataTest {

  @RegisterExtension
  public final AppEngineExtension appEngine = AppEngineExtension.builder().withCloudSql().build();

  private final DateTime now = DateTime.now(UTC);

  private VKey<BillingEvent.OneTime> transferBillingEventKey;
  private VKey<BillingEvent.Cancellation> otherServerApproveBillingEventKey;
  private VKey<BillingEvent.Recurring> recurringBillingEventKey;
  private VKey<PollMessage.Autorenew> autorenewPollMessageKey;
  private VKey<PollMessage.OneTime> otherServerApprovePollMessageKey;

  @BeforeEach
  void beforeEach() {
    transferBillingEventKey = OneTime.createVKey(12345L);
    otherServerApproveBillingEventKey = Cancellation.createVKey(2468L);
    recurringBillingEventKey = Recurring.createVKey(13579L);
    autorenewPollMessageKey = VKey.create(PollMessage.Autorenew.class, 67890L);
    otherServerApprovePollMessageKey = VKey.create(PollMessage.OneTime.class, 314159L);
  }

  @Test
  void test_copyConstantFieldsToBuilder() {
    DomainTransferData constantTransferData =
        new DomainTransferData.Builder()
            .setTransferRequestTrid(Trid.create("server-trid", "client-trid"))
            .setTransferRequestTime(now)
            .setGainingRegistrarId("NewRegistrar")
            .setLosingRegistrarId("TheRegistrar")
            // Test must use a non-1-year period, since that's the default value.
            .setTransferPeriod(Period.create(5, Period.Unit.YEARS))
            .build();
    DomainTransferData fullTransferData =
        constantTransferData
            .asBuilder()
            .setPendingTransferExpirationTime(now)
            .setTransferStatus(TransferStatus.PENDING)
            .setServerApproveEntities(
                "4-TLD",
                1356L,
                ImmutableSet.of(
                    transferBillingEventKey,
                    otherServerApproveBillingEventKey,
                    recurringBillingEventKey,
                    autorenewPollMessageKey,
                    otherServerApprovePollMessageKey))
            .setServerApproveBillingEvent(transferBillingEventKey)
            .setServerApproveAutorenewEvent(recurringBillingEventKey)
            .setServerApproveAutorenewPollMessage(autorenewPollMessageKey)
            .build();
    // asBuilder() copies over all fields
    assertThat(fullTransferData.asBuilder().build()).isEqualTo(fullTransferData);
    // copyConstantFieldsToBuilder() copies only constant fields
    assertThat(fullTransferData.copyConstantFieldsToBuilder().build())
        .isEqualTo(constantTransferData);
  }
}
