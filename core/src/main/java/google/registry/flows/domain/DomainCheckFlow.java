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

package google.registry.flows.domain;

import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.flows.FlowUtils.validateRegistrarIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.verifyTargetIdCount;
import static google.registry.flows.domain.DomainFlowUtils.checkAllowedAccessToTld;
import static google.registry.flows.domain.DomainFlowUtils.checkHasBillingAccount;
import static google.registry.flows.domain.DomainFlowUtils.getReservationTypes;
import static google.registry.flows.domain.DomainFlowUtils.handleFeeRequest;
import static google.registry.flows.domain.DomainFlowUtils.isAnchorTenant;
import static google.registry.flows.domain.DomainFlowUtils.isReserved;
import static google.registry.flows.domain.DomainFlowUtils.isValidReservedCreate;
import static google.registry.flows.domain.DomainFlowUtils.validateDomainName;
import static google.registry.flows.domain.DomainFlowUtils.validateDomainNameWithIdnTables;
import static google.registry.flows.domain.DomainFlowUtils.verifyNotInPredelegation;
import static google.registry.model.tld.Registry.TldState.START_DATE_SUNRISE;
import static google.registry.model.tld.label.ReservationType.getTypeOfHighestSeverity;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.net.InternetDomainName;
import google.registry.config.RegistryConfig.Config;
import google.registry.flows.EppException;
import google.registry.flows.EppException.ParameterValuePolicyErrorException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.Flow;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.flows.custom.DomainCheckFlowCustomLogic;
import google.registry.flows.custom.DomainCheckFlowCustomLogic.BeforeResponseParameters;
import google.registry.flows.custom.DomainCheckFlowCustomLogic.BeforeResponseReturnData;
import google.registry.flows.domain.token.AllocationTokenDomainCheckResults;
import google.registry.flows.domain.token.AllocationTokenFlowUtils;
import google.registry.model.EppResource;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainCommand.Check;
import google.registry.model.domain.fee.FeeCheckCommandExtension;
import google.registry.model.domain.fee.FeeCheckCommandExtensionItem;
import google.registry.model.domain.fee.FeeCheckResponseExtensionItem;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import google.registry.model.domain.fee06.FeeCheckCommandExtensionV06;
import google.registry.model.domain.launch.LaunchCheckExtension;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationTokenExtension;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.CheckData.DomainCheck;
import google.registry.model.eppoutput.CheckData.DomainCheckData;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.eppoutput.EppResponse.ResponseExtension;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import google.registry.model.tld.Registry;
import google.registry.model.tld.Registry.TldState;
import google.registry.model.tld.label.ReservationType;
import google.registry.persistence.VKey;
import google.registry.util.Clock;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * An EPP flow that checks whether a domain can be provisioned.
 *
 * <p>This flow also supports the EPP fee extension and can return pricing information.
 *
 * @error {@link google.registry.flows.exceptions.TooManyResourceChecksException}
 * @error {@link google.registry.flows.FlowUtils.NotLoggedInException}
 * @error {@link google.registry.flows.FlowUtils.UnknownCurrencyEppException}
 * @error {@link DomainFlowUtils.BadDomainNameCharacterException}
 * @error {@link DomainFlowUtils.BadDomainNamePartsCountException}
 * @error {@link DomainFlowUtils.DomainNameExistsAsTldException}
 * @error {@link DomainFlowUtils.BadPeriodUnitException}
 * @error {@link DomainFlowUtils.BadCommandForRegistryPhaseException}
 * @error {@link DomainFlowUtils.CurrencyUnitMismatchException}
 * @error {@link DomainFlowUtils.DashesInThirdAndFourthException}
 * @error {@link DomainFlowUtils.DomainLabelTooLongException}
 * @error {@link DomainFlowUtils.EmptyDomainNamePartException}
 * @error {@link DomainFlowUtils.FeeChecksDontSupportPhasesException}
 * @error {@link DomainFlowUtils.InvalidIdnDomainLabelException}
 * @error {@link DomainFlowUtils.InvalidPunycodeException}
 * @error {@link DomainFlowUtils.LeadingDashException}
 * @error {@link DomainFlowUtils.MissingBillingAccountMapException}
 * @error {@link DomainFlowUtils.NotAuthorizedForTldException}
 * @error {@link DomainFlowUtils.RestoresAreAlwaysForOneYearException}
 * @error {@link DomainFlowUtils.TldDoesNotExistException}
 * @error {@link DomainFlowUtils.TrailingDashException}
 * @error {@link DomainFlowUtils.TransfersAreAlwaysForOneYearException}
 * @error {@link DomainFlowUtils.UnknownFeeCommandException}
 * @error {@link OnlyCheckedNamesCanBeFeeCheckedException}
 */
