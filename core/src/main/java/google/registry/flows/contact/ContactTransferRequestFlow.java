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

package google.registry.flows.contact;

import static google.registry.flows.FlowUtils.createHistoryEntryId;
import static google.registry.flows.FlowUtils.validateRegistrarIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.verifyAuthInfo;
import static google.registry.flows.ResourceFlowUtils.verifyAuthInfoPresentForResourceTransfer;
import static google.registry.flows.ResourceFlowUtils.verifyNoDisallowedStatuses;
import static google.registry.flows.contact.ContactFlowUtils.createGainingTransferPollMessage;
import static google.registry.flows.contact.ContactFlowUtils.createLosingTransferPollMessage;
import static google.registry.flows.contact.ContactFlowUtils.createTransferResponse;
import static google.registry.model.eppoutput.Result.Code.SUCCESS_WITH_ACTION_PENDING;
import static google.registry.model.reporting.HistoryEntry.Type.CONTACT_TRANSFER_REQUEST;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableSet;
import google.registry.config.RegistryConfig.Config;
import google.registry.flows.EppException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.TransactionalFlow;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.flows.exceptions.AlreadyPendingTransferException;
import google.registry.flows.exceptions.ObjectAlreadySponsoredException;
import google.registry.model.contact.Contact;
import google.registry.model.contact.ContactHistory;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import google.registry.model.transfer.ContactTransferData;
import google.registry.model.transfer.TransferStatus;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * An EPP flow that requests a transfer on a contact.
 *
 * <p>The "gaining" registrar requests a transfer from the "losing" (aka current) registrar. The
 * losing registrar has a "transfer" time period to respond (by default five days) after which the
 * transfer is automatically approved. Within that window, the transfer might be approved explicitly
 * by the losing registrar or rejected, and the gaining registrar can also cancel the transfer
 * request.
 *
 * @error {@link google.registry.flows.FlowUtils.NotLoggedInException}
 * @error {@link google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link google.registry.flows.exceptions.AlreadyPendingTransferException}
 * @error {@link google.registry.flows.exceptions.MissingTransferRequestAuthInfoException}
 * @error {@link google.registry.flows.exceptions.ObjectAlreadySponsoredException}
 * @error {@link google.registry.flows.exceptions.ResourceStatusProhibitsOperationException}
 */
@ReportingSpec(ActivityReportField.CONTACT_TRANSFER_REQUEST)
public final class ContactTransferRequestFlow implements TransactionalFlow {

  private static final ImmutableSet<StatusValue> DISALLOWED_STATUSES =
      ImmutableSet.of(
          StatusValue.CLIENT_TRANSFER_PROHIBITED,
          StatusValue.PENDING_DELETE,
          StatusValue.SERVER_TRANSFER_PROHIBITED);

  @Inject ExtensionManager extensionManager;
  @Inject Optional<AuthInfo> authInfo;
  @Inject @RegistrarId String gainingClientId;
  @Inject @TargetId String targetId;

  @Inject
  @Config("contactAutomaticTransferLength")
  Duration automaticTransferLength;

  @Inject ContactHistory.Builder historyBuilder;
  @Inject Trid trid;
  @Inject EppResponse.Builder responseBuilder;

  @Inject
  ContactTransferRequestFlow() {}

  @Override
  public EppResponse run() throws EppException {
    extensionManager.register(MetadataExtension.class);
    validateRegistrarIsLoggedIn(gainingClientId);
    extensionManager.validate();
    DateTime now = tm().getTransactionTime();
    Contact existingContact = loadAndVerifyExistence(Contact.class, targetId, now);
    verifyAuthInfoPresentForResourceTransfer(authInfo);
    verifyAuthInfo(authInfo.get(), existingContact);
    // Verify that the resource does not already have a pending transfer.
    if (TransferStatus.PENDING.equals(existingContact.getTransferData().getTransferStatus())) {
      throw new AlreadyPendingTransferException(targetId);
    }
    String losingClientId = existingContact.getCurrentSponsorRegistrarId();
    // Verify that this client doesn't already sponsor this resource.
    if (gainingClientId.equals(losingClientId)) {
      throw new ObjectAlreadySponsoredException();
    }
    verifyNoDisallowedStatuses(existingContact, DISALLOWED_STATUSES);

    DateTime transferExpirationTime = now.plus(automaticTransferLength);
    ContactTransferData serverApproveTransferData =
        new ContactTransferData.Builder()
            .setTransferRequestTime(now)
            .setTransferRequestTrid(trid)
            .setGainingRegistrarId(gainingClientId)
            .setLosingRegistrarId(losingClientId)
            .setPendingTransferExpirationTime(transferExpirationTime)
            .setTransferStatus(TransferStatus.SERVER_APPROVED)
            .build();
    HistoryEntryId contactHistoryId = createHistoryEntryId(existingContact);
    historyBuilder
        .setRevisionId(contactHistoryId.getRevisionId())
        .setType(CONTACT_TRANSFER_REQUEST);
    // If the transfer is server approved, this message will be sent to the losing registrar. */
    PollMessage serverApproveLosingPollMessage =
        createLosingTransferPollMessage(targetId, serverApproveTransferData, contactHistoryId);
    // If the transfer is server approved, this message will be sent to the gaining registrar. */
    PollMessage serverApproveGainingPollMessage =
        createGainingTransferPollMessage(
            targetId, serverApproveTransferData, now, contactHistoryId);
    ContactTransferData pendingTransferData =
        serverApproveTransferData
            .asBuilder()
            .setTransferStatus(TransferStatus.PENDING)
            .setServerApproveEntities(
                serverApproveGainingPollMessage.getContactRepoId(),
                contactHistoryId.getRevisionId(),
                ImmutableSet.of(
                    serverApproveGainingPollMessage.createVKey(),
                    serverApproveLosingPollMessage.createVKey()))
            .build();
    // When a transfer is requested, a poll message is created to notify the losing registrar.
    PollMessage requestPollMessage =
        createLosingTransferPollMessage(targetId, pendingTransferData, contactHistoryId)
            .asBuilder()
            .setEventTime(now) // Unlike the serverApprove messages, this applies immediately.
            .build();
    Contact newContact =
        existingContact
            .asBuilder()
            .setTransferData(pendingTransferData)
            .addStatusValue(StatusValue.PENDING_TRANSFER)
            .build();
    tm().update(newContact);
    tm().insertAll(
            ImmutableSet.of(
                historyBuilder.setContact(newContact).build(),
                requestPollMessage,
                serverApproveGainingPollMessage,
                serverApproveLosingPollMessage));
    return responseBuilder
        .setResultFromCode(SUCCESS_WITH_ACTION_PENDING)
        .setResData(createTransferResponse(targetId, newContact.getTransferData()))
        .build();
  }
}
