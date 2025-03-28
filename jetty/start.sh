#!/bin/sh
# Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

env=${1:-"alpha"}
cd /jetty-base
cp -rf webapps/console-${env}/. webapps/console/
cd webapps
# Remove all environment builds not used in the deployment
find . -maxdepth 1 -type d -name "console-*" -exec rm -rf {} +
cd /jetty-base
echo "Running ${env}"
java -Dgoogle.registry.environment=${env} \
    -Djava.util.logging.config.file=/logging.properties \
    -jar /usr/local/jetty/start.jar
