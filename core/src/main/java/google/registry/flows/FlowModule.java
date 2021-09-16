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

import static com.google.common.base.Preconditions.checkState;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.base.Strings;
import dagger.Module;
import dagger.Provides;
import google.registry.flows.picker.FlowPicker;
import google.registry.model.contact.ContactHistory;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppcommon.Trid;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.EppInput.Poll;
import google.registry.model.eppinput.EppInput.ResourceCommandWrapper;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppinput.ResourceCommand.SingleResourceCommand;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.eppoutput.Result;
import google.registry.model.host.HostHistory;
import google.registry.model.reporting.HistoryEntry;
import java.lang.annotation.Documented;
import java.util.Optional;
import javax.inject.Qualifier;

/** Module to choose and instantiate an EPP flow. */
@Module
public class FlowModule {

  private EppInput eppInput;
  private byte[] inputXmlBytes;
  private SessionMetadata sessionMetadata;
  private TransportCredentials credentials;
  private boolean isDryRun;
  private EppRequestSource eppRequestSource;
  private boolean isSuperuser;

  private FlowModule() {}

  /** Builder for {@link FlowModule}. */
  static class Builder {
    FlowModule module = new FlowModule();

    Builder setEppInput(EppInput eppInput) {
      module.eppInput = eppInput;
      return this;
    }

    Builder setInputXmlBytes(byte[] inputXmlBytes) {
      module.inputXmlBytes = inputXmlBytes;
      return this;
    }

    Builder setSessionMetadata(SessionMetadata sessionMetadata) {
      module.sessionMetadata = sessionMetadata;
      return this;
    }

    Builder setIsDryRun(boolean isDryRun) {
      module.isDryRun = isDryRun;
      return this;
    }

    Builder setIsSuperuser(boolean isSuperuser) {
      module.isSuperuser = isSuperuser;
      return this;
    }

    Builder setEppRequestSource(EppRequestSource eppRequestSource) {
      module.eppRequestSource = eppRequestSource;
      return this;
    }

    Builder setCredentials(TransportCredentials credentials) {
      module.credentials = credentials;
      return this;
    }

    FlowModule build() {
      try {
        checkState(module != null, "Already built");
        return module;
      } finally {
        module = null;
      }
    }
  }

  @Provides
  @FlowScope
  @InputXml
  byte[] provideInputXml() {
    return inputXmlBytes;
  }

  @Provides
  @FlowScope
  EppInput provideEppInput() {
    return eppInput;
  }

  @Provides
  @FlowScope
  SessionMetadata provideSessionMetadata() {
    return sessionMetadata;
  }

  @Provides
  @FlowScope
  @DryRun
  boolean provideIsDryRun() {
    return isDryRun;
  }

  @Provides
  @FlowScope
  @Transactional
  boolean provideIsTransactional(Class<? extends Flow> flowClass) {
    return TransactionalFlow.class.isAssignableFrom(flowClass);
  }

  @Provides
  @FlowScope
  @Superuser
  boolean provideIsSuperuser() {
    return isSuperuser;
  }

  @Provides
  @FlowScope
  EppRequestSource provideEppRequestSource() {
    return eppRequestSource;
  }

  @Provides
  @FlowScope
  TransportCredentials provideTransportCredentials() {
    return credentials;
  }

  @Provides
  @FlowScope
  Trid provideTrid(EppInput eppInput, ServerTridProvider serverTridProvider) {
    return Trid.create(
        eppInput.getCommandWrapper().getClTrid().orElse(null),
        serverTridProvider.createServerTrid());
  }

  @Provides
  @FlowScope
  @RegistrarId
  static String provideRegistrarId(SessionMetadata sessionMetadata) {
    // Treat a missing registrarId as null so we can always inject a non-null value. All we do with
    // the registrarId is log it (as "") or detect its absence, both of which work fine with empty.
    return Strings.nullToEmpty(sessionMetadata.getRegistrarId());
  }

  @Provides
  @FlowScope
  static Class<? extends Flow> provideFlowClass(EppInput eppInput) {
    try {
      return FlowPicker.getFlowClass(eppInput);
    } catch (EppException e) {
      throw new EppExceptionInProviderException(e);
    }
  }

  @Provides
  @FlowScope
  static ResourceCommand provideResourceCommand(EppInput eppInput) {
    return ((ResourceCommandWrapper) eppInput.getCommandWrapper().getCommand())
        .getResourceCommand();
  }

  @Provides
  @FlowScope
  static AuthInfo provideAuthInfo(ResourceCommand resourceCommand) {
    return ((SingleResourceCommand) resourceCommand).getAuthInfo();
  }

  @Provides
  @FlowScope
  static Optional<AuthInfo> provideOptionalAuthInfo(ResourceCommand resourceCommand) {
    return Optional.ofNullable(((SingleResourceCommand) resourceCommand).getAuthInfo());
  }

