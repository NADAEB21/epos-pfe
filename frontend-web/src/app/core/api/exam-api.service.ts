import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiResponse } from '../auth/auth.models';
import {
  ExamenResponse,
  GrilleDetail,
  PageResponse,
  StationDetail,
  StationSummary,
  StatutExamen,
} from './models';

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
  // Stations are a top-level resource at the gateway (/api/v1/stations/**),
  // not nested under /examens — only the list lives under an exam.
  private readonly stationsUrl = `${environment.apiBaseUrl}/stations`;

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
   * Drive the exam lifecycle one legal edge at a time. The backend
   * (PATCH /examens/{id}/statut?statut=…) takes the target status as a QUERY
   * param, not a body, and only validates the state-machine edge is legal
   * (BROUILLON→CONFIGURE→EN_COURS→TERMINE→ARCHIVE) — it does NOT check launch
   * readiness (évaluateurs/grilles/roster). The Lancement screen owns that
   * pre-flight gate client-side; see its component doc. Returns the updated exam.
   */
  changerStatut(id: number, statut: StatutExamen): Observable<ExamenResponse> {
    const params = new HttpParams().set('statut', statut);
    return this.http
      .patch<ApiResponse<ExamenResponse>>(`${this.baseUrl}/${id}/statut`, null, { params })
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

  /**
   * The grille of a station with its items, for the read-only drill-down when a
   * station is expanded (kept out of the tab's initial 2-call fan-out).
   *
   * Hits the dedicated grille endpoint, NOT GET /stations/{id}: the station
   * detail's `grille` field is always null server-side (it only carries
   * hasGrille), so this is the only source for the items + computed pondération.
   */
  getStationGrille(stationId: number): Observable<GrilleDetail> {
    return this.http
      .get<ApiResponse<GrilleDetail>>(`${this.stationsUrl}/${stationId}/grille`)
      .pipe(map((r) => r.data));
  }

  /**
   * Replace a station's évaluateur list (PATCH). The gateway forwards a RAW
   * JSON array body (`[1,2,3]`, empty array clears) — not an object. Returns
   * the updated station, so callers can refresh local state from the response
   * instead of refetching.
   */
  setStationEvaluateurs(stationId: number, evaluateurIds: number[]): Observable<StationDetail> {
    return this.http
      .patch<ApiResponse<StationDetail>>(
        `${this.stationsUrl}/${stationId}/evaluateurs`,
        evaluateurIds,
      )
      .pipe(map((r) => r.data));
  }
}
