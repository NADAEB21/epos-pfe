import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiResponse } from '../auth/auth.models';
import { IndicesExamen } from './models';

/**
 * ai-service (#359, ADR-0029) — lectures d'analyse à travers le gateway
 * (`/api/v1/ai/**`, route statique D4). Même enveloppe ApiResponse que les
 * services Java.
 *
 * PAS de catchError ici — la politique d'erreur appartient à l'écran hôte
 * (ADR-0021 D4 : l'écran de délibération ne dépend JAMAIS du module IA ; c'est
 * l'appelant qui replie 403/409/501/503/réseau vers son état « absents »).
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
}
