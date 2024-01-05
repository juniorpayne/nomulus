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

package google.registry.bsa.api;

import static google.registry.bsa.BsaStringUtils.DOMAIN_JOINER;
import static google.registry.bsa.BsaStringUtils.PROPERTY_JOINER;
import static google.registry.bsa.BsaStringUtils.PROPERTY_SPLITTER;

import com.google.auto.value.AutoValue;
import java.util.List;

/**
 * A domain name whose second-level domain (SLD) matches a BSA label but is not blocked. It may be
 * already registered, or on the TLD's reserve list.
 */
// TODO(1/15/2024): rename to UnblockableDomain.
@AutoValue
public abstract class UnblockableDomain {
  abstract String domainName();

  abstract Reason reason();

  /** Reasons why a valid domain name cannot be blocked. */
  public enum Reason {
    REGISTERED,
    RESERVED,
    INVALID;
  }

  public String serialize() {
    return PROPERTY_JOINER.join(domainName(), reason().name());
  }

  public static UnblockableDomain deserialize(String text) {
    List<String> items = PROPERTY_SPLITTER.splitToList(text);
    return of(items.get(0), Reason.valueOf(items.get(1)));
  }

  public static UnblockableDomain of(String domainName, Reason reason) {
    return new AutoValue_UnblockableDomain(domainName, reason);
  }

  public static UnblockableDomain of(String label, String tld, Reason reason) {
    return of(DOMAIN_JOINER.join(label, tld), reason);
  }
}
