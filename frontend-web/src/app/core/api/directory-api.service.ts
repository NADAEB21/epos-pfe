import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiResponse, RoleType } from '../auth/auth.models';
import { MatiereResponse, UserResponse } from './models';

/** auth-service directory reads (users + matieres) through the gateway. */
@Injectable({ providedIn: 'root' })
export class DirectoryApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = environment.apiBaseUrl;

  listUsers(role?: RoleType): Observable<UserResponse[]> {
    let params = new HttpParams();
    if (role) params = params.set('role', role);
    return this.http
      .get<ApiResponse<UserResponse[]>>(`${this.baseUrl}/users`, { params })
      .pipe(map((r) => r.data ?? []));
  }

  listMatieres(): Observable<MatiereResponse[]> {
    return this.http
      .get<ApiResponse<MatiereResponse[]>>(`${this.baseUrl}/matieres`)
      .pipe(map((r) => r.data ?? []));
  }
}
