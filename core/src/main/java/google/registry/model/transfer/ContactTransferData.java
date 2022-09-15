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

package google.registry.model.transfer;

import javax.persistence.Embeddable;

/** Transfer data for contact. */
@Embeddable
public class ContactTransferData extends TransferData {
  public static final ContactTransferData EMPTY = new ContactTransferData();

  @Override
  public boolean isEmpty() {
    return EMPTY.equals(this);
  }

  @Override
  protected Builder createEmptyBuilder() {
    return new Builder();
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  public static class Builder extends TransferData.Builder<ContactTransferData, Builder> {
    /** Create a {@link Builder} wrapping a new instance. */
    public Builder() {}

    /** Create a {@link Builder} wrapping the given instance. */
    private Builder(ContactTransferData instance) {
      super(instance);
    }
  }
}
