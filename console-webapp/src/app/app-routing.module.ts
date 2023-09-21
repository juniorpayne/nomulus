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

import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { TldsComponent } from './tlds/tlds.component';
import { HomeComponent } from './home/home.component';
import { SettingsComponent } from './settings/settings.component';
import SettingsContactComponent from './settings/contact/contact.component';
import SettingsWhoisComponent from './settings/whois/whois.component';
import SettingsUsersComponent from './settings/users/users.component';
import SettingsSecurityComponent from './settings/security/security.component';
import { RegistrarGuard } from './registrar/registrar.guard';
import { RegistrarComponent } from './registrar/registrarsTable.component';
import { EmptyRegistrar } from './registrar/emptyRegistrar.component';
import ContactComponent from './settings/contact/contact.component';
import WhoisComponent from './settings/whois/whois.component';
import SecurityComponent from './settings/security/security.component';
import UsersComponent from './settings/users/users.component';

const routes: Routes = [
  { path: '', redirectTo: '/home', pathMatch: 'full' },
  { path: 'registrars', component: RegistrarComponent },
  { path: 'empty-registrar', component: EmptyRegistrar },
  { path: 'home', component: HomeComponent, canActivate: [RegistrarGuard] },
  { path: 'tlds', component: TldsComponent, canActivate: [RegistrarGuard] },
  {
    path: SettingsComponent.PATH,
    component: SettingsComponent,
    children: [
      {
        path: '',
        redirectTo: 'registrars',
        pathMatch: 'full',
      },
      {
        path: ContactComponent.PATH,
        component: SettingsContactComponent,
        canActivate: [RegistrarGuard],
      },
      {
        path: WhoisComponent.PATH,
        component: SettingsWhoisComponent,
        canActivate: [RegistrarGuard],
      },
      {
        path: SecurityComponent.PATH,
        component: SettingsSecurityComponent,
        canActivate: [RegistrarGuard],
      },
      {
        path: UsersComponent.PATH,
        component: SettingsUsersComponent,
        canActivate: [RegistrarGuard],
      },
      {
        path: RegistrarComponent.PATH,
        component: RegistrarComponent,
      },
    ],
  },
];

@NgModule({
  imports: [RouterModule.forRoot(routes, { useHash: true })],
  exports: [RouterModule],
})
export class AppRoutingModule {}
