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

package google.registry.flows;

import static com.google.common.collect.Sets.intersection;
import static google.registry.model.EppResourceUtils.isLinked;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import google.registry.flows.EppException.AuthorizationErrorException;
import google.registry.flows.EppException.InvalidAuthorizationInformationErrorException;
import google.registry.flows.EppException.ObjectDoesNotExistException;
import google.registry.flows.EppException.ParameterValuePolicyErrorException;
import google.registry.flows.EppException.ParameterValueRangeErrorException;
import google.registry.flows.exceptions.MissingTransferRequestAuthInfoException;
import google.registry.flows.exceptions.NotPendingTransferException;
import google.registry.flows.exceptions.NotTransferInitiatorException;
import google.registry.flows.exceptions.ResourceAlreadyExistsForThisClientException;
import google.registry.flows.exceptions.ResourceCreateContentionException;
import google.registry.flows.exceptions.ResourceStatusProhibitsOperationException;
import google.registry.flows.exceptions.ResourceToDeleteIsReferencedException;
import google.registry.flows.exceptions.TooManyResourceChecksException;
import google.registry.model.EppResource;
import google.registry.model.EppResource.ForeignKeyedEppResource;
import google.registry.model.EppResource.ResourceWithTransferData;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.Period;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.transfer.TransferStatus;
import google.registry.persistence.VKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.joda.time.DateTime;

/** Static utility functions for resource flows. */
public final class ResourceFlowUtils {

  private ResourceFlowUtils() {}

  /** Check that the given registrarId corresponds to the owner of given resource. */
  public static void verifyResourceOwnership(String myRegistrarId, EppResource resource)
      throws EppException {
    if (!myRegistrarId.equals(resource.getPersistedCurrentSponsorRegistrarId())) {
      throw new ResourceNotOwnedException();
    }
  }

  /**
   * Check whether if there are domains linked to the resource to be deleted. Throws an exception if
   * so.
   */
  public static <R extends EppResource> void checkLinkedDomains(
      final String targetId, final DateTime now, final Class<R> resourceClass) throws EppException {
    EppException failfastException =
        tm().transact(
                () -> {
                  VKey<R> key = ForeignKeyUtils.load(resourceClass, targetId, now);
                  if (key == null) {
                    return new ResourceDoesNotExistException(resourceClass, targetId);
                  }
                  return isLinked(key, now) ? new ResourceToDeleteIsReferencedException() : null;
                });
    if (failfastException != null) {
      throw failfastException;
    }
  }

  public static <R extends EppResource & ResourceWithTransferData> void verifyHasPendingTransfer(
      R resource) throws NotPendingTransferException {
    if (resource.getTransferData().getTransferStatus() != TransferStatus.PENDING) {
      throw new NotPendingTransferException(resource.getForeignKey());
    }
  }

  public static <R extends EppResource & ResourceWithTransferData> void verifyTransferInitiator(
      String registrarId, R resource) throws NotTransferInitiatorException {
    if (!resource.getTransferData().getGainingRegistrarId().equals(registrarId)) {
      throw new NotTransferInitiatorException();
    }
  }

  public static <R extends EppResource & ForeignKeyedEppResource> R loadAndVerifyExistence(
      Class<R> clazz, String targetId, DateTime now) throws ResourceDoesNotExistException {
    return verifyExistence(clazz, targetId, loadByForeignKey(clazz, targetId, now));
  }

  public static <R extends EppResource> R verifyExistence(
      Class<R> clazz, String targetId, Optional<R> resource) throws ResourceDoesNotExistException {
    return resource.orElseThrow(() -> new ResourceDoesNotExistException(clazz, targetId));
  }

  public static <R extends EppResource> void verifyResourceDoesNotExist(
      Class<R> clazz, String targetId, DateTime now, String registrarId) throws EppException {
    VKey<R> key = ForeignKeyUtils.load(clazz, targetId, now);
    if (key != null) {
      R resource = tm().loadByKey(key);
      // These are similar exceptions, but we can track them internally as log-based metrics.
      if (Objects.equals(registrarId, resource.getPersistedCurrentSponsorRegistrarId())) {
        throw new ResourceAlreadyExistsForThisClientException(targetId);
      } else {
        throw new ResourceCreateContentionException(targetId);
      }
    }
  }

  /** Check that the given AuthInfo is present for a resource being transferred. */
  public static void verifyAuthInfoPresentForResourceTransfer(Optional<AuthInfo> authInfo)
      throws EppException {
    if (!authInfo.isPresent()) {
      throw new MissingTransferRequestAuthInfoException();
    }
  }

  /** Check that the given AuthInfo is either missing or else is valid for the given resource. */
  public static void verifyOptionalAuthInfo(Optional<AuthInfo> authInfo, Contact contact)
      throws EppException {
    if (authInfo.isPresent()) {
      verifyAuthInfo(authInfo.get(), contact);
    }
  }

  /** Check that the given AuthInfo is either missing or else is valid for the given resource. */
  public static void verifyOptionalAuthInfo(Optional<AuthInfo> authInfo, Domain domain)
      throws EppException {
    if (authInfo.isPresent()) {
      verifyAuthInfo(authInfo.get(), domain);
    }
  }

