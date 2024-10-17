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

import { Injectable, signal } from '@angular/core';
import { tap } from 'rxjs';
import { RegistrarService } from '../registrar/registrar.service';
import { BackendService } from '../shared/services/backend.service';

export interface CreateAutoTimestamp {
  creationTime: string;
}

export interface User {
  emailAddress: String;
  role: String;
  password?: String;
}

@Injectable()
export class UsersService {
  users = signal<User[]>([]);

  constructor(
    private backendService: BackendService,
    private registrarService: RegistrarService
  ) {}

  fetchUsers() {
    return this.backendService
      .getUsers(this.registrarService.registrarId())
      .pipe(
        tap((users: User[]) => {
          this.users.set(users);
        })
      );
  }

  createNewUser() {
    return this.backendService
      .createUser(this.registrarService.registrarId())
      .pipe(
        tap((newUser: User) => {
          this.users.set([...this.users(), newUser]);
        })
      );
  }
}