@ReportingSpec(ActivityReportField.DOMAIN_CHECK)
public final class DomainCheckFlow implements Flow {

  @Inject ResourceCommand resourceCommand;
  @Inject ExtensionManager extensionManager;
  @Inject EppInput eppInput;
  @Inject @RegistrarId String registrarId;

  @Inject
  @Config("maxChecks")
  int maxChecks;

  @Inject @Superuser boolean isSuperuser;
  @Inject Clock clock;
  @Inject EppResponse.Builder responseBuilder;
  @Inject AllocationTokenFlowUtils allocationTokenFlowUtils;
  @Inject DomainCheckFlowCustomLogic flowCustomLogic;
  @Inject DomainPricingLogic pricingLogic;

  @Inject
  DomainCheckFlow() {}

  @Override
  public EppResponse run() throws EppException {
    extensionManager.register(
        FeeCheckCommandExtension.class, LaunchCheckExtension.class, AllocationTokenExtension.class);
    flowCustomLogic.beforeValidation();
    validateRegistrarIsLoggedIn(registrarId);
    extensionManager.validate();
    ImmutableList<String> domainNames = ((Check) resourceCommand).getTargetIds();
    verifyTargetIdCount(domainNames, maxChecks);
    DateTime now = clock.nowUtc();
    ImmutableMap.Builder<String, InternetDomainName> parsedDomainsBuilder =
        new ImmutableMap.Builder<>();
    // Only check that the registrar has access to a TLD the first time it is encountered
    Set<String> seenTlds = new HashSet<>();
    for (String domainName : ImmutableSet.copyOf(domainNames)) {
      InternetDomainName parsedDomain = validateDomainName(domainName);
      validateDomainNameWithIdnTables(parsedDomain);
      // This validation is moderately expensive, so cache the results.
      parsedDomainsBuilder.put(domainName, parsedDomain);
      String tld = parsedDomain.parent().toString();
      boolean tldFirstTimeSeen = seenTlds.add(tld);
      if (tldFirstTimeSeen && !isSuperuser) {
        checkAllowedAccessToTld(registrarId, tld);
        checkHasBillingAccount(registrarId, tld);
        verifyNotInPredelegation(Registry.get(tld), now);
      }
    }
    ImmutableMap<String, InternetDomainName> parsedDomains = parsedDomainsBuilder.build();
    flowCustomLogic.afterValidation(
        DomainCheckFlowCustomLogic.AfterValidationParameters.newBuilder()
            .setDomainNames(parsedDomains)
            // TODO: Use as of date from fee extension v0.12 instead of now, if specified.
            .setAsOfDate(now)
            .build());
    ImmutableMap<String, VKey<Domain>> existingDomains =
        ForeignKeyUtils.load(Domain.class, domainNames, now);
    Optional<AllocationTokenExtension> allocationTokenExtension =
        eppInput.getSingleExtension(AllocationTokenExtension.class);
    Optional<AllocationTokenDomainCheckResults> tokenDomainCheckResults =
        allocationTokenExtension.map(
            tokenExtension ->
                allocationTokenFlowUtils.checkDomainsWithToken(
                    ImmutableList.copyOf(parsedDomains.values()),
                    tokenExtension.getAllocationToken(),
                    registrarId,
                    now));

    ImmutableList.Builder<DomainCheck> checksBuilder = new ImmutableList.Builder<>();
    ImmutableSet.Builder<String> availableDomains = new ImmutableSet.Builder<>();
    ImmutableMap<String, TldState> tldStates =
        Maps.toMap(seenTlds, tld -> Registry.get(tld).getTldState(now));
    ImmutableMap<InternetDomainName, String> domainCheckResults =
        tokenDomainCheckResults
            .map(AllocationTokenDomainCheckResults::domainCheckResults)
            .orElse(ImmutableMap.of());
    Optional<AllocationToken> allocationToken =
        tokenDomainCheckResults.flatMap(AllocationTokenDomainCheckResults::token);
    for (String domainName : domainNames) {
      Optional<String> message =
          getMessageForCheck(
              parsedDomains.get(domainName),
              existingDomains,
              domainCheckResults,
              tldStates,
              allocationToken);
      boolean isAvailable = !message.isPresent();
      checksBuilder.add(DomainCheck.create(isAvailable, domainName, message.orElse(null)));
      if (isAvailable) {
        availableDomains.add(domainName);
      }
    }
    BeforeResponseReturnData responseData =
        flowCustomLogic.beforeResponse(
            BeforeResponseParameters.newBuilder()
                .setDomainChecks(checksBuilder.build())
                .setResponseExtensions(
                    getResponseExtensions(
                        parsedDomains,
                        existingDomains,
                        availableDomains.build(),
                        now,
                        allocationToken))
                .setAsOfDate(now)
                .build());
    return responseBuilder
        .setResData(DomainCheckData.create(responseData.domainChecks()))
        .setExtensions(responseData.responseExtensions())
        .build();
  }

