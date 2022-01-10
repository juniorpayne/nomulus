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

package google.registry.tools;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.io.Files;
import google.registry.model.tld.Registry;
import google.registry.model.tld.label.PremiumList;
import google.registry.model.tld.label.PremiumList.PremiumEntry;
import google.registry.model.tld.label.PremiumListDao;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link UpdatePremiumListCommand}. */
class UpdatePremiumListCommandTest<C extends UpdatePremiumListCommand>
    extends CreateOrUpdatePremiumListCommandTestCase<C> {
  Registry registry;

  @BeforeEach
  void beforeEach() {
    registry = createRegistry(TLD_TEST, USD, initialPremiumListData);
  }

  @Test
  void verify_registryIsSetUpCorrectly() {
    Optional<PremiumList> list = PremiumListDao.getLatestRevision(TLD_TEST);
    // ensure that no premium list is created before running the command
    assertThat(list.isPresent()).isTrue();
    // data from @beforeEach of CreateOrUpdatePremiumListCommandTestCase.java
    assertThat(PremiumListDao.loadPremiumEntries(list.get()))
        .comparingElementsUsing(immutableObjectCorrespondence("revisionId"))
        .containsExactly(PremiumEntry.create(0L, new BigDecimal("9090.00"), "doge"));
  }

  @Test
  void commandPrompt_successStageEntityChange() throws Exception {
    File tmpFile = tmpDir.resolve(String.format("%s.txt", TLD_TEST)).toFile();
    String newPremiumListData = "omg,USD 1234";
    Files.asCharSink(tmpFile, UTF_8).write(newPremiumListData);
    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    command.inputFile = Paths.get(tmpFile.getPath());
    command.name = TLD_TEST;
    command.prompt();
    assertThat(command.prompt()).contains("Update premium list for prime?");
  }

  @Test
  void commandRun_successUpdateList() throws Exception {
    File tmpFile = tmpDir.resolve(String.format("%s.txt", TLD_TEST)).toFile();
    String newPremiumListData = "eth,USD 9999";
    Files.asCharSink(tmpFile, UTF_8).write(newPremiumListData);

    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    // data come from @beforeEach of CreateOrUpdatePremiumListCommandTestCase.java
    command.inputFile = Paths.get(tmpFile.getPath());
    runCommandForced("--name=" + TLD_TEST, "--input=" + command.inputFile);

    assertThat(PremiumListDao.loadAllPremiumEntries(TLD_TEST))
        .comparingElementsUsing(immutableObjectCorrespondence("revisionId"))
        .containsExactly(PremiumEntry.create(0L, new BigDecimal("9999.00"), "eth"));
  }

  @Test
  void commandRun_successUpdateList_whenExistingListIsEmpty() throws Exception {
    File existingPremiumFile = tmpDir.resolve(TLD_TEST + ".txt").toFile();
    Files.asCharSink(existingPremiumFile, UTF_8).write("");

    File newPremiumFile = tmpDir.resolve(String.format("%s.txt", TLD_TEST)).toFile();
    String newPremiumListData = "eth,USD 9999";
    Files.asCharSink(newPremiumFile, UTF_8).write(newPremiumListData);

    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    // data come from @beforeEach of CreateOrUpdatePremiumListCommandTestCase.java
    command.inputFile = Paths.get(newPremiumFile.getPath());
    runCommandForced("--name=" + TLD_TEST, "--input=" + command.inputFile);

    assertThat(PremiumListDao.loadAllPremiumEntries(TLD_TEST))
        .comparingElementsUsing(immutableObjectCorrespondence("revisionId"))
        .containsExactly(PremiumEntry.create(0L, new BigDecimal("9999.00"), "eth"));
  }

  @Test
  void commandRun_successUpdateMultiLineList() throws Exception {
    File tmpFile = tmpDir.resolve(TLD_TEST + ".txt").toFile();
    String premiumTerms = "foo,USD 9000\ndoge,USD 100\nelon,USD 2021";
    Files.asCharSink(tmpFile, UTF_8).write(premiumTerms);

    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    command.inputFile = Paths.get(tmpFile.getPath());
    runCommandForced("--name=" + TLD_TEST, "--input=" + command.inputFile);

    // assert all three lines from premiumTerms are added
    assertThat(
            PremiumListDao.loadAllPremiumEntries(TLD_TEST).stream()
                .map(Object::toString)
                .collect(toImmutableList()))
        .containsExactly("foo, 9000.00", "doge, 100.00", "elon, 2021.00");
  }

  @Test
  void commandPrompt_failureUpdateEmptyList() throws Exception {
    Path tmpPath = tmpDir.resolve(String.format("%s.txt", TLD_TEST));
    Files.write(new byte[0], tmpPath.toFile());

    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    command.inputFile = tmpPath;
    command.name = TLD_TEST;
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::prompt);
    assertThat(thrown).hasMessageThat().isEqualTo("New premium list data cannot be empty");
  }

  @Test
  void commandPrompt_failureNoPreviousVersion() {
    String fileName = "random";
    registry = createRegistry(fileName, null, null);
    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    command.name = fileName;
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::prompt);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            String.format("Could not update premium list %s because it doesn't exist.", fileName));
  }

  @Test
  void commandPrompt_failureNoInputFile() {
    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    assertThrows(NullPointerException.class, command::prompt);
  }

  @Test
  void commandPrompt_failureTldFromNameDoesNotExist() {
    String fileName = "random";
    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    command.name = fileName;
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::prompt);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            String.format("Could not update premium list %s because it doesn't exist.", fileName));
  }

  @Test
  void commandPrompt_failureTldFromInputFileDoesNotExist() {
    String fileName = "random";
    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    // using tld extracted from file name but this tld is not part of the registry
    command.inputFile =
        Paths.get(tmpDir.resolve(String.format("%s.txt", fileName)).toFile().getPath());
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::prompt);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            String.format("Could not update premium list %s because it doesn't exist.", fileName));
  }
}
