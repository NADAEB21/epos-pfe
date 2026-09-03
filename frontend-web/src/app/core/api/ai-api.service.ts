import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiResponse } from '../auth/auth.models';
import {
  DecisionPropositionAi,
  DecisionRequestAi,
  IndicesExamen,
  OperationBareme,
  ProjectionAi,
  PropositionsExamen,
  SyntheseFaculte,
  TendancesMatiere,
} from './models';

/**
 * ai-service (#359, #362, ADR-0029) — lectures d'analyse et propositions à
 * travers le gateway (`/api/v1/ai/**`, route statique D4). Même enveloppe
 * ApiResponse que les services Java ; les refus (401/403/404/409/503) portent
 * leur message dans `message`, à afficher VERBATIM.
 *
 * PAS de catchError ici — la politique d'erreur appartient à l'écran hôte
 * (ADR-0021 D4 : l'écran de délibération ne dépend JAMAIS du module IA ; c'est
 * l'appelant qui replie 403/409/501/503/réseau vers son état « absents »).
 *
 * Le module ne write JAMAIS vers scoring (ADR-0029 D2) : l'acceptation d'une
 * proposition est un POST du client vers scoring (porte N7), PUIS une décision
 * journalisée ici — deux artefacts, deux propriétaires (ADR-0030 D1).
 */
@Injectable({ providedIn: 'root' })
export class AiApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/ai`;

  /** Les indices psychométriques d'un examen CLOS — cache ai_db, contrat de refus inclus. */
  getIndices(examenId: number): Observable<IndicesExamen> {
    return this.http
      .get<ApiResponse<IndicesExamen>>(`${this.baseUrl}/examens/${examenId}/indices`)
      .pipe(map((r) => r.data));
  }

  /** #362 — propositions D8 rangées par défendabilité, effet projeté AVANT décision (D10). Journalisées côté ai_db. */
  getPropositions(examenId: number): Observable<PropositionsExamen> {
    return this.http
      .get<ApiResponse<PropositionsExamen>>(`${this.baseUrl}/examens/${examenId}/propositions`)
      .pipe(map((r) => r.data));
  }

  /** #362 — l'acte journalisé (ACCEPTER après le POST scoring, ou REFUSER), une seule fois (409 sinon). */
  deciderProposition(
    examenId: number,
    propositionId: string,
    body: DecisionRequestAi,
  ): Observable<DecisionPropositionAi> {
    return this.http
      .post<ApiResponse<DecisionPropositionAi>>(
        `${this.baseUrl}/examens/${examenId}/propositions/${propositionId}/decision`,
        body,
      )
      .pipe(map((r) => r.data));
  }

  /** #362 — prévisualisation D10 d'une composition MANUELLE (même arithmétique que scoring), pure lecture. */
  projeter(examenId: number, operations: OperationBareme[]): Observable<ProjectionAi> {
    return this.http
      .post<ApiResponse<ProjectionAi>>(`${this.baseUrl}/examens/${examenId}/projection`, { operations })
      .pipe(map((r) => r.data));
  }

  /** #365 (N10) — les sessions CLOSES d'une matière dans le temps (responsable de la matière, ou admin). */
  getTendances(matiereId: number): Observable<TendancesMatiere> {
    return this.http
      .get<ApiResponse<TendancesMatiere>>(`${this.baseUrl}/matieres/${matiereId}/tendances`)
      .pipe(map((r) => r.data));
  }

  /** #365 (N10) — la synthèse facultaire, SUPER_ADMIN, agrégée d'abord (ADR-0021 D5 : jamais par étudiant). */
  getSynthese(): Observable<SyntheseFaculte> {
    return this.http
      .get<ApiResponse<SyntheseFaculte>>(`${this.baseUrl}/faculte/synthese`)
      .pipe(map((r) => r.data));
  }
}
