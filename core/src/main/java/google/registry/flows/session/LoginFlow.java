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

package google.registry.flows.session;

import static com.google.common.collect.Sets.difference;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.CollectionUtils.nullToEmpty;

import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.flows.EppException;
import google.registry.flows.EppException.AuthenticationErrorClosingConnectionException;
import google.registry.flows.EppException.AuthenticationErrorException;
import google.registry.flows.EppException.AuthorizationErrorException;
import google.registry.flows.EppException.CommandUseErrorException;
import google.registry.flows.EppException.ParameterValuePolicyErrorException;
import google.registry.flows.EppException.UnimplementedExtensionException;
import google.registry.flows.EppException.UnimplementedObjectServiceException;
import google.registry.flows.EppException.UnimplementedProtocolVersionException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.FlowUtils.GenericXmlSyntaxErrorException;
import google.registry.flows.MutatingFlow;
import google.registry.flows.SessionMetadata;
import google.registry.flows.TlsCredentials.BadRegistrarCertificateException;
import google.registry.flows.TlsCredentials.BadRegistrarIpAddressException;
import google.registry.flows.TlsCredentials.MissingRegistrarCertificateException;
import google.registry.flows.TransportCredentials;
import google.registry.flows.TransportCredentials.BadRegistrarPasswordException;
import google.registry.model.eppcommon.ProtocolDefinition;
import google.registry.model.eppcommon.ProtocolDefinition.ServiceExtension;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.EppInput.Login;
import google.registry.model.eppinput.EppInput.Options;
import google.registry.model.eppinput.EppInput.Services;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.registrar.Registrar;
import google.registry.util.PasswordUtils.HashAlgorithm;
import java.util.Optional;
import java.util.Set;
import javax.inject.Inject;

/**
 * An EPP flow for login.
 *
 * @error {@link UnimplementedExtensionException}
 * @error {@link UnimplementedObjectServiceException}
 * @error {@link UnimplementedProtocolVersionException}
 * @error {@link GenericXmlSyntaxErrorException}
 * @error {@link BadRegistrarCertificateException}
 * @error {@link BadRegistrarIpAddressException}
 * @error {@link MissingRegistrarCertificateException}
 * @error {@link BadRegistrarPasswordException}
 * @error {@link LoginFlow.AlreadyLoggedInException}
 * @error {@link BadRegistrarIdException}
 * @error {@link LoginFlow.TooManyFailedLoginsException}
 * @error {@link LoginFlow.RegistrarAccountNotActiveException}
 * @error {@link LoginFlow.UnsupportedLanguageException}
 */
public class LoginFlow implements MutatingFlow {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Maximum number of failed login attempts allowed per connection. */
  private static final int MAX_FAILED_LOGIN_ATTEMPTS_PER_CONNECTION = 3;

  @Inject ExtensionManager extensionManager;
  @Inject EppInput eppInput;
  @Inject SessionMetadata sessionMetadata;
  @Inject TransportCredentials credentials;
  @Inject @RegistrarId String registrarId;
  @Inject EppResponse.Builder responseBuilder;

  @Inject LoginFlow() {}

  /** Run the flow and log errors. */
  @Override
  public final EppResponse run() throws EppException {
    try {
      return runWithoutLogging();
    } catch (EppException e) {
      logger.atWarning().log("Login failed: %s", e.getMessage());
      throw e;
    }
  }

  /** Run the flow without bothering to log errors. The {@link #run} method will do that for us. */
  private EppResponse runWithoutLogging() throws EppException {
    extensionManager.validate();  // There are no legal extensions for this flow.
    Login login = (Login) eppInput.getCommandWrapper().getCommand();
    if (!registrarId.isEmpty()) {
      throw new AlreadyLoggedInException();
    }
    Options options = login.getOptions();
    if (!ProtocolDefinition.LANGUAGE.equals(options.getLanguage())) {
      throw new UnsupportedLanguageException();
    }
    Services services = login.getServices();
    Set<String> unsupportedObjectServices = difference(
        nullToEmpty(services.getObjectServices()),
        ProtocolDefinition.SUPPORTED_OBJECT_SERVICES);
    if (!unsupportedObjectServices.isEmpty()) {
      throw new UnimplementedObjectServiceException();
    }
    ImmutableSet.Builder<String> serviceExtensionUrisBuilder = new ImmutableSet.Builder<>();
    for (String uri : nullToEmpty(services.getServiceExtensions())) {
      ServiceExtension serviceExtension = ProtocolDefinition.getServiceExtensionFromUri(uri);
      if (serviceExtension == null) {
        throw new UnimplementedExtensionException();
      }
      serviceExtensionUrisBuilder.add(uri);
    }
    Optional<Registrar> registrar = Registrar.loadByRegistrarIdCached(login.getClientId());
    if (!registrar.isPresent()) {
      throw new BadRegistrarIdException(login.getClientId());
    }

    // AuthenticationErrorExceptions will propagate up through here.
    try {
      credentials.validate(registrar.get(), login.getPassword());
    } catch (AuthenticationErrorException e) {
      sessionMetadata.incrementFailedLoginAttempts();
      if (sessionMetadata.getFailedLoginAttempts() > MAX_FAILED_LOGIN_ATTEMPTS_PER_CONNECTION) {
        throw new TooManyFailedLoginsException();
      } else {
        throw e;
      }
    }
    if (!registrar.get().isLive()) {
      throw new RegistrarAccountNotActiveException();
    }

    if (login.getNewPassword().isPresent()
        || registrar.get().getCurrentHashAlgorithm(login.getPassword()).orElse(null)
            != HashAlgorithm.SCRYPT) {
      String newPassword =
          login
              .getNewPassword()
              .orElseGet(
                  () -> {
                    logger.atInfo().log("Rehashing existing registrar password with Scrypt");
                    return login.getPassword();
                  });
      // Load fresh from database (bypassing the cache) to ensure we don't save stale data.
      Optional<Registrar> freshRegistrar = Registrar.loadByRegistrarId(login.getClientId());
      if (!freshRegistrar.isPresent()) {
        throw new BadRegistrarIdException(login.getClientId());
      }
      tm().put(freshRegistrar.get().asBuilder().setPassword(newPassword).build());
    }

    // We are in!
    sessionMetadata.resetFailedLoginAttempts();
    sessionMetadata.setRegistrarId(login.getClientId());
    sessionMetadata.setServiceExtensionUris(serviceExtensionUrisBuilder.build());
    return responseBuilder.setIsLoginResponse().build();
  }

  /** Registrar with this ID could not be found. */
  static class BadRegistrarIdException extends AuthenticationErrorException {
    BadRegistrarIdException(String registrarId) {
      super("Registrar with this ID could not be found: " + registrarId);
    }
  }

  /** Registrar login failed too many times. */
  static class TooManyFailedLoginsException extends AuthenticationErrorClosingConnectionException {
    TooManyFailedLoginsException() {
      super("Registrar login failed too many times");
    }
  }

  /** Registrar account is not active. */
  static class RegistrarAccountNotActiveException extends AuthorizationErrorException {
    RegistrarAccountNotActiveException() {
      super("Registrar account is not active");
    }
  }

  /** Registrar is already logged in. */
  static class AlreadyLoggedInException extends CommandUseErrorException {
    AlreadyLoggedInException() {
      super("Registrar is already logged in");
    }
  }

  /** Specified language is not supported. */
  static class UnsupportedLanguageException extends ParameterValuePolicyErrorException {
    UnsupportedLanguageException() {
      super("Specified language is not supported");
    }
  }
}
