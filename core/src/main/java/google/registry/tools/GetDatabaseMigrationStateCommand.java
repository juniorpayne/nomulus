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

import com.beust.jcommander.Parameters;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import google.registry.model.common.TimedTransitionProperty;

/** A command to check the current Registry 3.0 migration state of the database. */
@DeleteAfterMigration
@Parameters(separators = " =", commandDescription = "Check current Registry 3.0 migration state")
public class GetDatabaseMigrationStateCommand implements Command {

  @Override
  public void run() throws Exception {
    TimedTransitionProperty<MigrationState> migrationSchedule =
        DatabaseMigrationStateSchedule.get();
    System.out.printf("Current migration schedule: %s%n", migrationSchedule.toValueMap());
  }
}
