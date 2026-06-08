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

export interface ExamenResponse {
  id: number;
  nom: string;
  matiereId: number;
  dateExamen: string; // yyyy-MM-dd
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
