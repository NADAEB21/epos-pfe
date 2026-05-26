import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of, tap, throwError } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  ApiResponse,
  LoginRequest,
  LoginResponse,
  RefreshRequest,
} from './auth.models';
import { AuthStore } from './auth.store';
import { decodeJwt, payloadToCurrentUser } from './jwt.util';
import { TokenStorageService } from './token-storage.service';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly tokens = inject(TokenStorageService);
  private readonly store = inject(AuthStore);

  private readonly baseUrl = `${environment.apiBaseUrl}/auth`;

  login(credentials: LoginRequest): Observable<void> {
    return this.http
      .post<ApiResponse<LoginResponse>>(`${this.baseUrl}/login`, credentials)
      .pipe(
        map((response) => {
          if (!response.data) throw new Error('Empty login response');
          return response.data;
        }),
        tap((data) => this.handleAuthSuccess(data.accessToken, data.refreshToken)),
        map(() => void 0),
      );
  }

  refresh(): Observable<string> {
    const refreshToken = this.tokens.getRefreshToken();
    if (!refreshToken) {
      this.clearSession();
      return throwError(() => new Error('No refresh token'));
    }
    const body: RefreshRequest = { refreshToken };
    return this.http
      .post<ApiResponse<LoginResponse>>(`${this.baseUrl}/refresh`, body)
      .pipe(
        map((response) => {
          if (!response.data) throw new Error('Empty refresh response');
          return response.data;
        }),
        tap((data) => this.handleAuthSuccess(data.accessToken, data.refreshToken)),
        map((data) => data.accessToken),
        catchError((err) => {
          this.clearSession();
          return throwError(() => err);
        }),
      );
  }

  logout(): Observable<void> {
    return this.http.post<ApiResponse<void>>(`${this.baseUrl}/logout`, {}).pipe(
      map(() => void 0),
      catchError(() => of(void 0)),
      tap(() => this.clearSession()),
    );
  }

  hydrate(): void {
    const access = this.tokens.getAccessToken();
    if (!access) return;
    try {
      const payload = decodeJwt(access);
      if (Date.now() >= payload.exp * 1000) {
        this.clearSession();
        return;
      }
      this.store.setUser(payloadToCurrentUser(payload));
    } catch {
      this.clearSession();
    }
  }

  private handleAuthSuccess(accessToken: string, refreshToken: string): void {
    this.tokens.setTokens(accessToken, refreshToken);
    const payload = decodeJwt(accessToken);
    this.store.setUser(payloadToCurrentUser(payload));
  }

  private clearSession(): void {
    this.tokens.clear();
    this.store.clear();
  }
}
