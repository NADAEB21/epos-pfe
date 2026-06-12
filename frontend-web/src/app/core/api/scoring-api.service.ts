import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiResponse } from '../auth/auth.models';
import {
  CreateEtudiantRequest,
  CreateParticipationRequest,
  EtudiantSummary,
  GenerationResult,
  LotSummary,
  NotationSummary,
  ParticipationSummary,
  PresenceResult,
  RepartitionResult,
  RotationAssignmentSummary,
  RotationSummary,
} from './models';

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

  /**
   * Create a student in the GLOBAL directory (POST /etudiants — RESPONSABLE_MATIERE
   * allowed). This does NOT enrol them in any exam; pair it with createParticipation
   * to put a brand-new student on a roster (the 2-step add-new flow). Returns the
   * created student with its server-assigned id.
   */
  createEtudiant(body: CreateEtudiantRequest): Observable<EtudiantSummary> {
    return this.http
      .post<ApiResponse<EtudiantSummary>>(`${this.baseUrl}/etudiants`, body)
      .pipe(map((r) => r.data));
  }

  /**
   * Enrol a student onto an exam (POST /participations — RESPONSABLE_MATIERE
   * allowed). The participation is the only student↔exam link. Returns the
   * created enrolment (carrying its id, needed to remove it later).
   */
  createParticipation(body: CreateParticipationRequest): Observable<ParticipationSummary> {
    return this.http
      .post<ApiResponse<ParticipationSummary>>(`${this.baseUrl}/participations`, body)
      .pipe(map((r) => r.data));
  }

  /**
   * Remove a student from an exam's roster (DELETE /participations/{id}). Deletes
   * only the enrolment — the student stays in the global directory. RESPONSABLE_MATIERE
   * is allowed (the add/remove-symmetry authz fix shipped with this screen); it was
   * previously SUPER_ADMIN-only, which left the roster un-editable by the persona
   * that owns it.
   */
  deleteParticipation(participationId: number): Observable<void> {
    return this.http
      .delete<ApiResponse<void>>(`${this.baseUrl}/participations/${participationId}`)
      .pipe(map(() => void 0));
  }

  /**
   * Lots (waves) of an exam (GET /lots?examenId=). Each lot is a group of
   * students running the circuit together at a scheduled time.
   */
  listLots(examenId: number): Observable<LotSummary[]> {
    const params = new HttpParams().set('examenId', examenId);
    return this.http
      .get<ApiResponse<LotSummary[]>>(`${this.baseUrl}/lots`, { params })
      .pipe(map((r) => r.data ?? []));
  }

  /**
   * Phase 1 — Répartir en lots (POST /lots/examens/{id}/repartir,
   * RESPONSABLE_MATIERE). Partitions the enrolled roster into waves of
   * K stations × nbEtudiantsParStation. Backend-gated to CONFIGURE and
   * re-runnable there (wipes prior lots + any generated plan first). The
   * pre-exam deliverable: students learn their lot + arrival window ahead.
   */
  repartirLots(examenId: number): Observable<RepartitionResult> {
    return this.http
      .post<ApiResponse<RepartitionResult>>(
        `${this.baseUrl}/lots/examens/${examenId}/repartir`,
        null,
      )
      .pipe(map((r) => r.data));
  }

  /**
   * Phase 2 — mark a lot's presence on exam day (PATCH /lots/{id}/presence,
   * RESPONSABLE_MATIERE). Default is "the whole wave showed up"; pass the
   * participation ids that didn't. Flips the lot to EN_COURS, unlocking its
   * rotation generation.
   */
  marquerPresence(lotId: number, absents: number[]): Observable<PresenceResult> {
    return this.http
      .patch<ApiResponse<PresenceResult>>(
        `${this.baseUrl}/lots/${lotId}/presence`,
        { absents },
      )
      .pipe(map((r) => r.data));
  }

  /**
   * Phase 2 — generate a single lot's OSCE rotations (POST
   * /rotations/lots/{lotId}/generer, RESPONSABLE_MATIERE). Builds the
   * Latin-square circuit for that wave's present students so the évaluateurs
   * have a work list. Backend-gated to exam EN_COURS + lot presence marked;
   * re-runnable per lot. Returns the counts summary.
   */
  genererRotationsLot(lotId: number): Observable<GenerationResult> {
    return this.http
      .post<ApiResponse<GenerationResult>>(
        `${this.baseUrl}/rotations/lots/${lotId}/generer`,
        null,
      )
      .pipe(map((r) => r.data));
  }

  /**
   * Every rotation (créneau slot) at one station, across all generated lots
   * (GET /rotations/station/{id}). The Suivi timeline fans this out over the
   * exam's stations. `statut` is persisted EN_ATTENTE only — the live state is
   * clock-derived from `debutCreneau`, not read off the row.
   */
  listRotationsByStation(stationId: number): Observable<RotationSummary[]> {
    return this.http
      .get<ApiResponse<RotationSummary[]>>(`${this.baseUrl}/rotations/station/${stationId}`)
      .pipe(map((r) => r.data ?? []));
  }

  /**
   * The student assignments of one rotation (GET /assignments/rotation/{id}) —
   * one row per student in that slot's group. Fetched lazily by the Suivi
   * drill-down (per station, on expand) to keep the initial load to the
   * station fan-out only.
   */
  listAssignmentsByRotation(rotationId: number): Observable<RotationAssignmentSummary[]> {
    return this.http
      .get<ApiResponse<RotationAssignmentSummary[]>>(
        `${this.baseUrl}/assignments/rotation/${rotationId}`,
      )
      .pipe(map((r) => r.data ?? []));
  }
}
