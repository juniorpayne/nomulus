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

import static google.registry.flows.FlowUtils.validateRegistrarIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.verifyResourceDoesNotExist;
import static google.registry.flows.contact.ContactFlowUtils.validateAsciiPostalInfo;
import static google.registry.flows.contact.ContactFlowUtils.validateContactAgainstPolicy;
import static google.registry.model.EppResourceUtils.createRepoId;
import static google.registry.model.IdService.allocateId;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableSet;
import google.registry.config.RegistryConfig.Config;
import google.registry.flows.EppException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.TransactionalFlow;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.flows.exceptions.ResourceAlreadyExistsForThisClientException;
import google.registry.flows.exceptions.ResourceCreateContentionException;
import google.registry.model.contact.Contact;
import google.registry.model.contact.ContactCommand.Create;
import google.registry.model.contact.ContactHistory;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.CreateData.ContactCreateData;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * An EPP flow that creates a new contact.
 *
 * @error {@link google.registry.flows.FlowUtils.NotLoggedInException}
 * @error {@link ResourceAlreadyExistsForThisClientException}
 * @error {@link ResourceCreateContentionException}
 * @error {@link ContactFlowUtils.BadInternationalizedPostalInfoException}
 * @error {@link ContactFlowUtils.DeclineContactDisclosureFieldDisallowedPolicyException}
 */
@ReportingSpec(ActivityReportField.CONTACT_CREATE)
public final class ContactCreateFlow implements TransactionalFlow {

  @Inject ResourceCommand resourceCommand;
  @Inject ExtensionManager extensionManager;
  @Inject @RegistrarId String registrarId;
  @Inject @TargetId String targetId;
  @Inject ContactHistory.Builder historyBuilder;
  @Inject EppResponse.Builder responseBuilder;
  @Inject @Config("contactAndHostRoidSuffix") String roidSuffix;
  @Inject ContactCreateFlow() {}

  @Override
  public EppResponse run() throws EppException {
    extensionManager.register(MetadataExtension.class);
    validateRegistrarIsLoggedIn(registrarId);
    extensionManager.validate();
    Create command = (Create) resourceCommand;
    DateTime now = tm().getTransactionTime();
    verifyResourceDoesNotExist(Contact.class, targetId, now, registrarId);
    Contact newContact =
        new Contact.Builder()
            .setContactId(targetId)
            .setAuthInfo(command.getAuthInfo())
            .setCreationRegistrarId(registrarId)
            .setPersistedCurrentSponsorRegistrarId(registrarId)
            .setRepoId(createRepoId(allocateId(), roidSuffix))
            .setFaxNumber(command.getFax())
            .setVoiceNumber(command.getVoice())
            .setDisclose(command.getDisclose())
            .setEmailAddress(command.getEmail())
            .setInternationalizedPostalInfo(command.getInternationalizedPostalInfo())
            .setLocalizedPostalInfo(command.getLocalizedPostalInfo())
            .build();
    validateAsciiPostalInfo(newContact.getInternationalizedPostalInfo());
    validateContactAgainstPolicy(newContact);
    historyBuilder
        .setType(HistoryEntry.Type.CONTACT_CREATE)
        .setXmlBytes(null) // We don't want to store contact details in the history entry.
        .setContact(newContact);
    tm().insertAll(ImmutableSet.of(newContact, historyBuilder.build()));
    return responseBuilder
        .setResData(ContactCreateData.create(newContact.getContactId(), now))
        .build();
  }
}
