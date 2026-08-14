import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiResponse, RoleType } from '../auth/auth.models';

/** GET /auth/me — l'identité servie par le serveur (pas décodée du JWT). */
export interface MeRole {
  role: RoleType;
  matiereId: number | null;
}

export interface MeResponse {
  id: number;
  email: string;
  nom: string;
  prenom: string;
  /** Rôle principal (compat) — raisonner sur `roles`, pas sur lui. */
  role: RoleType;
  roles: MeRole[];
}

/**
 * W1 (#registre S39) — le « moi » du serveur. Deux endpoints qui existaient
 * depuis des mois sans AUCUN appelant web : GET /auth/me et
 * PUT /auth/change-password (même famille que #276→#280 : un service livré
 * n'est fini que branché).
 */
@Injectable({ providedIn: 'root' })
export class ProfileApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/auth`;

  me(): Observable<MeResponse> {
    return this.http
      .get<ApiResponse<MeResponse>>(`${this.baseUrl}/me`)
      .pipe(map((r) => r.data));
  }

  /**
   * Vérifie le mot de passe ACTUEL avant d'accepter le nouveau (distinct du
   * flux « oublié »). Côté serveur, le succès révoque tous les refresh
   * tokens : l'appelant — et tout autre appareil — doit se reconnecter.
   * L'écran doit donc traiter le succès comme une fin de session, pas comme
   * un simple toast.
   */
  changePassword(currentPassword: string, newPassword: string): Observable<void> {
    return this.http
      .put<ApiResponse<void>>(`${this.baseUrl}/change-password`, {
        currentPassword,
        newPassword,
      })
      .pipe(map(() => undefined));
  }
}
