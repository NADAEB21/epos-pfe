import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiResponse } from '../auth/auth.models';
import {
  CreateEtudiantRequest,
  UpdateEtudiantRequest,
  Convocation,
  EnvoiConvocationsResult,
  CreateParticipationRequest,
  DemarrageResult,
  EtudiantSummary,
  ExamenResult,
  GenerationResult,
  ImportEtudiantRow,
  ImportResult,
  LotSummary,
  RemplacerEvaluateurRequest,
  SubstitutionResult,
  NotationAdjustmentSummary,
  NotationItemSummary,
  NotationSummary,
  ParticipationSummary,
  ReajustementRequest,
  Reclamation,
  ReclamationRequest,
  ReclamationResolveRequest,
  PresenceResult,
  RepartitionResult,
  RotationAssignmentSummary,
  RotationSummary,
  SuiviProgression,
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
   * Per-student aggregated results for one exam (issue #90 — GET
   * /notations/examen/{examenId}/results). The backend joins
   * Notation → RotationAssignment → ExamenParticipation → Etudiant and groups by
   * student, returning rows already sorted by totalScore desc (rank 1 first). An
   * exam with no scored notations yet returns an empty array. Mounted under the
   * /notations prefix — NOT /examens/{id}/results — because the gateway routes
   * /examens/** to exam-service.
   */
  getExamenResults(examenId: number): Observable<ExamenResult[]> {
    return this.http
      .get<ApiResponse<ExamenResult[]>>(`${this.baseUrl}/notations/examen/${examenId}/results`)
      .pipe(map((r) => r.data ?? []));
  }

  /**
   * The per-critère breakdown of one station's notation (GET
   * /notation-items/notation/{notationId} — RESPONSABLE_MATIERE allowed). Powers
   * the Résultats deep-dive: the responsable audits HOW a station total was
   * reached, critère by critère, not just the final score. Returns [] when the
   * mobile app captured only a global score (the common case until per-critère
   * scoring ships) — the screen renders the grille's critères with empty values
   * in that case rather than nothing.
   */
  getNotationItems(notationId: number): Observable<NotationItemSummary[]> {
    return this.http
      .get<ApiResponse<NotationItemSummary[]>>(
        `${this.baseUrl}/notation-items/notation/${notationId}`,
      )
      .pipe(map((r) => r.data ?? []));
  }

  /**
   * Audited réajustement of a LOCKED notation (POST /notations/{id}/reajustement —
   * RESPONSABLE_MATIERE + SUPER_ADMIN only, ADR-0013 Part 2). The only sanctioned
   * way to change a verrouillée score, on a student réclamation: the notation stays
   * locked, and the change is recorded (old→new, motif, who) in one transaction.
   * `motif` is required. Returns the notation with its updated total.
   */
  reajusterNotation(notationId: number, body: ReajustementRequest): Observable<NotationSummary> {
    return this.http
      .post<ApiResponse<NotationSummary>>(
        `${this.baseUrl}/notations/${notationId}/reajustement`,
        body,
      )
      .pipe(map((r) => r.data));
  }

  /**
   * Adjustment history of one notation (GET /notations/{id}/reajustements —
   * RESPONSABLE_MATIERE + SUPER_ADMIN). Most-recent first; empty when the notation
   * was never réajustée. Powers the réclamation trail under the Résultats deep-dive.
   */
  listReajustements(notationId: number): Observable<NotationAdjustmentSummary[]> {
    return this.http
      .get<ApiResponse<NotationAdjustmentSummary[]>>(
        `${this.baseUrl}/notations/${notationId}/reajustements`,
      )
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
   * Patch a directory record (PUT /etudiants/{id} — SUPER_ADMIN or
   * RESPONSABLE_MATIERE). #227: this is what makes a missing or mistyped e-mail
   * fixable in place, instead of forcing a full roster re-import to change one
   * cell.
   *
   * <p>The backend treats an ABSENT field as "leave unchanged" and an EMPTY
   * STRING as "erase", so callers send only what they mean to touch — passing
   * `{ email }` alone cannot damage the student's name. Do NOT spread a whole
   * EtudiantSummary in here "to be safe": that is what made the old endpoint a
   * data-loss vector (#215).
   *
   * <p>The directory is shared across exams, so an address corrected here is
   * corrected for every exam that student sits.
   */
  updateEtudiant(id: number, body: UpdateEtudiantRequest): Observable<EtudiantSummary> {
    return this.http
      .put<ApiResponse<EtudiantSummary>>(`${this.baseUrl}/etudiants/${id}`, body)
      .pipe(map((r) => r.data));
  }

  /**
   * The exam's convocations, already derived server-side (#227) — lot, day and
   * arrival time included, in the official listing order (#256).
   *
   * <p>This replaced a client-side derivation on purpose: the e-mail sender has
   * to compute the same arrival time, and keeping a second copy of that rule in
   * TypeScript guaranteed the screen and the student's e-mail would eventually
   * disagree. Read it, don't recompute it.
   */
  listConvocations(examenId: number): Observable<Convocation[]> {
    return this.http
      .get<ApiResponse<Convocation[]>>(`${this.baseUrl}/convocations/examens/${examenId}`)
      .pipe(map((r) => r.data ?? []));
  }

  /**
   * Sends each reachable student their convocation and records when.
   *
   * <p>Always resolves with a per-student breakdown rather than a bare success:
   * students with no address are counted separately (they are exactly the ones
   * needing a paper slip), and `simule` tells the caller whether anything
   * actually left — mail is DISABLED by default so a demo can't spam a real
   * promotion.
   */
  envoyerConvocations(examenId: number): Observable<EnvoiConvocationsResult> {
    return this.http
      .post<ApiResponse<EnvoiConvocationsResult>>(
        `${this.baseUrl}/convocations/examens/${examenId}/envoyer`,
        null,
      )
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
   * Bulk import + enrol (gap #11 — POST /etudiants/import?examenId=X). The FE
   * parses the CSV/.xlsx into normalized rows; the backend find-or-creates each
   * student by numero_inscription and enrols them on the exam, skipping any
   * already on the roster. Returns a per-row outcome for the result table.
   */
  importEtudiants(examenId: number, rows: ImportEtudiantRow[]): Observable<ImportResult> {
    const params = new HttpParams().set('examenId', examenId);
    return this.http
      .post<ApiResponse<ImportResult>>(`${this.baseUrl}/etudiants/import`, rows, { params })
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
   * ADR-0017 §3 / #296 — remplacer l'évaluateur d'une station EN PLEINE épreuve.
   *
   * <p>Seules les rotations non terminées changent de main : le travail déjà
   * validé reste au nom de son auteur. Le serveur refuse (400) si la personne
   * tient déjà une autre station de la même vague, et (404) si la vague n'a pas
   * démarré — auquel cas il faut réaffecter la station, pas suppléer.
   *
   * <p>N'existait qu'au backend depuis ADR-0017 : aucun écran ne l'appelait,
   * donc la seule sortie d'un examinateur injoignable était une requête à la
   * main. C'est ce que #296 corrige.
   */
  remplacerEvaluateur(
    lotId: number,
    stationId: number,
    body: RemplacerEvaluateurRequest,
  ): Observable<SubstitutionResult> {
    return this.http
      .post<ApiResponse<SubstitutionResult>>(
        `${this.baseUrl}/lots/${lotId}/stations/${stationId}/remplacer-evaluateur`,
        body,
      )
      .pipe(map((r) => r.data));
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
   * Manually move one enrolled student into a target lot (#165 — PATCH
   * /lots/{targetLotId}/etudiants/{participationId}, RESPONSABLE_MATIERE + SUPER_ADMIN).
   * The single-student alternative to re-répartir: re-points one participation's lot
   * without rebuilding the whole partition, and recomputes both lots' sizes. Backend-
   * gated to CONFIGURE (400 once the exam has launched, or if the target lot is in
   * another exam; 404 unknown lot/participation). Returns the updated participation
   * carrying its new lotId.
   */
  deplacerEtudiant(targetLotId: number, participationId: number): Observable<ParticipationSummary> {
    return this.http
      .patch<ApiResponse<ParticipationSummary>>(
        `${this.baseUrl}/lots/${targetLotId}/etudiants/${participationId}`,
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
  /**
   * How many rotations a lot ALREADY has (#188). The Lots screen needs this at load time:
   * without it, a reloaded page shows "Générer" on an already-generated lot and the
   * destructive regeneration fires with no confirmation. Session state does not survive a
   * reload — this does.
   */
  countRotationsLot(lotId: number): Observable<number> {
    return this.http
      .get<ApiResponse<number>>(`${this.baseUrl}/rotations/lot/${lotId}/count`)
      .pipe(map((r) => r.data));
  }

  /**
   * #208 / #252 — progression du jour J, dérivée côté serveur
   * (GET /lots/examens/{id}/progression, RESPONSABLE + ADMIN).
   *
   * Renvoie la vague ouverte et son temps écoulé, l'alerte « lot terminé », la vague que
   * « Lot suivant » ouvrira, et par station le groupe en cours + « N/M notés ».
   * Le composant Suivi ne dérive plus AUCUN statut de l'horloge : il affiche ceci.
   */
  getProgression(examenId: number): Observable<SuiviProgression> {
    return this.http
      .get<ApiResponse<SuiviProgression>>(
        `${this.baseUrl}/lots/examens/${examenId}/progression`,
      )
      .pipe(map((r) => r.data));
  }

  /**
   * #207 / ADR-0014-B — « Lot suivant » : le RESPONSABLE ouvre la vague suivante
   * (POST /lots/{id}/ouvrir). Volontairement fermé à l'évaluateur : un lot est servi
   * simultanément par toutes les stations, donc l'ouvrir engage la salle entière.
   *
   * Refuse (400) tant que la vague précédente n'est pas entièrement validée — garde
   * mesurée sur l'état des rotations, jamais sur une durée.
   */
  ouvrirLot(lotId: number): Observable<void> {
    return this.http
      .post<ApiResponse<void>>(`${this.baseUrl}/lots/${lotId}/ouvrir`, null)
      .pipe(map(() => undefined));
  }

  /**
   * #147 — set (or clear, with `null`) the day a lot runs on. PATCH-only
   * endpoint: the generic lot PUT cannot express "clear" (its null means
   * "leave untouched", #215). CONFIGURE-gated server-side.
   */
  changerJourLot(lotId: number, jour: string | null): Observable<LotSummary> {
    return this.http
      .patch<ApiResponse<LotSummary>>(`${this.baseUrl}/lots/${lotId}/jour`, { jour })
      .pipe(map((r) => r.data));
  }

  /**
   * #185 — « Présence & démarrer » : presence (everyone present unless `absents`
   * given) + rotation generation for the lot, in ONE backend transaction. The
   * wave opening stays ADR-0014-B's business: first wave auto-opens, later
   * waves wait for the responsable's gated « Ouvrir le lot N ».
   */
  presenceEtDemarrer(lotId: number, absents: number[] = []): Observable<DemarrageResult> {
    return this.http
      .post<ApiResponse<DemarrageResult>>(
        `${this.baseUrl}/lots/${lotId}/presence-et-demarrer`,
        { absents },
      )
      .pipe(map((r) => r.data));
  }

  genererRotationsLot(lotId: number): Observable<GenerationResult> {
    return this.http
      .post<ApiResponse<GenerationResult>>(
        `${this.baseUrl}/rotations/lots/${lotId}/generer`,
        null,
      )
      .pipe(map((r) => r.data));
  }

  /**
   * Réinitialiser le planning généré d'un examen — moitié « données » du reset #183
   * (POST /rotations/examens/{examenId}/reset, RESPONSABLE_MATIERE + SUPER_ADMIN).
   * Purge les rotations/groupes de TOUS les lots (les lots/roster/présence sont
   * conservés). Porte le garde-fou #188 : refusé (400) si UNE notation existe ;
   * périmètre matière enforced cross-service (403). À appeler AVANT
   * {@link ExamApiService.resetExamen} : si le planning ne peut pas être purgé (notes
   * existantes), le statut ne doit pas changer. Renvoie le nombre de lots/groupes purgés.
   */
  resetRotationsExamen(examenId: number): Observable<{ lots: number; groupes: number }> {
    return this.http
      .post<ApiResponse<{ lots: number; groupes: number }>>(
        `${this.baseUrl}/rotations/examens/${examenId}/reset`,
        null,
      )
      .pipe(map((r) => r.data));
  }

  // ---- réclamations (student complaint register, #136) -------------------

  /**
   * Every réclamation filed on one exam (GET /reclamations/examen/{examenId} —
   * RESPONSABLE_MATIERE + SUPER_ADMIN). Most-recent first; empty when none filed.
   * Powers the register on the Résultats screen.
   */
  listReclamations(examenId: number): Observable<Reclamation[]> {
    return this.http
      .get<ApiResponse<Reclamation[]>>(`${this.baseUrl}/reclamations/examen/${examenId}`)
      .pipe(map((r) => r.data ?? []));
  }

  /**
   * File a student complaint (POST /reclamations — RESP/ADMIN, returns 201 with
   * statut EN_ATTENTE). The responsable files on the student's behalf (students
   * have no login). Blank objet → 400; unknown participation → 404.
   */
  createReclamation(body: ReclamationRequest): Observable<Reclamation> {
    return this.http
      .post<ApiResponse<Reclamation>>(`${this.baseUrl}/reclamations`, body)
      .pipe(map((r) => r.data));
  }

  /**
   * Decide a pending complaint (PATCH /reclamations/{id}/resoudre — RESP/ADMIN).
   * statut is ACCEPTEE | REJETEE, reponse required. Decide-ONCE: re-resolving a
   * decided complaint → 400. The score change stays the separate réajustement
   * endpoint — this only records the decision. Returns the resolved réclamation.
   */
  resolveReclamation(id: number, body: ReclamationResolveRequest): Observable<Reclamation> {
    return this.http
      .patch<ApiResponse<Reclamation>>(`${this.baseUrl}/reclamations/${id}/resoudre`, body)
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
