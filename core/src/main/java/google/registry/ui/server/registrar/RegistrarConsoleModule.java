// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.registrar;

import static google.registry.request.RequestParameters.extractBooleanParameter;
import static google.registry.request.RequestParameters.extractOptionalIntParameter;
import static google.registry.request.RequestParameters.extractOptionalParameter;
import static google.registry.request.RequestParameters.extractRequiredParameter;

import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dagger.Module;
import dagger.Provides;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.request.OptionalJsonPayload;
import google.registry.request.Parameter;
import google.registry.request.RequestScope;
import google.registry.request.Response;
import google.registry.request.auth.AuthResult;
import google.registry.security.XsrfTokenManager;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.DateTime;

/** Dagger module for the Registrar Console parameters. */
@Module
public final class RegistrarConsoleModule {
  static final String PARAM_CLIENT_ID = "clientId";

  @Provides
  @RequestScope
  ConsoleApiParams provideConsoleApiParams(
      HttpServletRequest request,
      Response response,
      AuthResult authResult,
      XsrfTokenManager xsrfTokenManager) {
    return ConsoleApiParams.create(request, response, authResult, xsrfTokenManager);
  }

  @Provides
  @Parameter(PARAM_CLIENT_ID)
  static Optional<String> provideOptionalClientId(HttpServletRequest req) {
    return extractOptionalParameter(req, PARAM_CLIENT_ID);
  }

  @Provides
  @Parameter(PARAM_CLIENT_ID)
  static String provideClientId(HttpServletRequest req) {
    return extractRequiredParameter(req, PARAM_CLIENT_ID);
  }

  @Provides
  @Parameter("ianaId")
  static Optional<Integer> provideOptionalIanaId(HttpServletRequest req) {
    return extractOptionalIntParameter(req, "ianaId");
  }

  @Provides
  @Parameter("billingAccount")
  static Optional<String> provideOptionalBillingAccount(HttpServletRequest req) {
    return extractOptionalParameter(req, "billingAccount");
  }

  @Provides
  @Parameter("name")
  static Optional<String> provideOptionalName(HttpServletRequest req) {
    return extractOptionalParameter(req, "name");
  }

  @Provides
  @Parameter("driveId")
  static Optional<String> provideOptionalDriveId(HttpServletRequest req) {
    return extractOptionalParameter(req, "driveId");
  }

  @Provides
  @Parameter("referralEmail")
  static Optional<String> provideOptionalReferralEmail(HttpServletRequest req) {
    return extractOptionalParameter(req, "referralEmail");
  }

  @Provides
  @Parameter("consoleUserEmail")
  static Optional<String> provideOptionalConsoleUserEmail(HttpServletRequest req) {
    return extractOptionalParameter(req, "consoleUserEmail");
  }

  @Provides
  @Parameter("email")
  static Optional<String> provideOptionalEmail(HttpServletRequest req) {
    return extractOptionalParameter(req, "email");
  }

  @Provides
  @Parameter("email")
  static String provideEmail(HttpServletRequest req) {
    return extractRequiredParameter(req, "email");
  }

  @Provides
  @Parameter("street1")
  static Optional<String> provideOptionalStreet1(HttpServletRequest req) {
    return extractOptionalParameter(req, "street1");
  }

  @Provides
  @Parameter("street2")
  static Optional<String> provideOptionalStreet2(HttpServletRequest req) {
    return extractOptionalParameter(req, "street2");
  }

  @Provides
  @Parameter("street3")
  static Optional<String> provideOptionalStreet3(HttpServletRequest req) {
    return extractOptionalParameter(req, "street3");
  }

  @Provides
  @Parameter("city")
  static Optional<String> provideOptionalCity(HttpServletRequest req) {
    return extractOptionalParameter(req, "city");
  }

  @Provides
  @Parameter("state")
  static Optional<String> provideOptionalState(HttpServletRequest req) {
    return extractOptionalParameter(req, "state");
  }

  @Provides
  @Parameter("zip")
  static Optional<String> provideOptionalZip(HttpServletRequest req) {
    return extractOptionalParameter(req, "zip");
  }

  @Provides
  @Parameter("countryCode")
  static Optional<String> provideOptionalCountryCode(HttpServletRequest req) {
    return extractOptionalParameter(req, "countryCode");
  }

  @Provides
  @Parameter("password")
  static Optional<String> provideOptionalPassword(HttpServletRequest req) {
    return extractOptionalParameter(req, "password");
  }

  @Provides
  @Parameter("passcode")
  static Optional<String> provideOptionalPasscode(HttpServletRequest req) {
    return extractOptionalParameter(req, "passcode");
  }

  @Provides
  @Parameter("lockVerificationCode")
  static String provideLockVerificationCode(HttpServletRequest req) {
    return extractRequiredParameter(req, "lockVerificationCode");
  }

  @Provides
  @Parameter("isLock")
  static Boolean provideIsLock(HttpServletRequest req) {
    return extractBooleanParameter(req, "isLock");
  }

  @Provides
  @Parameter("domain")
  static String provideDomain(HttpServletRequest req) {
    return extractRequiredParameter(req, "domain");
  }

  @Provides
  @Parameter("contacts")
  public static Optional<ImmutableSet<RegistrarPoc>> provideContacts(
      Gson gson, @OptionalJsonPayload Optional<JsonElement> payload) {
    return payload.map(s -> ImmutableSet.copyOf(gson.fromJson(s, RegistrarPoc[].class)));
  }

  @Provides
  @Parameter("registrarId")
  static String provideRegistrarId(HttpServletRequest req) {
    return extractRequiredParameter(req, "registrarId");
  }

  @Provides
  @Parameter("registrar")
  public static Optional<Registrar> provideRegistrar(
      Gson gson, @OptionalJsonPayload Optional<JsonElement> payload) {
    return payload.map(s -> gson.fromJson(s, Registrar.class));
  }

  @Provides
  @Parameter("checkpointTime")
  public static Optional<DateTime> provideCheckpointTime(HttpServletRequest req) {
    return extractOptionalParameter(req, "checkpointTime").map(DateTime::parse);
  }

  @Provides
  @Parameter("pageNumber")
  public static Optional<Integer> providePageNumber(HttpServletRequest req) {
    return extractOptionalIntParameter(req, "pageNumber");
  }

  @Provides
  @Parameter("resultsPerPage")
  public static Optional<Integer> provideResultsPerPage(HttpServletRequest req) {
    return extractOptionalIntParameter(req, "resultsPerPage");
  }

  @Provides
  @Parameter("totalResults")
  public static Optional<Long> provideTotalResults(HttpServletRequest req) {
    return extractOptionalParameter(req, "totalResults").map(Long::valueOf);
  }
}
