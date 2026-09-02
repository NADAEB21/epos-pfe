import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiResponse, RoleType } from '../auth/auth.models';
import {
  InvitationStatus,
  MatiereImportResult,
  MatiereImportRow,
  MatiereRequest,
  MatiereResponse,
  RoleAssignment,
  UserCreateRequest,
  UserResponse,
} from './models';

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
   * #289 — retirer l'accès (POST /users/{id}/desactivation), SUPER_ADMIN seul.
   *
   * <p>Remplace l'ancien `DELETE /users/{id}` : le motif est désormais
   * OBLIGATOIRE et l'auteur est enregistré côté serveur (depuis le JWT). Le
   * serveur refuse aussi qu'on se retire soi-même ou qu'on retire le dernier
   * administrateur actif — deux refus nominatifs à afficher tels quels.
   */
  deactivateUser(userId: number, motif: string): Observable<void> {
    return this.http
      .post<ApiResponse<void>>(`${this.baseUrl}/users/${userId}/desactivation`, { motif })
      .pipe(map(() => undefined));
  }

  /**
   * #389 — renvoyer l'invitation « choisissez votre mot de passe » (lien 7
   * jours, usage unique). La réponse dit si l'e-mail est parti ou si la
   * messagerie est désactivée (`simulee`) — l'écran ne suppose jamais le succès.
   */
  resendInvitation(userId: number): Observable<InvitationStatus> {
    return this.http
      .post<ApiResponse<InvitationStatus>>(`${this.baseUrl}/users/${userId}/invitation`, {})
      .pipe(map((r) => r.data));
  }

  /** #289 — rouvrir un compte retiré. Efface aussi tout verrou résiduel (#294). */
  reactivateUser(userId: number, motif: string): Observable<void> {
    return this.http
      .post<ApiResponse<void>>(`${this.baseUrl}/users/${userId}/reactivation`, { motif })
      .pipe(map(() => undefined));
  }

  // ---------------------------------------------------------------------------
  // #134 — catalogue des matières (écritures SUPER_ADMIN seul, ADR-0018 D5)
  // ---------------------------------------------------------------------------

  /** #134 — créer une matière. 409 = code déjà pris (comparé sans la casse). */
  createMatiere(body: MatiereRequest): Observable<MatiereResponse> {
    return this.http
      .post<ApiResponse<MatiereResponse>>(`${this.baseUrl}/matieres`, body)
      .pipe(map((r) => r.data));
  }

  /** #134 — renommer (code et/ou libellé). Les références par id restent intactes. */
  updateMatiere(id: number, body: MatiereRequest): Observable<MatiereResponse> {
    return this.http
      .put<ApiResponse<MatiereResponse>>(`${this.baseUrl}/matieres/${id}`, body)
      .pipe(map((r) => r.data));
  }

  /**
   * #134 — retirer une matière du catalogue actif. Pas de DELETE : les examens
   * passés la référencent en clé logique inter-services (ADR-0006). Motif
   * obligatoire, auteur enregistré côté serveur, réversible — doctrine #289.
   */
  retirerMatiere(id: number, motif: string): Observable<MatiereResponse> {
    return this.http
      .post<ApiResponse<MatiereResponse>>(`${this.baseUrl}/matieres/${id}/retrait`, { motif })
      .pipe(map((r) => r.data));
  }

  /** #134 — rouvrir une matière retirée. */
  reactiverMatiere(id: number, motif: string): Observable<MatiereResponse> {
    return this.http
      .post<ApiResponse<MatiereResponse>>(`${this.baseUrl}/matieres/${id}/reactivation`, { motif })
      .pipe(map((r) => r.data));
  }

  /** #134 — import en lot, verdict par ligne (meilleur effort : les lignes valides passent). */
  importMatieres(rows: MatiereImportRow[]): Observable<MatiereImportResult> {
    return this.http
      .post<ApiResponse<MatiereImportResult>>(`${this.baseUrl}/matieres/import`, rows)
      .pipe(map((r) => r.data));
  }
}
