// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.console;

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.gson.Gson;
import google.registry.model.EppResourceUtils;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.model.domain.Domain;
import google.registry.request.Action;
import google.registry.request.Action.GkeService;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import java.util.Optional;
import javax.inject.Inject;

/** Returns a JSON representation of a domain to the registrar console. */
@Action(
    service = Action.Service.DEFAULT,
    gkeService = GkeService.CONSOLE,
    path = ConsoleDomainGetAction.PATH,
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ConsoleDomainGetAction extends ConsoleApiAction {

  public static final String PATH = "/console-api/domain";

  private final Gson gson;
  private final String paramDomain;

  @Inject
  public ConsoleDomainGetAction(
      ConsoleApiParams consoleApiParams,
      Gson gson,
      @Parameter("consoleDomain") String paramDomain) {
    super(consoleApiParams);
    this.paramDomain = paramDomain;
    this.gson = gson;
  }

  @Override
  protected void getHandler(User user) {
    Optional<Domain> possibleDomain =
        tm().transact(
                () ->
                    EppResourceUtils.loadByForeignKeyCached(
                        Domain.class, paramDomain, tm().getTransactionTime()));
    if (possibleDomain.isEmpty()) {
      consoleApiParams.response().setStatus(SC_NOT_FOUND);
      return;
    }
    Domain domain = possibleDomain.get();
    if (!user.getUserRoles()
        .hasPermission(domain.getCurrentSponsorRegistrarId(), ConsolePermission.DOWNLOAD_DOMAINS)) {
      consoleApiParams.response().setStatus(SC_NOT_FOUND);
      return;
    }
    consoleApiParams.response().setStatus(SC_OK);
    consoleApiParams.response().setPayload(gson.toJson(domain));
  }
}
