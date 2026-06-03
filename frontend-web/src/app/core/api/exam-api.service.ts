import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiResponse } from '../auth/auth.models';
import { ExamenResponse, PageResponse, StationSummary, StatutExamen } from './models';

export interface ListExamensOptions {
  statut?: StatutExamen;
  page?: number;
  size?: number;
  sort?: string; // e.g. "dateExamen,desc"
}

/** exam-service reads through the gateway. List is paginated + scope-filtered (#95). */
@Injectable({ providedIn: 'root' })
export class ExamApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/examens`;

  listExamens(options: ListExamensOptions = {}): Observable<PageResponse<ExamenResponse>> {
    let params = new HttpParams();
    if (options.statut) params = params.set('statut', options.statut);
    if (options.page != null) params = params.set('page', options.page);
    if (options.size != null) params = params.set('size', options.size);
    if (options.sort) params = params.set('sort', options.sort);
    return this.http
      .get<ApiResponse<PageResponse<ExamenResponse>>>(this.baseUrl, { params })
      .pipe(map((r) => r.data));
  }

  getExamen(id: number): Observable<ExamenResponse> {
    return this.http
      .get<ApiResponse<ExamenResponse>>(`${this.baseUrl}/${id}`)
      .pipe(map((r) => r.data));
  }

  /**
   * Stations of an exam, ordered. This is the canonical source for full station
   * data: unlike the stations embedded in getExamen(id), each row here carries
   * evaluateurIds + hasGrille. Paginated server-side (default 20) — we request a
   * large page since an exam has a handful of stations.
   */
  listStations(examenId: number): Observable<StationSummary[]> {
    const params = new HttpParams().set('size', 100).set('sort', 'ordre,asc');
    return this.http
      .get<ApiResponse<PageResponse<StationSummary>>>(`${this.baseUrl}/${examenId}/stations`, {
        params,
      })
      .pipe(map((r) => r.data.content));
  }
}
