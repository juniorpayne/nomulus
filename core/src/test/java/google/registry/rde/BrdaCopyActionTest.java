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

package google.registry.rde;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.model.common.Cursor.CursorType.BRDA;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.GpgSystemCommandExtension.GPG_BINARY;
import static google.registry.testing.SystemInfo.hasCommand;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.io.ByteSource;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import google.registry.gcs.GcsUtils;
import google.registry.keyring.api.Keyring;
import google.registry.model.common.Cursor;
import google.registry.model.rde.RdeMode;
import google.registry.model.rde.RdeRevision;
import google.registry.model.tld.Registry;
import google.registry.request.HttpException.NoContentException;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.BouncyCastleProviderExtension;
import google.registry.testing.FakeKeyringModule;
import google.registry.testing.GpgSystemCommandExtension;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Optional;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for {@link BrdaCopyAction}. */
public class BrdaCopyActionTest {

  private static final ByteSource DEPOSIT_XML = RdeTestData.loadBytes("deposit_full.xml");
  private static final String STAGE_FILENAME = "lol_2010-10-17_thin_S1_R0";
  private static final BlobId RYDE_FILE =
      BlobId.of("tub", String.format("%s.ryde", STAGE_FILENAME));
  private static final BlobId SIG_FILE = BlobId.of("tub", String.format("%s.sig", STAGE_FILENAME));

  private BlobId stageFile;
  private BlobId stageLengthFile;

  @RegisterExtension
  public final BouncyCastleProviderExtension bouncy = new BouncyCastleProviderExtension();

  @RegisterExtension
  public final AppEngineExtension appEngine = AppEngineExtension.builder().withCloudSql().build();

  @RegisterExtension
  public final GpgSystemCommandExtension gpg =
      new GpgSystemCommandExtension(
          RdeTestData.loadBytes("pgp-public-keyring.asc"),
          RdeTestData.loadBytes("pgp-private-keyring-escrow.asc"));

  private static PGPPublicKey encryptKey;
  private static PGPPrivateKey decryptKey;
  private static PGPPublicKey receiverKey;
  private static PGPKeyPair signingKey;

  @BeforeAll
  static void beforeAll() {
    try (Keyring keyring = new FakeKeyringModule().get()) {
      encryptKey = keyring.getRdeStagingEncryptionKey();
      decryptKey = keyring.getRdeStagingDecryptionKey();
      receiverKey = keyring.getRdeReceiverKey();
      signingKey = keyring.getRdeSigningKey();
    }
  }

  private final GcsUtils gcsUtils = new GcsUtils(LocalStorageHelper.getOptions());
  private final BrdaCopyAction action = new BrdaCopyAction();

  private void runAction(String prefix) throws IOException {
    stageFile = BlobId.of("keg", String.format("%s%s.xml.ghostryde", prefix, STAGE_FILENAME));
    stageLengthFile = BlobId.of("keg", String.format("%s%s.xml.length", prefix, STAGE_FILENAME));
    byte[] xml = DEPOSIT_XML.read();
    gcsUtils.createFromBytes(stageFile, Ghostryde.encode(xml, encryptKey));
    gcsUtils.createFromBytes(stageLengthFile, Long.toString(xml.length).getBytes(UTF_8));
    action.prefix = prefix.isEmpty() ? Optional.empty() : Optional.of(prefix);
    action.run();
  }