  @Provides
  @FlowScope
  @TargetId
  static String provideTargetId(ResourceCommand resourceCommand) {
    return ((SingleResourceCommand) resourceCommand).getTargetId();
  }

  @Provides
  @FlowScope
  @PollMessageId
  static String providePollMessageId(EppInput eppInput) {
    return Strings.nullToEmpty(((Poll) eppInput.getCommandWrapper().getCommand()).getMessageId());
  }

  private static <B extends HistoryEntry.Builder<? extends HistoryEntry, ?>>
      B makeHistoryEntryBuilder(
          B builder,
          Trid trid,
          byte[] inputXmlBytes,
          boolean isSuperuser,
          String registrarId,
          EppInput eppInput) {
    builder
        .setModificationTime(tm().getTransactionTime())
        .setTrid(trid)
        .setXmlBytes(inputXmlBytes)
        .setBySuperuser(isSuperuser)
        .setRegistrarId(registrarId);
    Optional<MetadataExtension> metadataExtension =
        eppInput.getSingleExtension(MetadataExtension.class);
    metadataExtension.ifPresent(
        extension ->
            builder
                .setReason(extension.getReason())
                .setRequestedByRegistrar(extension.getRequestedByRegistrar()));
    return builder;
  }

  /**
   * Provides a partially filled in {@link ContactHistory.Builder}
   *
   * <p>This is not marked with {@link FlowScope} so that each retry gets a fresh one. Otherwise,
   * the fact that the builder is one-use would cause NPEs.
   */
  @Provides
  static ContactHistory.Builder provideContactHistoryBuilder(
      Trid trid,
      @InputXml byte[] inputXmlBytes,
      @Superuser boolean isSuperuser,
      @RegistrarId String registrarId,
      EppInput eppInput) {
    return makeHistoryEntryBuilder(
        new ContactHistory.Builder(), trid, inputXmlBytes, isSuperuser, registrarId, eppInput);
  }

  /**
   * Provides a partially filled in {@link HostHistory.Builder}
   *
   * <p>This is not marked with {@link FlowScope} so that each retry gets a fresh one. Otherwise,
   * the fact that the builder is one-use would cause NPEs.
   */
  @Provides
  static HostHistory.Builder provideHostHistoryBuilder(
      Trid trid,
      @InputXml byte[] inputXmlBytes,
      @Superuser boolean isSuperuser,
      @RegistrarId String registrarId,
      EppInput eppInput) {
    return makeHistoryEntryBuilder(
        new HostHistory.Builder(), trid, inputXmlBytes, isSuperuser, registrarId, eppInput);
  }

  /**
   * Provides a partially filled in {@link DomainHistory.Builder}
   *
   * <p>This is not marked with {@link FlowScope} so that each retry gets a fresh one. Otherwise,
   * the fact that the builder is one-use would cause NPEs.
   */
  @Provides
  static DomainHistory.Builder provideDomainHistoryBuilder(
      Trid trid,
      @InputXml byte[] inputXmlBytes,
      @Superuser boolean isSuperuser,
      @RegistrarId String registrarId,
      EppInput eppInput) {
    return makeHistoryEntryBuilder(
        new DomainHistory.Builder(), trid, inputXmlBytes, isSuperuser, registrarId, eppInput);
  }

  /**
   * Provides a partially filled in {@link EppResponse} builder.
   *
   * <p>This is not marked with {@link FlowScope} so that each retry gets a fresh one. Otherwise,
   * the fact that the builder is one-use would cause NPEs.
   */
  @Provides
  static EppResponse.Builder provideEppResponseBuilder(Trid trid) {
    return new EppResponse.Builder()
        .setTrid(trid)
        .setResultFromCode(Result.Code.SUCCESS); // Default to success.
  }

  @Provides
  static FlowMetadata provideFlowMetadata(@Superuser boolean isSuperuser) {
    return FlowMetadata.newBuilder().setSuperuser(isSuperuser).build();
  }

  /** Wrapper class to carry an {@link EppException} to the calling code. */
  static class EppExceptionInProviderException extends RuntimeException {
    EppExceptionInProviderException(EppException exception) {
      super(exception);
    }
  }

  /** Dagger qualifier for inputXml. */
  @Qualifier
  @Documented
  public @interface InputXml {}

  /** Dagger qualifier for registrar client id. */
  @Qualifier
  @Documented
  public @interface RegistrarId {}

  /** Dagger qualifier for the target id (foreign key) for single resource flows. */
  @Qualifier
  @Documented
  public @interface TargetId {}

  /** Dagger qualifier for the message id for poll flows. */
  @Qualifier
  @Documented
  public @interface PollMessageId {}

  /** Dagger qualifier for whether a flow is in dry run mode. */
  @Qualifier
  @Documented
  public @interface DryRun {}

  /** Dagger qualifier for whether a flow is in superuser mode. */
  @Qualifier
  @Documented
  public @interface Superuser {}

  /** Dagger qualifier for whether a flow is transactional. */
  @Qualifier
  @Documented
  public @interface Transactional {}
}
