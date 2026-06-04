import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiResponse } from '../auth/auth.models';
import { EtudiantSummary, NotationSummary, ParticipationSummary } from './models';

/** scoring-service reads through the gateway. Lists are evaluateur-scope filtered (#91). */
@Injectable({ providedIn: 'root' })
export class ScoringApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = environment.apiBaseUrl;

  listNotations(): Observable<NotationSummary[]> {
    return this.http
      .get<ApiResponse<NotationSummary[]>>(`${this.baseUrl}/notations`)
      .pipe(map((r) => r.data ?? []));
  }

  listEtudiants(): Observable<EtudiantSummary[]> {
    return this.http
      .get<ApiResponse<EtudiantSummary[]>>(`${this.baseUrl}/etudiants`)
      .pipe(map((r) => r.data ?? []));
  }

  /**
   * Participations (exam enrolments) filtered to one exam server-side via
   * ?examenId — the backend filter added alongside this screen. Without it the
   * only option was fetching every exam's participations and filtering in the
   * browser, which leaks cross-matière data (#86) and doesn't scale.
   */
  listParticipations(examenId: number): Observable<ParticipationSummary[]> {
    const params = new HttpParams().set('examenId', examenId);
    return this.http
      .get<ApiResponse<ParticipationSummary[]>>(`${this.baseUrl}/participations`, { params })
      .pipe(map((r) => r.data ?? []));
  }
}