  private Optional<String> getMessageForCheck(
      InternetDomainName domainName,
      ImmutableMap<String, VKey<Domain>> existingDomains,
      ImmutableMap<InternetDomainName, String> tokenCheckResults,
      ImmutableMap<String, TldState> tldStates,
      Optional<AllocationToken> allocationToken) {
    if (existingDomains.containsKey(domainName.toString())) {
      return Optional.of("In use");
    }
    TldState tldState = tldStates.get(domainName.parent().toString());
    if (isReserved(domainName, START_DATE_SUNRISE.equals(tldState))) {
      if (!isValidReservedCreate(domainName, allocationToken)
          && !isAnchorTenant(domainName, allocationToken, Optional.empty())) {
        ImmutableSet<ReservationType> reservationTypes = getReservationTypes(domainName);
        if (!reservationTypes.isEmpty()) {
          ReservationType highestSeverityType = getTypeOfHighestSeverity(reservationTypes);
          return Optional.of(highestSeverityType.getMessageForCheck());
        }
      }
    }
    return Optional.ofNullable(emptyToNull(tokenCheckResults.get(domainName)));
  }

  /** Handle the fee check extension. */
  private ImmutableList<? extends ResponseExtension> getResponseExtensions(
      ImmutableMap<String, InternetDomainName> domainNames,
      ImmutableMap<String, VKey<Domain>> existingDomains,
      ImmutableSet<String> availableDomains,
      DateTime now,
      Optional<AllocationToken> allocationToken)
      throws EppException {
    Optional<FeeCheckCommandExtension> feeCheckOpt =
        eppInput.getSingleExtension(FeeCheckCommandExtension.class);
    if (!feeCheckOpt.isPresent()) {
      return ImmutableList.of(); // No fee checks were requested.
    }
    FeeCheckCommandExtension<?, ?> feeCheck = feeCheckOpt.get();
    ImmutableList.Builder<FeeCheckResponseExtensionItem> responseItems =
        new ImmutableList.Builder<>();
    ImmutableMap<String, Domain> domainObjs =
        loadDomainsForRestoreChecks(feeCheck, domainNames, existingDomains);
    ImmutableMap<String, BillingEvent.Recurring> recurrences =
        loadRecurrencesForDomains(domainObjs);

    for (FeeCheckCommandExtensionItem feeCheckItem : feeCheck.getItems()) {
      for (String domainName : getDomainNamesToCheckForFee(feeCheckItem, domainNames.keySet())) {
        FeeCheckResponseExtensionItem.Builder<?> builder = feeCheckItem.createResponseBuilder();
        Optional<Domain> domain = Optional.ofNullable(domainObjs.get(domainName));
        handleFeeRequest(
            feeCheckItem,
            builder,
            domainNames.get(domainName),
            domain,
            feeCheck.getCurrency(),
            now,
            pricingLogic,
            allocationToken,
            availableDomains.contains(domainName),
            recurrences.getOrDefault(domainName, null));
        responseItems.add(builder.setDomainNameIfSupported(domainName).build());
      }
    }
    return ImmutableList.of(feeCheck.createResponse(responseItems.build()));
  }

