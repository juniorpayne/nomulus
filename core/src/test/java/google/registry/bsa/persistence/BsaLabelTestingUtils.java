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

package google.registry.bsa.persistence;

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import org.joda.time.DateTime;

/** Testing utils for users of {@link BsaLabel}. */
public final class BsaLabelTestingUtils {

  private BsaLabelTestingUtils() {}

  public static void persistBsaLabel(String domainLabel, DateTime creationTime) {
    tm().transact(() -> tm().put(new BsaLabel(domainLabel, creationTime)));
  }
}
