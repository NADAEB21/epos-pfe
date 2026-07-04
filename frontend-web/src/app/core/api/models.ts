// Wire contracts shared across feature screens. These mirror the backend DTOs
// reachable through the gateway under /api/v1 — keep them in sync with:
//   exam-service    ExamenResponse / StatutExamen / PageResponse
//   scoring-service NotationDTO / EtudiantDTO
//   auth-service    UserResponse / MatiereResponse

export type StatutExamen =
  | 'BROUILLON'
  | 'CONFIGURE'
  | 'EN_COURS'
  | 'TERMINE'
  | 'ARCHIVE';

export type TypeStation = 'PRATIQUE' | 'THEORIQUE';

export type TypeItem = 'BINAIRE' | 'NUMERIQUE';

export interface StationSummary {
  id: number;
  nom?: string;
  ordre?: number;
  type?: TypeStation;
  description?: string | null;
  hasGrille?: boolean;
  evaluateurIds?: number[];
}

/** One scored line of a grille. valeurMax is null for BINAIRE items. */
export interface GrilleItem {
  id: number;
  libelle: string;
  type?: TypeItem;
  ponderation?: number | null;
  valeurMax?: number | null;
  ordre?: number | null;
  categorie?: string | null;
}

/** Grille with its items — only returned by the station detail endpoint. */
export interface GrilleDetail {
  id: number;
  nom: string;
  noteMax?: number | null;
  description?: string | null;
  sommePonderations?: number | null;
  ponderationValide?: boolean;
  nombreItems?: number;
  items?: GrilleItem[];
}

/** GET /stations/{id} — StationSummary plus the embedded grille (if any). */
export interface StationDetail extends StationSummary {
  grille?: GrilleDetail | null;
}

/**
 * A reusable grille template (exam-service GrilleTemplateResponse). Saved from an
 * existing grille (POST /grilles/{id}/templates?nom=) and applied to a station
 * (POST /templates/grilles/{tid}/appliquer/stations/{sid}) — apply is a FULL
 * REPLACE: the backend deletes the station's current grille and recreates it from
 * the template, so the UI confirms before applying onto a station that has one.
 *
 * The library (GET /templates/grilles) is GLOBAL — no matière filter — so a
 * responsable sees every template. Save + apply are open to RESPONSABLE_MATIERE
 * (matière-checked on the source grille / target station server-side); standalone
 * template create + DELETE are SUPER_ADMIN-only, so the responsable surface offers
 * save + apply but NO delete affordance.
 */
export interface GrilleTemplate {
  id: number;
  nom: string;
  description?: string | null;
  noteMax?: number | null;
  nombreItems?: number;
  sommePonderations?: number | null;
  createdAt?: string | null;
  items?: GrilleItem[];
}

/**
 * Body for POST /stations/{id}/grille and PUT /grilles/{id} (exam-service
 * GrilleRequest). nom ≤150, noteMax 1–100 (default 20), description ≤300.
 *
 * `items` exists in the backend DTO for grouped creation, BUT that path
 * (GrilleServiceImpl.creerPourStation) skips both the NUMERIQUE valeurMax
 * validation and the pondération-sum check — only the dedicated POST /items
 * endpoint validates. So we always create the grille meta-only (items omitted /
 * empty) and add critères one-by-one through createGrilleItem, which is the
 * validated path. PUT /grilles/{id} ignores items entirely (only nom/noteMax/
 * description are applied server-side).
 */
export interface GrilleRequest {
  nom: string;
  noteMax: number;
  description?: string;
}

/**
 * Body for POST /grilles/{id}/items and PUT /items/{id} (exam-service
 * ItemRequest). libelle ≤300, ponderation 0.5–20 (required). valeurMax is
 * REQUIRED for NUMERIQUE (>0 and ≤ ponderation) and IGNORED for BINAIRE — the
 * backend nulls it for BINAIRE regardless. `ordre` is server-assigned, never
 * sent. The server rejects an add/edit that pushes the pondération sum above
 * the grille's noteMax (BusinessException → 400).
 */
export interface ItemRequest {
  libelle: string;
  type: TypeItem;
  ponderation: number;
  valeurMax?: number | null;
  categorie?: string;
}

