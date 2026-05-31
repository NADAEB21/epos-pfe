import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiResponse } from '../auth/auth.models';
import { EtudiantSummary, NotationSummary } from './models';

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
}
