// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.model.tld.label.PremiumList;
import google.registry.model.tld.label.PremiumList.PremiumEntry;
import google.registry.model.tld.label.PremiumListDao;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Retrieves and prints one or more premium lists. */
@Parameters(separators = " =", commandDescription = "Show one or more premium lists")
public class GetPremiumListCommand implements Command {

  @Parameter(description = "Name(s) of the premium list(s) to retrieve", required = true)
  private List<String> mainParameters;

  @Override
  public void run() {
    for (String premiumListName : mainParameters) {
      Optional<PremiumList> premiumList = PremiumListDao.getLatestRevision(premiumListName);
      if (premiumList.isPresent()) {
        System.out.printf(
            "%s:\n%s\n",
            premiumListName,
            PremiumListDao.loadAllPremiumEntries(premiumListName).stream()
                .sorted(Comparator.comparing(PremiumEntry::getDomainLabel))
                .map(premiumEntry -> premiumEntry.toString(premiumList.get().getCurrency()))
                .collect(Collectors.joining("\n")));
      } else {
        System.out.printf("No list found with name %s.%n", premiumListName);
      }
    }
  }
}