  /** Check that the given {@link AuthInfo} is valid for the given domain. */
  public static void verifyAuthInfo(AuthInfo authInfo, Domain domain) throws EppException {
    final String authRepoId = authInfo.getPw().getRepoId();
    String authPassword = authInfo.getPw().getValue();
    if (authRepoId == null) {
      // If no roid is specified, check the password against the domain's password.
      String domainPassword = domain.getAuthInfo().getPw().getValue();
      if (!domainPassword.equals(authPassword)) {
        throw new BadAuthInfoForResourceException();
      }
      return;
    }
    // The roid should match one of the contacts.
    Optional<VKey<Contact>> foundContact =
        domain.getReferencedContacts().stream()
            .filter(key -> key.getKey().equals(authRepoId))
            .findFirst();
    if (!foundContact.isPresent()) {
      throw new BadAuthInfoForResourceException();
    }
    // Check the authInfo against the contact.
    verifyAuthInfo(authInfo, tm().transact(() -> tm().loadByKey(foundContact.get())));
  }

  /** Check that the given {@link AuthInfo} is valid for the given contact. */
  public static void verifyAuthInfo(AuthInfo authInfo, Contact contact) throws EppException {
    String authRepoId = authInfo.getPw().getRepoId();
    String authPassword = authInfo.getPw().getValue();
    String contactPassword = contact.getAuthInfo().getPw().getValue();
    if (!contactPassword.equals(authPassword)
        // It's unnecessary to specify a repoId on a contact auth info, but if it's there validate
        // it. The usual case of this is validating a domain's auth using this method.
        || (authRepoId != null && !authRepoId.equals(contact.getRepoId()))) {
      throw new BadAuthInfoForResourceException();
    }
  }

  /** Check that the resource does not have any disallowed status values. */
  public static void verifyNoDisallowedStatuses(
      EppResource resource, ImmutableSet<StatusValue> disallowedStatuses) throws EppException {
    Set<StatusValue> problems = Sets.intersection(resource.getStatusValues(), disallowedStatuses);
    if (!problems.isEmpty()) {
      throw new ResourceStatusProhibitsOperationException(problems);
    }
  }

  /** Get the list of target ids from a check command. */
  public static void verifyTargetIdCount(List<String> targetIds, int maxChecks)
      throws TooManyResourceChecksException {
    if (targetIds.size() > maxChecks) {
      throw new TooManyResourceChecksException(maxChecks);
    }
  }

  /** Check that the same values aren't being added and removed in an update command. */
  public static void checkSameValuesNotAddedAndRemoved(
      ImmutableSet<?> fieldsToAdd, ImmutableSet<?> fieldsToRemove)
      throws AddRemoveSameValueException {
    if (!intersection(fieldsToAdd, fieldsToRemove).isEmpty()) {
      throw new AddRemoveSameValueException();
    }
  }

  /** Check that all {@link StatusValue} objects in a set are client-settable. */
  public static void verifyAllStatusesAreClientSettable(Set<StatusValue> statusValues)
      throws StatusNotClientSettableException {
    for (StatusValue statusValue : statusValues) {
      if (!statusValue.isClientSettable()) {
        throw new StatusNotClientSettableException(statusValue.getXmlName());
      }
    }
  }

  /**
   * Computes the exDate for the domain at the given transfer approval time with an adjusted amount
   * of transfer period years if the domain is in the auto renew grace period at the time of
   * approval.
   *
   * @param domain is the domain already projected at approvalTime
   */
  public static DateTime computeExDateForApprovalTime(
      DomainBase domain, DateTime approvalTime, Period period) {
    boolean inAutoRenew = domain.getGracePeriodStatuses().contains(GracePeriodStatus.AUTO_RENEW);
    // inAutoRenew is set to false if the period is zero because a zero-period transfer should not
    // subsume an autorenew.
    // https://www.icann.org/resources/unthemed-pages/appendix-07-2010-01-06-en section 3.1.2
    // specifies that when a transfer occurs without a change to the expiration date, the losing
    // registrar's account should still be charged for the autorenew.
    if (period.getValue() == 0) {
      inAutoRenew = false;
    }
    return Domain.extendRegistrationWithCap(
        approvalTime,
        domain.getRegistrationExpirationTime(),
        period.getValue() - (inAutoRenew ? 1 : 0));
  }

  /** Resource with this id does not exist. */
  public static class ResourceDoesNotExistException extends ObjectDoesNotExistException {
    public ResourceDoesNotExistException(Class<?> type, String targetId) {
      super(type, targetId);
    }
  }

  /** The specified resource belongs to another client. */
  public static class ResourceNotOwnedException extends AuthorizationErrorException {
    public ResourceNotOwnedException() {
      super("The specified resource belongs to another client");
    }
  }

  /** Authorization information for accessing resource is invalid. */
  public static class BadAuthInfoForResourceException
      extends InvalidAuthorizationInformationErrorException {
    public BadAuthInfoForResourceException() {
      super("Authorization information for accessing resource is invalid");
    }
  }

  /** Cannot add and remove the same value. */
  public static class AddRemoveSameValueException extends ParameterValuePolicyErrorException {
    public AddRemoveSameValueException() {
      super("Cannot add and remove the same value");
    }
  }

  /** The specified status value cannot be set by clients. */
  public static class StatusNotClientSettableException extends ParameterValueRangeErrorException {
    public StatusNotClientSettableException(String statusValue) {
      super(String.format("Status value %s cannot be set by clients", statusValue));
    }
  }
}
