#!/bin/bash
# Copyright 2023 The Nomulus Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Sync the TLD configuration files from the internal repo with the Tld object
# in the database. Loops through the Tld configuration files and runs the configure_tld command
# with the file.

# - env: The Nomulus environment, production, sandbox, etc.
# - tools_credential: The credential (.json) needed to run the nomulus command.
# - config_file_directory: The internal directory storing the TLD config files.

set -e
if [ "$#" -ne 3 ]; then
  echo "Expecting three parameters in order: env tools_credential config_file_directory"
  exit 1
fi

nomulus_env="${1}"
tools_credential="${2}"
config_file_directory="${3}"

echo ${config_file_directory}

for FILE in ${config_file_directory}/${nomulus_env}/*; do
  echo $FILE
  java -jar /nomulus.jar -e "${nomulus_env}" \
  --credential "${tools_credential}" \
  configure_tld -i $FILE --force
done
