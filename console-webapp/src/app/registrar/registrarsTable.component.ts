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

import { Component, ViewChild, ViewEncapsulation } from '@angular/core';
import { Registrar, RegistrarService } from './registrar.service';
import { MatPaginator } from '@angular/material/paginator';
import { MatSort } from '@angular/material/sort';
import { MatTableDataSource } from '@angular/material/table';
import { RegistrarDetailsWrapperComponent } from './registrarDetails.component';

@Component({
  selector: 'app-registrar',
  templateUrl: './registrarsTable.component.html',
  styleUrls: ['./registrarsTable.component.scss'],
  encapsulation: ViewEncapsulation.None,
})
export class RegistrarComponent {
  public static PATH = 'registrars';
  dataSource: MatTableDataSource<Registrar>;
  columns = [
    {
      columnDef: 'registrarId',
      header: 'Registrar Id',
      cell: (record: Registrar) => `${record.registrarId || ''}`,
    },
    {
      columnDef: 'registrarName',
      header: 'Name',
      cell: (record: Registrar) => `${record.registrarName || ''}`,
    },
    {
      columnDef: 'allowedTlds',
      header: 'TLDs',
      cell: (record: Registrar) => `${(record.allowedTlds || []).join(', ')}`,
    },
    {
      columnDef: 'emailAddress',
      header: 'Username',
      cell: (record: Registrar) => `${record.emailAddress || ''}`,
    },
    {
      columnDef: 'ianaIdentifier',
      header: 'IANA ID',
      cell: (record: Registrar) => `${record.ianaIdentifier || ''}`,
    },
    {
      columnDef: 'billingAccountMap',
      header: 'Billing Accounts',
      cell: (record: Registrar) =>
        // @ts-ignore - completely legit line, but TS keeps complaining
        `${Object.entries(record.billingAccountMap).reduce(
          (acc, [key, val]) => {
            return `${acc}${key}=${val}<br/>`;
          },
          ''
        )}`,
    },
    {
      columnDef: 'registryLockAllowed',
      header: 'Registry Lock',
      cell: (record: Registrar) => `${record.registryLockAllowed}`,
    },
    {
      columnDef: 'driveId',
      header: 'Drive ID',
      cell: (record: Registrar) => `${record.driveFolderId || ''}`,
    },
  ];
  displayedColumns = ['edit'].concat(this.columns.map((c) => c.columnDef));

  @ViewChild(MatPaginator) paginator!: MatPaginator;
  @ViewChild(MatSort) sort!: MatSort;
  @ViewChild('registrarDetailsView')
  detailsComponentWrapper!: RegistrarDetailsWrapperComponent;

  constructor(protected registrarService: RegistrarService) {
    this.dataSource = new MatTableDataSource<Registrar>(
      registrarService.registrars
    );
  }

  ngAfterViewInit() {
    this.dataSource.paginator = this.paginator;
    this.dataSource.sort = this.sort;
  }

  openDetails(event: MouseEvent, registrar: Registrar) {
    event.stopPropagation();
    this.detailsComponentWrapper.open(registrar);
  }

  applyFilter(event: Event) {
    const filterValue = (event.target as HTMLInputElement).value;
    this.dataSource.filter = filterValue.trim().toLowerCase();
  }
}
