import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiResponse } from '../auth/auth.models';
import {
  CreateExamenRequest,
  ExamenResponse,
  GrilleDetail,
  GrilleItem,
  GrilleRequest,
  GrilleTemplate,
  ItemRequest,
  PageResponse,
  StationDetail,
  StationRequest,
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
  // Grilles + items are top-level resources at the gateway (the controller maps
  // /api/grilles/** and /api/items/**, rewritten from /api/v1/** by the gateway).
  private readonly grillesUrl = `${environment.apiBaseUrl}/grilles`;
  private readonly itemsUrl = `${environment.apiBaseUrl}/items`;
  // Grille templates — a global, reusable library. Saving lives under a grille
  // (/grilles/{id}/templates); listing + applying live under /templates/grilles.
  private readonly templatesUrl = `${environment.apiBaseUrl}/templates/grilles`;

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
   * Create an exam from scratch (POST /examens). The exam lands in BROUILLON;
   * the response carries the new id, so callers navigate straight into the
   * workspace (/examens/{id}). The backend gates matiereId on the caller's
   * RESPONSABLE_MATIERE scope — a 403 means the chosen matière is out of scope.
   */
  createExamen(body: CreateExamenRequest): Observable<ExamenResponse> {
    return this.http
      .post<ApiResponse<ExamenResponse>>(this.baseUrl, body)
      .pipe(map((r) => r.data));
  }

  /**
   * Edit an exam's metadata (PUT /examens/{id}). The backend only allows this
   * while BROUILLON (Examen.isModifiable) and 403s a matière out of the caller's
   * scope. The body is the SAME shape as create — matiereId is @NotNull server
   * side, so callers must resend the exam's existing matiereId even though the
   * responsable can't change it. Returns the updated exam.
   */
  updateExamen(id: number, body: CreateExamenRequest): Observable<ExamenResponse> {
    return this.http
      .put<ApiResponse<ExamenResponse>>(`${this.baseUrl}/${id}`, body)
      .pipe(map((r) => r.data));
  }

  /**
   * Delete an exam (DELETE /examens/{id}). The backend gates this to
   * BROUILLON/CONFIGURE only — it 400s (BusinessException) once EN_COURS,
   * TERMINE or ARCHIVE, and 403s a matière out of the caller's scope. It
   * cascades to the exam's stations + grilles (exam-service), but NOT to the
   * roster/lots/notations in scoring-service (cross-DB logical FK, no cascade);
   * those are only ever populated from CONFIGURE onward and are orphaned, not
   * deleted. Returns void.
   */
  deleteExamen(id: number): Observable<void> {
    return this.http
      .delete<ApiResponse<void>>(`${this.baseUrl}/${id}`)
      .pipe(map(() => void 0));
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
   * Pause a running exam (PATCH /examens/{id}/pause — ADR-0009). Pause is
   * orthogonal state: the exam stays EN_COURS, it just stops the effective
   * clock (covers breaks, meal stops, multi-day gaps). Backend 400s unless the
   * exam is EN_COURS and not already paused. Returns the updated exam carrying
   * enPause/pausedAt/totalPauseSec.
   */
  pauseExamen(id: number): Observable<ExamenResponse> {
    return this.http
      .patch<ApiResponse<ExamenResponse>>(`${this.baseUrl}/${id}/pause`, null)
      .pipe(map((r) => r.data));
  }

  /**
   * Resume a paused exam (PATCH /examens/{id}/reprendre). Accumulates the
   * elapsed pause into totalPauseSec and clears pausedAt. Backend 400s unless
   * the exam is currently paused. Returns the updated exam.
   */
  reprendreExamen(id: number): Observable<ExamenResponse> {
    return this.http
      .patch<ApiResponse<ExamenResponse>>(`${this.baseUrl}/${id}/reprendre`, null)
      .pipe(map((r) => r.data));
  }

  // ---- sujet PDF ----------------------------------------------------------

  /**
   * Upload (or replace) the exam's sujet PDF (POST /examens/{id}/pdf). The
   * backend takes a MULTIPART body with the file under the field name `fichier`
   * (NOT JSON) — we build the FormData here and let the browser set the
   * multipart Content-Type/boundary (never set it manually). Server-side: PDF
   * content-type only, ~10 MB max, matière-scoped (403 out of scope). An
   * existing PDF is overwritten. Returns the updated exam (hasPdfSujet=true,
   * pdfSujetNom set).
   */
  uploadPdfSujet(id: number, fichier: File): Observable<ExamenResponse> {
    const form = new FormData();
    form.append('fichier', fichier);
    return this.http
      .post<ApiResponse<ExamenResponse>>(`${this.baseUrl}/${id}/pdf`, form)
      .pipe(map((r) => r.data));
  }

  /**
   * Download the exam's sujet PDF (GET /examens/{id}/pdf). The endpoint is
   * @PreAuthorize-guarded, so it can't be a plain <a href> — it needs the JWT
   * the HttpClient interceptor attaches. We fetch it as a Blob; the caller turns
   * it into an object URL to view/save. 404 if no PDF is attached.
   */
  downloadPdfSujet(id: number): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/${id}/pdf`, { responseType: 'blob' });
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
   * Create a station's grille (POST /stations/{id}/grille). A station may hold
   * at most one grille (server 400s a second). We send meta only (no items) —
   * grouped item creation bypasses server validation, so items are added one by
   * one through createGrilleItem. Gated to BROUILLON/CONFIGURE + matière scope.
   * Returns the new grille (0 items, ponderationValide=false).
   */
  createStationGrille(stationId: number, body: GrilleRequest): Observable<GrilleDetail> {
    return this.http
      .post<ApiResponse<GrilleDetail>>(`${this.stationsUrl}/${stationId}/grille`, body)
      .pipe(map((r) => r.data));
  }

  /**
   * Replace a station's grille in one idempotent call (PUT /stations/{id}/grille,
   * #161). Create-or-replace in place: creates the grille if the station has none,
   * otherwise overwrites its meta and purges its items — no delete→create dance,
   * so the unique station_id constraint can never conflict. Like create, this
   * sends meta only (items are re-added one-by-one through the validated /items
   * endpoint). Gated to BROUILLON/CONFIGURE + matière scope. Returns the grille.
   */
  replaceStationGrille(stationId: number, body: GrilleRequest): Observable<GrilleDetail> {
    return this.http
      .put<ApiResponse<GrilleDetail>>(`${this.stationsUrl}/${stationId}/grille`, body)
      .pipe(map((r) => r.data));
  }

  /**
   * Edit a grille's meta (PUT /grilles/{id}). Only nom/noteMax/description are
   * applied — items are untouched. Note the backend does NOT re-check the items'
   * pondération sum against a lowered noteMax, so the caller should warn when
   * noteMax drops below the current sum (it persists as ponderationValide=false).
   */
  updateGrille(grilleId: number, body: GrilleRequest): Observable<GrilleDetail> {
    return this.http
      .put<ApiResponse<GrilleDetail>>(`${this.grillesUrl}/${grilleId}`, body)
      .pipe(map((r) => r.data));
  }

  /** Delete a grille and all its critères (DELETE /grilles/{id}, cascade). */
  deleteGrille(grilleId: number): Observable<void> {
    return this.http
      .delete<ApiResponse<void>>(`${this.grillesUrl}/${grilleId}`)
      .pipe(map(() => void 0));
  }

  /**
   * Add a critère to a grille (POST /grilles/{id}/items). The server assigns
   * ordre, validates NUMERIQUE valeurMax (>0, ≤ ponderation), and rejects (400)
   * if the new pondération sum would exceed the grille's noteMax. Returns the
   * created item; re-GET the grille afterwards for the recomputed totals/flag.
   */
  createGrilleItem(grilleId: number, body: ItemRequest): Observable<GrilleItem> {
    return this.http
      .post<ApiResponse<GrilleItem>>(`${this.grillesUrl}/${grilleId}/items`, body)
      .pipe(map((r) => r.data));
  }

  /**
   * Edit a critère (PUT /items/{id}). Same NUMERIQUE + sum-overflow rules as
   * create. valeurMax is nulled server-side when type is BINAIRE.
   */
  updateGrilleItem(itemId: number, body: ItemRequest): Observable<GrilleItem> {
    return this.http
      .put<ApiResponse<GrilleItem>>(`${this.itemsUrl}/${itemId}`, body)
      .pipe(map((r) => r.data));
  }

  /** Delete a critère (DELETE /items/{id}). Survivors are re-ordered server-side. */
  deleteGrilleItem(itemId: number): Observable<void> {
    return this.http
      .delete<ApiResponse<void>>(`${this.itemsUrl}/${itemId}`)
      .pipe(map(() => void 0));
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

  /**
   * Add a station to an exam (POST /examens/{id}/stations). Ordre is assigned
   * server-side. Gated to BROUILLON/CONFIGURE (Examen.isGrilleModifiable) and to
   * the caller's matière scope; a duplicate nom within the exam is a 400/409
   * BusinessException. Returns the created station (with its assigned ordre).
   */
  createStation(examenId: number, body: StationRequest): Observable<StationDetail> {
    return this.http
      .post<ApiResponse<StationDetail>>(`${this.baseUrl}/${examenId}/stations`, body)
      .pipe(map((r) => r.data));
  }

  /**
   * Edit a station (PUT /stations/{id}). Same BROUILLON/CONFIGURE gate. Omitting
   * evaluateurIds in the body leaves the existing bindings intact server-side, so
   * the metadata edit form sends only nom/type/description.
   */
  updateStation(stationId: number, body: StationRequest): Observable<StationDetail> {
    return this.http
      .put<ApiResponse<StationDetail>>(`${this.stationsUrl}/${stationId}`, body)
      .pipe(map((r) => r.data));
  }

  /**
   * Delete a station (DELETE /stations/{id}). Cascades to its grille and the
   * backend re-orders the remaining stations. Same BROUILLON/CONFIGURE gate.
   */
  deleteStation(stationId: number): Observable<void> {
    return this.http
      .delete<ApiResponse<void>>(`${this.stationsUrl}/${stationId}`)
      .pipe(map(() => void 0));
  }

  // ---- grille templates (global library) ----------------------------------

  /**
   * The whole template library (GET /templates/grilles). GLOBAL — the backend
   * applies NO matière filter, so every responsable sees every template. Each
   * entry carries its items + computed totals (nombreItems / sommePonderations).
   */
  listGrilleTemplates(): Observable<GrilleTemplate[]> {
    return this.http
      .get<ApiResponse<GrilleTemplate[]>>(this.templatesUrl)
      .pipe(map((r) => r.data));
  }

  /**
   * Save an existing grille as a reusable template (POST /grilles/{id}/templates).
   * `nom` is a QUERY param, not a body. Open to RESPONSABLE_MATIERE (matière-checked
   * on the grille's exam server-side). A duplicate template name is a 400/409
   * BusinessException ("Un template nommé '…' existe déjà"). Returns the new template.
   */
  saveGrilleAsTemplate(grilleId: number, nom: string): Observable<GrilleTemplate> {
    const params = new HttpParams().set('nom', nom);
    return this.http
      .post<ApiResponse<GrilleTemplate>>(`${this.grillesUrl}/${grilleId}/templates`, null, {
        params,
      })
      .pipe(map((r) => r.data));
  }

  /**
   * Apply a template onto a station (POST /templates/grilles/{tid}/appliquer/
   * stations/{sid}). FULL REPLACE: the backend deletes the station's current grille
   * (if any) and recreates it from the template — callers MUST confirm before
   * overwriting an existing grille. Gated to the exam being modifiable
   * (BROUILLON/CONFIGURE → else 400) and the caller's matière scope (→ 403).
   */
  applyTemplateToStation(templateId: number, stationId: number): Observable<void> {
    return this.http
      .post<ApiResponse<void>>(
        `${this.templatesUrl}/${templateId}/appliquer/stations/${stationId}`,
        null,
      )
      .pipe(map(() => void 0));
  }
}
