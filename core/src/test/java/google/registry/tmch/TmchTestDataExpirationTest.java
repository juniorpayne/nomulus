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

package google.registry.tmch;

import static google.registry.testing.TestDataHelper.loadFile;
import static org.joda.time.DateTimeZone.UTC;

import google.registry.config.RegistryConfig.ConfigModule.TmchCaMode;
import google.registry.flows.EppException;
import google.registry.flows.domain.DomainFlowTmchUtils;
import google.registry.model.smd.EncodedSignedMark;
import google.registry.testing.AppEngineExtension;
import google.registry.util.SystemClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests that the ICANN testing signed mark files are valid and not expired. */
class TmchTestDataExpirationTest {

  @RegisterExtension
  public final AppEngineExtension appEngine = AppEngineExtension.builder().withCloudSql().build();

  /**
   * Verifies the currently-active signed mark file provided by ICANN.
   *
   * <p>The rest of the tests use injected clocks so that we don't have to keep updating those SMD
   * files and so that we can test various types of files, but this test will vail when the validity
   * of the ICANN-provided file expires.
   *
   * <p>When this fails, check
   * <a>https://newgtlds.icann.org/en/about/trademark-clearinghouse/registries-registrars</a> for a
   * new updated SMD file.
   */
  @Test
  void testActiveSignedMarkFiles_areValidAndNotExpired() throws Exception {
    DomainFlowTmchUtils tmchUtils =
        new DomainFlowTmchUtils(
            new TmchXmlSignature(
                new TmchCertificateAuthority(TmchCaMode.PILOT, new SystemClock())));

    String filePath = "active/smd-active-21aug20-en.smd";
    String tmchData = loadFile(TmchTestDataExpirationTest.class, filePath);
    EncodedSignedMark smd = TmchData.readEncodedSignedMark(tmchData);
    try {
      tmchUtils.verifyEncodedSignedMark(smd, DateTime.now(UTC));
    } catch (EppException e) {
      throw new AssertionError("Error verifying signed mark " + filePath, e);
    }
  }
}