  /**
   * Loads and returns all existing domains that are having restore fees checked.
   *
   * <p>This is necessary so that we can check their expiration dates to determine if a one-year
   * renewal is part of the cost of a restore.
   *
   * <p>This may be resource-intensive for large checks of many restore fees, but those are
   * comparatively rare, and we are at least using an in-memory cache. Also, this will get a lot
   * nicer in Cloud SQL when we can SELECT just the fields we want rather than having to load the
   * entire entity.
   */
  private ImmutableMap<String, Domain> loadDomainsForRestoreChecks(
      FeeCheckCommandExtension<?, ?> feeCheck,
      ImmutableMap<String, InternetDomainName> domainNames,
      ImmutableMap<String, VKey<Domain>> existingDomains) {
    ImmutableList<String> restoreCheckDomains;
    if (feeCheck instanceof FeeCheckCommandExtensionV06) {
      // The V06 fee extension supports specifying the command fees to check on a per-domain basis.
      restoreCheckDomains =
          feeCheck.getItems().stream()
              .filter(fc -> fc.getCommandName() == CommandName.RESTORE)
              .map(FeeCheckCommandExtensionItem::getDomainName)
              .distinct()
              .collect(toImmutableList());
    } else if (feeCheck.getItems().stream()
        .anyMatch(fc -> fc.getCommandName() == CommandName.RESTORE)) {
      // The more recent fee extension versions support specifying the command fees to check only on
      // the overall domain check, not per-domain.
      restoreCheckDomains = ImmutableList.copyOf(domainNames.keySet());
    } else {
      // Fall-through case for more recent fee extension versions when the restore fee isn't being
      // checked.
      restoreCheckDomains = ImmutableList.of();
    }

    // Filter down to just domains we know exist and then use the EppResource cache to load them.
    ImmutableMap<String, VKey<Domain>> existingDomainsToLoad =
        restoreCheckDomains.stream()
            .filter(existingDomains::containsKey)
            .collect(toImmutableMap(d -> d, existingDomains::get));
    ImmutableMap<VKey<? extends EppResource>, EppResource> loadedDomains =
        EppResource.loadCached(ImmutableList.copyOf(existingDomainsToLoad.values()));
    return ImmutableMap.copyOf(
        Maps.transformEntries(existingDomainsToLoad, (k, v) -> (Domain) loadedDomains.get(v)));
  }

  private ImmutableMap<String, BillingEvent.Recurring> loadRecurrencesForDomains(
      ImmutableMap<String, Domain> domainObjs) {
    return tm().transact(
            () -> {
              ImmutableMap<VKey<? extends BillingEvent.Recurring>, BillingEvent.Recurring>
                  recurrences =
                      tm().loadByKeys(
                              domainObjs.values().stream()
                                  .map(Domain::getAutorenewBillingEvent)
                                  .collect(toImmutableSet()));
              return ImmutableMap.copyOf(
                  Maps.transformValues(
                      domainObjs, d -> recurrences.get(d.getAutorenewBillingEvent())));
            });
  }

  /**
   * Return the domains to be checked for a particular fee check item. Some versions of the fee
   * extension specify the domain name in the extension item, while others use the list of domain
   * names from the regular check domain availability list.
   */
  private ImmutableSet<String> getDomainNamesToCheckForFee(
      FeeCheckCommandExtensionItem feeCheckItem, ImmutableSet<String> availabilityCheckDomains)
      throws OnlyCheckedNamesCanBeFeeCheckedException {
    if (feeCheckItem.isDomainNameSupported()) {
      String domainNameInExtension = feeCheckItem.getDomainName();
      if (!availabilityCheckDomains.contains(domainNameInExtension)) {
        // Although the fee extension explicitly says it's ok to fee check a domain name that you
        // aren't also availability checking, we forbid it. This makes the experience simpler and
        // also means we can assume any domain names in the fee checks have been validated.
        throw new OnlyCheckedNamesCanBeFeeCheckedException();
      }
      return ImmutableSet.of(domainNameInExtension);
    }
    // If this version of the fee extension is nameless, use the full list of domains.
    return availabilityCheckDomains;
  }

  /** By server policy, fee check names must be listed in the availability check. */
  static class OnlyCheckedNamesCanBeFeeCheckedException extends ParameterValuePolicyErrorException {
    OnlyCheckedNamesCanBeFeeCheckedException() {
      super("By server policy, fee check names must be listed in the availability check");
    }
  }
}
