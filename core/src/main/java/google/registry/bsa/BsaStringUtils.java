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

package google.registry.bsa;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import java.util.List;

/** Helpers for domain name manipulation and string serialization of Java objects. */
public class BsaStringUtils {

  public static final Joiner DOMAIN_JOINER = Joiner.on('.');
  public static final Joiner PROPERTY_JOINER = Joiner.on(',');
  public static final Splitter DOMAIN_SPLITTER = Splitter.on('.');
  public static final Splitter PROPERTY_SPLITTER = Splitter.on(',');
  public static final Splitter LINE_SPLITTER = Splitter.on('\n');

  public static String getLabelInDomain(String domainName) {
    List<String> parts = DOMAIN_SPLITTER.limit(1).splitToList(domainName);
    checkArgument(!parts.isEmpty(), "Not a valid domain: [%s]", domainName);
    return parts.get(0);
  }

  public static String getTldInDomain(String domainName) {
    List<String> parts = DOMAIN_SPLITTER.splitToList(domainName);
    checkArgument(parts.size() == 2, "Not a valid domain: [%s]", domainName);
    return parts.get(1);
  }

  private BsaStringUtils() {}
}
