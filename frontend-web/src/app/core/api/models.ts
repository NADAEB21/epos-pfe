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
