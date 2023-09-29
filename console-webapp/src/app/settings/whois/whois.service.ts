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

import { Injectable } from '@angular/core';
import { switchMap } from 'rxjs';
import { Address, RegistrarService } from 'src/app/registrar/registrar.service';
import { BackendService } from 'src/app/shared/services/backend.service';

export interface WhoisRegistrarFields {
  ianaIdentifier?: number;
  icannReferralEmail?: string;
  localizedAddress?: Address;
  registrarId?: string;
  url?: string;
  whoisServer?: string;
}

@Injectable()
export class WhoisService {
  whoisRegistrarFields: WhoisRegistrarFields = {};

  constructor(
    private backend: BackendService,
    private registrarService: RegistrarService
  ) {}

  saveChanges(newWhoisRegistrarFields: WhoisRegistrarFields) {
    return this.backend.postWhoisRegistrarFields(newWhoisRegistrarFields).pipe(
      switchMap(() => {
        return this.registrarService.loadRegistrars();
      })
    );
  }
}
