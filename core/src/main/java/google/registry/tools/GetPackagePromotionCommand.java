// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.model.domain.token.PackagePromotion;
import java.util.List;

/** Command to show a {@link PackagePromotion} object. */
@Parameters(separators = " =", commandDescription = "Show package promotion object(s)")
public class GetPackagePromotionCommand extends GetEppResourceCommand {

  @Parameter(description = "Package token(s)", required = true)
  private List<String> mainParameters;

  @Override
  void runAndPrint() {
    for (String token : mainParameters) {
      jpaTm()
          .transact(
              () -> {
                PackagePromotion packagePromotion =
                    checkArgumentPresent(
                        PackagePromotion.loadByTokenString(token),
                        "PackagePromotion with package token %s does not exist",
                        token);
                System.out.println(packagePromotion);
              });
    }
  }
}
