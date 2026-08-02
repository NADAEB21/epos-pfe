import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiResponse, RoleType } from '../auth/auth.models';
import { MatiereResponse, RoleAssignment, UserCreateRequest, UserResponse } from './models';

/** auth-service directory (users + matieres) through the gateway. */
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

  /**
   * Create a person (POST /users). The backend enforces the delegation matrix
   * (UserService.validateDelegation): a responsable may create EVALUATEUR
   * (global) or RESPONSABLE_MATIERE within their own matière, never SUPER_ADMIN.
   * 409 = email already exists — one email is one person; add a role to the
   * existing account instead (addRoles).
   */
  createUser(body: UserCreateRequest): Observable<UserResponse> {
    return this.http
      .post<ApiResponse<UserResponse>>(`${this.baseUrl}/users`, body)
      .pipe(map((r) => r.data));
  }

  /**
   * ADD roles to an existing account (POST /users/{id}/roles) — additive and
   * idempotent, never touches roles already held. This is the sanctioned path
   * for « cette personne existe déjà » (e.g. appoint an existing user
   * co-responsable, or make a responsable also évaluateur).
   */
  addRoles(userId: number, roles: RoleAssignment[]): Observable<void> {
    return this.http
      .post<ApiResponse<void>>(`${this.baseUrl}/users/${userId}/roles`, roles)
      .pipe(map(() => undefined));
  }

  /**
   * Deactivate an account (DELETE /users/{id}) — SUPER_ADMIN only. Soft:
   * is_active=false + all refresh tokens revoked. There is NO reactivation
   * endpoint today — reversing this requires SQL (known backend gap).
   */
  deactivateUser(userId: number): Observable<void> {
    return this.http
      .delete<ApiResponse<void>>(`${this.baseUrl}/users/${userId}`)
      .pipe(map(() => undefined));
  }
}