export interface ExamenResponse {
  id: number;
  nom: string;
  matiereId: number;
  dateExamen: string; // yyyy-MM-dd
  heureDebut: string | null; // HH:mm — start of the OSCE circuit
  dureeStationMin: number | null;
  nbEtudiantsParStation: number | null;
  statut: StatutExamen;
  description: string | null;
  hasPdfSujet: boolean;
  pdfSujetNom: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  stations?: StationSummary[];
  // Pause/reprise (ADR-0009). A paused exam stays EN_COURS — pause is orthogonal
  // state, not a status. The Suivi screen computes "effective exam time" =
  // (now − examStart) − totalPauseSec − (enPause ? now − pausedAt : 0), so the
  // pre-computed back-to-back rotation schedule survives breaks / multi-day gaps.
  enPause?: boolean;
  pausedAt?: string | null; // "yyyy-MM-dd HH:mm:ss", null unless enPause
  totalPauseSec?: number;
  // Real launch instant (ADR-0010), "yyyy-MM-dd HH:mm:ss". Null until the exam is
  // launched (CONFIGURE→EN_COURS) and for pre-ADR rows. The Suivi board anchors its
  // clock on this when present, else on the PLANNED start (dateExamen + heureDebut).
  launchedAt?: string | null;
}

/**
 * Body for POST /examens (exam-service ExamenRequest). The exam lands in
 * BROUILLON; matiereId must be one the caller is RESPONSABLE_MATIERE for —
 * the backend @PreAuthorize gates on `ROLE_RESPONSABLE_MATIERE:<matiereId>`.
 * dureeStationMin / nbEtudiantsParStation default server-side (15 / 4) when
 * omitted. Mirror the backend bean validation client-side: nom ≤150,
 * dureeStationMin 1–180, nbEtudiantsParStation 1–10, description ≤500.
 */
export interface CreateExamenRequest {
  nom: string;
  matiereId: number;
  dateExamen: string; // yyyy-MM-dd
  heureDebut?: string; // HH:mm — start of the circuit; defaults server-side to 09:00
  dureeStationMin?: number;
  nbEtudiantsParStation?: number;
  description?: string;
}

/**
 * Body for POST /examens/{id}/stations and PUT /stations/{id} (exam-service
 * StationRequest). nom ≤150, type required, description ≤300. evaluateurIds is
 * OPTIONAL: omitting it on a PUT leaves the existing bindings untouched
 * (StationServiceImpl.modifier only overwrites when non-null), so the metadata
 * edit form leaves it out and the dedicated évaluateur picker owns binding.
 */