  @BeforeEach
  void beforeEach() throws Exception {
    createTld("lol");
    action.gcsUtils = gcsUtils;
    action.tld = "lol";
    action.watermark = DateTime.parse("2010-10-17TZ");
    action.brdaBucket = "tub";
    action.stagingBucket = "keg";
    action.receiverKey = receiverKey;
    action.signingKey = signingKey;
    action.stagingDecryptionKey = decryptKey;
    tm().transact(
            () -> {
              RdeRevision.saveRevision("lol", DateTime.parse("2010-10-17TZ"), RdeMode.THIN, 0);
            });
    persistResource(Cursor.createScoped(BRDA, action.watermark.plusDays(1), Registry.get("lol")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "job-name/"})
  void testRun_stagingNotFinished_throws204(String prefix) throws Exception {
    persistResource(Cursor.createScoped(BRDA, action.watermark, Registry.get("lol")));
    NoContentException thrown = assertThrows(NoContentException.class, () -> runAction(prefix));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Waiting on RdeStagingAction for TLD lol to copy BRDA deposit for"
                + " 2010-10-17T00:00:00.000Z to GCS; last BRDA staging completion was before"
                + " 2010-10-17T00:00:00.000Z");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "job-name/"})
  void testRun(String prefix) throws Exception {
    runAction(prefix);
    assertThat(gcsUtils.existsAndNotEmpty(stageFile)).isTrue();
    assertThat(gcsUtils.existsAndNotEmpty(stageLengthFile)).isTrue();
    assertThat(gcsUtils.existsAndNotEmpty(SIG_FILE)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "job-name/"})
  void testRun_rydeFormat(String prefix) throws Exception {
    assumeTrue(hasCommand(GPG_BINARY + " --version"));
    runAction(prefix);

    File rydeTmp = new File(gpg.getCwd(), "ryde");
    Files.write(gcsUtils.readBytesFrom(RYDE_FILE), rydeTmp);
    Process pid =
        gpg.exec(
            GPG_BINARY,
            "--list-packets",
            "--ignore-mdc-error",
            "--keyid-format",
            "long",
            rydeTmp.toString());
    String stdout = slurp(pid.getInputStream());
    String stderr = slurp(pid.getErrorStream());
    assertWithMessage(stderr).that(pid.waitFor()).isEqualTo(0);
    assertWithMessage("OpenPGP message is missing encryption layer")
        .that(stdout)
        .contains(":pubkey enc packet:");
    assertWithMessage("Unexpected symmetric encryption algorithm")
        .that(stdout)
        .contains(":pubkey enc packet: version 3, algo 1");
    assertWithMessage("OpenPGP message is missing compression layer")
        .that(stdout)
        .contains(":compressed packet:");
    assertWithMessage("Expected zip compression algorithm")
        .that(stdout)
        .contains(":compressed packet: algo=1");
    assertWithMessage("OpenPGP message is missing literal data packet")
        .that(stdout)
        .contains(":literal data packet:");
    assertWithMessage("Literal data packet does not contain correct filename")
        .that(stdout)
        .contains("name=\"lol_2010-10-17_thin_S1_R0.tar\"");
    assertWithMessage("Literal data packet should be in BINARY mode")
        .that(stdout)
        .contains("mode b ");
    assertWithMessage("Unexpected asymmetric encryption algorithm")
        .that(stderr)
        .contains("encrypted with 2048-bit RSA key");
    assertWithMessage("Unexpected receiver public key")
        .that(stderr)
        .contains("ID 7F9084EE54E1EB0F");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "job-name/"})
  void testRun_rydeSignature(String prefix) throws Exception {
    assumeTrue(hasCommand(GPG_BINARY + " --version"));
    runAction(prefix);

    File rydeTmp = new File(gpg.getCwd(), "ryde");
    File sigTmp = new File(gpg.getCwd(), "ryde.sig");
    Files.write(gcsUtils.readBytesFrom(RYDE_FILE), rydeTmp);
    Files.write(gcsUtils.readBytesFrom(SIG_FILE), sigTmp);

    Process pid = gpg.exec(GPG_BINARY, "--verify", sigTmp.toString(), rydeTmp.toString());
    String stderr = slurp(pid.getErrorStream());
    assertWithMessage(stderr).that(pid.waitFor()).isEqualTo(0);
    assertThat(stderr).contains("Good signature");
    assertThat(stderr).contains("rde-unittest@registry.test");
  }

  private String slurp(InputStream is) throws IOException {
    return CharStreams.toString(new InputStreamReader(is, UTF_8));
  }
}
