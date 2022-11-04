// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.persistence.transaction;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.sql.CredentialFactory;

/** Supplier class to provide Credential for Cloud SQL library. */
public class CloudSqlCredentialSupplier implements CredentialFactory {
  private static GoogleCredentials credential;

  /** Initialize the supplier with given credential json and scopes. */
  public static void setupCredentialSupplier(GoogleCredentials credential) {
    System.setProperty(
        CredentialFactory.CREDENTIAL_FACTORY_PROPERTY, CloudSqlCredentialSupplier.class.getName());
    CloudSqlCredentialSupplier.credential = credential;
  }

  @Override
  public HttpRequestInitializer create() {
    return new HttpCredentialsAdapter(credential);
  }
}
