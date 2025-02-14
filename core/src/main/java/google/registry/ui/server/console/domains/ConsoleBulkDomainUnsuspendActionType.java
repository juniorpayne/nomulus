// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.console.domains;

import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonElement;
import google.registry.model.console.ConsolePermission;

/** An action that will unsuspend the given domain, removing all 5 server*Prohibited statuses. */
public class ConsoleBulkDomainUnsuspendActionType implements ConsoleDomainActionType {

  private static final String DOMAIN_SUSPEND_XML =
      """
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<epp
  xmlns="urn:ietf:params:xml:ns:epp-1.0">
  <command>
    <update>
      <domain:update
        xmlns:domain="urn:ietf:params:xml:ns:domain-1.0">
        <domain:name>%DOMAIN_NAME%</domain:name>
        <domain:add></domain:add>
        <domain:rem>
          <domain:status s="serverDeleteProhibited" lang="en"></domain:status>
          <domain:status s="serverHold" lang="en"></domain:status>
          <domain:status s="serverRenewProhibited" lang="en"></domain:status>
          <domain:status s="serverTransferProhibited" lang="en"></domain:status>
          <domain:status s="serverUpdateProhibited" lang="en"></domain:status>
        </domain:rem>
      </domain:update>
    </update>
    <extension>
      <metadata:metadata
        xmlns:metadata="urn:google:params:xml:ns:metadata-1.0">
        <metadata:reason>Console unsuspension: %REASON%</metadata:reason>
        <metadata:requestedByRegistrar>false</metadata:requestedByRegistrar>
      </metadata:metadata>
    </extension>
    <clTRID>RegistryConsole</clTRID>
  </command>
</epp>""";

  private final String reason;

  public ConsoleBulkDomainUnsuspendActionType(JsonElement jsonElement) {
    this.reason = jsonElement.getAsJsonObject().get("reason").getAsString();
  }

  @Override
  public String getXmlContentsToRun(String domainName) {
    return ConsoleDomainActionType.fillSubstitutions(
        DOMAIN_SUSPEND_XML, ImmutableMap.of("DOMAIN_NAME", domainName, "REASON", reason));
  }

  @Override
  public ConsolePermission getNecessaryPermission() {
    return ConsolePermission.SUSPEND_DOMAIN;
  }
}
