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
import { ProfileApiService } from '../api/profile-api.service';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly tokens = inject(TokenStorageService);
  private readonly store = inject(AuthStore);
  private readonly profile = inject(ProfileApiService);

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

  /**
   * W10 — flux « mot de passe oublié », étape 1. Public et ANTI-ÉNUMÉRATION :
   * le serveur répond 200 que l'adresse existe ou non — l'écran doit afficher
   * le même message dans les deux cas, jamais « adresse inconnue ».
   */
  requestPasswordReset(email: string): Observable<void> {
    return this.http
      .post<ApiResponse<void>>(`${this.baseUrl}/password-reset/request`, { email })
      .pipe(map(() => void 0));
  }

  /**
   * W10 — étape 2 : consomme le code à usage unique (30 min) et pose le
   * nouveau mot de passe. Le refus (code invalide/expiré/déjà utilisé) porte
   * un message serveur à afficher tel quel.
   */
  confirmPasswordReset(token: string, newPassword: string): Observable<void> {
    return this.http
      .post<ApiResponse<void>>(`${this.baseUrl}/password-reset/confirm`, {
        token,
        newPassword,
      })
      .pipe(map(() => void 0));
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
      this.completerProfil();
    } catch {
      this.clearSession();
    }
  }

  private handleAuthSuccess(accessToken: string, refreshToken: string): void {
    this.tokens.setTokens(accessToken, refreshToken);
    const payload = decodeJwt(accessToken);
    const suivant = payloadToCurrentUser(payload);
    // Un refresh remplace le jeton, pas la personne : on garde le profil déjà
    // connu au lieu de le re-demander à chaque rotation de jeton.
    const precedent = this.store.currentUser();
    if (precedent && precedent.userId === suivant.userId) {
      suivant.nom = precedent.nom;
      suivant.prenom = precedent.prenom;
      suivant.primaryRole = precedent.primaryRole;
    }
    this.store.setUser(suivant);
    this.completerProfil();
  }

  /**
   * #389 (R4) — complète l'identité lue dans le JWT (e-mail, rôles) par celle
   * que le serveur SERT (GET /auth/me : nom, prénom, rôle principal) — la même
   * source que l'app mobile. Tir sans attente : la session ne dépend jamais de
   * cet appel ; en échec ou en attente, l'en-tête garde sa lecture de repli
   * (l'e-mail). Rien n'est écrit si la personne a changé entre-temps.
   */
  private completerProfil(): void {
    const courant = this.store.currentUser();
    if (!courant || courant.nom) return;
    const attendu = courant.userId;
    this.profile.me().subscribe({
      next: (me) => {
        const u = this.store.currentUser();
        if (!u || u.userId !== attendu || me.id !== attendu) return;
        this.store.setUser({ ...u, nom: me.nom, prenom: me.prenom, primaryRole: me.role });
      },
      error: () => {
        /* repli : la lecture depuis l'e-mail reste affichée */
      },
    });
  }

  private clearSession(): void {
    this.tokens.clear();
    this.store.clear();
  }
}
