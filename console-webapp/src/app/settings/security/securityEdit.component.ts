// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

import { HttpErrorResponse } from '@angular/common/http';
import { Component } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { RegistrarService } from 'src/app/registrar/registrar.service';
import {
  SecurityService,
  SecuritySettings,
  apiToUiConverter,
  ipAllowListItem,
} from './security.service';

@Component({
  selector: 'app-security-edit',
  templateUrl: './securityEdit.component.html',
  styleUrls: ['./securityEdit.component.scss'],
})
export default class SecurityEditComponent {
  dataSource: SecuritySettings = {};

  constructor(
    public securityService: SecurityService,
    private _snackBar: MatSnackBar,
    public registrarService: RegistrarService
  ) {
    this.dataSource = apiToUiConverter(registrarService.registrar());
  }

  createIpEntry() {
    this.dataSource.ipAddressAllowList?.push({ value: '' });
  }

  save() {
    this.securityService.saveChanges(this.dataSource).subscribe({
      complete: () => {
        this.goBack();
      },
      error: (err: HttpErrorResponse) => {
        this._snackBar.open(err.error);
      },
    });
  }

  goBack() {
    this.securityService.isEditingSecurity = false;
  }

  removeIpEntry(ip: ipAllowListItem) {
    this.dataSource.ipAddressAllowList =
      this.dataSource.ipAddressAllowList?.filter((item) => item !== ip);
  }
}