export interface StationRequest {
  nom: string;
  type: TypeStation;
  description?: string;
  evaluateurIds?: number[];
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

export interface NotationSummary {
  id: number;
  score_final: number | null;
  verouillee: boolean | null;
  stationId: number | null;
  grilleId: number | null;
  assignmentId: number | null;
}

export interface EtudiantSummary {
  id: number;
  nom?: string;
  prenom?: string;
  numero_inscription?: string;
}

/**
 * Body for POST /etudiants (scoring-service EtudiantDTO). Field names are
 * snake_case verbatim per the scoring convention — `numero_inscription` maps to
 * the record component of the same name. The backend applies NO uniqueness check
 * on numero_inscription (no @Column(unique), no service guard — verified against
 * EtudiantService.saveEtudiant + the Etudiant entity), so a duplicate is silently
 * accepted server-side; the roster screen guards against it client-side.
 */
export interface CreateEtudiantRequest {
  nom: string;
  prenom: string;
  numero_inscription: string;
}

/**
 * One row of a bulk import (POST /etudiants/import?examenId=X). The FE parses
 * CSV/.xlsx (SheetJS) into these; field names mirror the backend
 * ImportEtudiantRequest verbatim (snake_case numero_inscription).
 */
export interface ImportEtudiantRow {
  nom: string;
  prenom: string;
  numero_inscription: string;
}

/** Per-row outcome echoed back by the import endpoint (backend ImportRowResult). */
export interface ImportRowResult {
  ligne: number;
  numero_inscription: string | null;
  nom: string | null;
  prenom: string | null;
  statut: 'CREATED' | 'ENROLLED' | 'ALREADY_ENROLLED' | 'ERROR';
  message: string | null;
}

/**
 * Summary of a bulk import (backend ImportResult). The four counters bucket every
 * row by statut and sum to total.
 */
export interface ImportResult {
  total: number;
  created: number;
  enrolled: number;
  alreadyEnrolled: number;
  errors: number;
  rows: ImportRowResult[];
}

/**
 * Body for POST /participations (scoring-service ParticipationDTO). A
 * participation is the ONLY tie between a student and an exam, so this is what
 * "enrol" means. We send examen_id + etudiantId only; num_echantillon / note /
 * est_present are left null at authoring time (échantillon assignment + presence
 * belong to orchestration / exam day, not the roster). The backend enforces no
 * duplicate-(examen,étudiant) guard and no exam-status gate (verified against
 * ExamenParticipationService.save), so both are guarded client-side.
 */
export interface CreateParticipationRequest {
  examen_id: number;
  etudiantId: number;
  num_echantillon?: string | null;
  est_present?: boolean | null;
  note?: number | null;
}

/**
 * One student's enrolment in a specific exam (scoring-service
 * ExamenParticipation). The only place a student is tied to an exam — the
 * roster of exam X is its participations where examen_id === X, joined to
 * étudiants by etudiantId. Field names mirror the backend DTO verbatim.
 */
export interface ParticipationSummary {
  id: number;
  examen_id: number;
  num_echantillon: string | null;
  note: number | null;
  est_present: boolean | null;
  etudiantId: number | null;
  lotId: number | null;
}

/**
 * One lot (wave) of an exam (scoring-service LotDTO). A lot is a group of
 * students who run the whole circuit together at a scheduled time. `statut`:
 * EN_ATTENTE (répartie, pre-exam) → EN_COURS (presence marked, day-of) → TERMINE.
 */
export interface LotSummary {
  id: number;
  numeroLot: number | null;
  tailleLot: number | null;
  statut: 'EN_ATTENTE' | 'EN_COURS' | 'TERMINE' | null;
  evaluateurId: number | null;
  examenId: number | null;
}

/**
 * Result of POST /lots/examens/{id}/repartir (Phase 1 — CONFIGURE). Partitions
 * the enrolled roster into waves of `lotSize = K stations × nbEtudiantsParStation`.
 * No rotations yet — that's the per-lot, exam-day step.
 */
export interface RepartitionResult {
  lots: number;
  lotSize: number;
  etudiantsRepartis: number;
  details: { lotId: number; numeroLot: number; taille: number }[];
}

/** Result of PATCH /lots/{id}/presence (Phase 2 — exam day). */
export interface PresenceResult {
  lotId: number;
  total: number;
  presents: number;
  absents: number;
}

/**
 * Result of POST /rotations/lots/{lotId}/generer (scoring-service
 * GenerationResult). Counts confirm the OSCE circuit was built without a
 * re-query. `avertissement` is non-null only when a soft constraint was
 * breached (e.g. group size exceeds the configured étudiants/station).
 */
export interface GenerationResult {
  lots: number;
  groupes: number;
  stations: number;
  creneaux: number;
  rotations: number;
  assignments: number;
  etudiantsPresents: number;
  etudiantsAbsents: number;
  avertissement: string | null;
}

/**
 * Live status of a single rotation. PERSISTED value is always EN_ATTENTE — the
 * generator hard-sets it (RotationGenerationService) and nothing on the backend
 * ever flips it (the mobile évaluateur app that would is unbuilt). So the Suivi
 * screen IGNORES this field and computes the live state from the clock instead.
 */
export type RotationStatus = 'EN_ATTENTE' | 'EN_COURS' | 'TERMINE';

/**
 * One slot of the OSCE circuit (scoring-service RotationDTO) — a student group
 * visiting one station at one créneau. `debutCreneau` is the PLANNED wall-clock
 * start (anchored at the exam's dateExamen + heureDebut), so the Suivi timeline
 * derives each slot's window as [debutCreneau, debutCreneau + dureeStationMin).
 * `statut` is persisted-only EN_ATTENTE — see RotationStatus; do not trust it for
 * live state. Field names mirror the backend record verbatim (camelCase here, not
 * the snake_case of the Etudiant/Participation DTOs).
 */
export interface RotationSummary {
  id: number;
  evaluateurId: number | null;
  stationId: number | null;
  ordrePassage: number | null;
  debutCreneau: string; // "yyyy-MM-ddTHH:mm:ss" (LocalDateTime)
  statut: RotationStatus;
  studentGroupId: number | null;
}

/**
 * One student's place in a rotation (scoring-service RotationAssignmentDTO).
 * Joins a rotation to a participation; the Suivi drill-down resolves the student
 * name via participationId → ParticipationSummary.etudiantId → EtudiantSummary.
 */
export interface RotationAssignmentSummary {
  id: number;
  presenceConfirmee: boolean | null;
  tempsAdditionnel: number | null;
  rotationId: number | null;
  participationId: number | null;
}

/**
 * One student's score at one station, inside an {@link ExamenResult} (scoring
 * StationScoreDTO). camelCase — this endpoint follows the Rotation/Assignment DTO
 * convention, NOT the snake_case of Etudiant/Participation. `stationId`/`grilleId`
 * are the cross-service logical FKs; the screen resolves the station name + grille
 * noteMax from exam-service for the column header and the `/max` denominator.
 */
export interface StationScore {
  notationId: number;
  stationId: number | null;
  grilleId: number | null;
  score: number | null;
  verrouillee: boolean | null;
}

/**
 * One student's aggregated result for a whole exam (scoring ExamenResultDTO,
 * issue #90 — GET /notations/examen/{examenId}/results). Computed on the fly by
 * joining Notation → RotationAssignment → ExamenParticipation → Etudiant; the
 * backend returns rows sorted by totalScore desc, so the first row is rank 1.
 * `totalScore` is the plain sum of the per-station `score`s (each out of its
 * grille noteMax) — the screen derives the `/max` + the class average from the
 * grille noteMax it fetches separately.
 */
export interface ExamenResult {
  participationId: number;
  etudiantId: number | null;
  numeroInscription: string | null;
  nom: string | null;
  prenom: string | null;
  numEchantillon: string | null;
  totalScore: number;
  stationsNotees: number;
  stations: StationScore[];
}

/**
 * One scored criterion of a notation (scoring NotationItemDTO — GET
 * /notation-items/notation/{notationId}). The per-critère breakdown behind a
 * station's total, written by the mobile évaluateur app. Field names are
 * snake_case (`item_id`) per the scoring Etudiant/Notation-item convention,
 * EXCEPT `notationId` which the record declares camelCase. `item_id` is the
 * cross-service logical FK to the grille's GrilleItem.id (in exam_db), so the
 * Résultats deep-dive resolves each critère's libelle + barème from the grille.
 * `valeur` is 0/1 for BINAIRE critères (acquis), awarded points for NUMERIQUE.
 * Empty list = no per-critère detail captured (global score only).
 */
export interface NotationItemSummary {
  id: number;
  item_id: number | null;
  valeur: number | null;
  commentaire: string | null;
  notationId: number | null;
}

/**
 * One audited réajustement of a locked notation (scoring NotationAdjustmentDTO —
 * GET /notations/{id}/reajustements, ADR-0013 Part 2). The immutable trail of a
 * responsable/admin correction on a student réclamation: who, when, old→new value
 * (item-level) and old→new total score, plus the required motif. `itemId` is null
 * for a total-level override. Most-recent first.
 */
export interface NotationAdjustmentSummary {
  id: number;
  notationId: number;
  itemId: number | null;
  ancienneValeur: number | null;
  nouvelleValeur: number | null;
  ancienScore: number | null;
  nouveauScore: number | null;
  motif: string;
  adjustedByUserId: number;
  adjustedAt: string;
}

/**
 * Body of POST /notations/{id}/reajustement (ADR-0013 Part 2). `itemId` present →
 * réajuste that critère then recomputes the total; absent → overrides the total
 * directly. `motif` is required (the réclamation reason).
 */
export interface ReajustementRequest {
  itemId?: number | null;
  nouvelleValeur: number;
  motif: string;
}

export interface MatiereResponse {
  id: number;
  code: string;
  libelle: string;
}

export interface UserResponse {
  id: number;
  email: string;
  nom: string;
  prenom: string;
  isActive: boolean;
  createdAt: string | null;
}
