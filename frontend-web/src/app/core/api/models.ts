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
  /** Answer key (#162): free-text expected answer — a value, interval, tolerance,
   *  organic compound, or observation. Optional, null when unset. (The backend
   *  also carries a numeric `valeurAttendue` column, left dormant for a possible
   *  future auto-grading feature; the responsable UI uses free text only.) */
  conditionsAttendues?: string | null;
  /** Sub-criteria (#160). Parent item id (null / absent for a top-level critère).
   *  `GET /grilles/{id}/items` returns TOP-LEVEL items only, each with its own
   *  `sousCriteres` nested (children filtered out of the flat list). Grading is
   *  leaf-only: a parent with children is scored via its children, not directly. */
  parentId?: number | null;
  hasSousCriteres?: boolean;
  sousCriteres?: GrilleItem[];
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
 *
 * Answer key (#162): conditionsAttendues (≤1000) is an optional free-text corrigé
 * the backend stores as-is for any type — a value, interval, compound, or phrase.
 */
export interface ItemRequest {
  libelle: string;
  type: TypeItem;
  ponderation: number;
  valeurMax?: number | null;
  categorie?: string;
  conditionsAttendues?: string | null;
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
  email?: string;
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
  email?: string;
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
  email: string;
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
  /**
   * #227 — addresses this file actually filled in. Sits OUTSIDE the four-way
   * partition and does not sum into `total`: re-importing the roster just to add
   * the missing e-mails puts every row on `alreadyEnrolled`, which otherwise
   * reads exactly like "nothing happened".
   */
  emailsRenseignes: number;
  rows: ImportRowResult[];
}

/**
 * Body for PUT /etudiants/{id}. Every field is optional and the backend reads
 * ABSENT as "leave unchanged" — so `{ email }` alone patches only the address.
 * An EMPTY STRING is the explicit "erase this". Never send fields you don't mean
 * to write (#215).
 */
/**
 * One convocation, DERIVED SERVER-SIDE (#227). The arrival time used to be
 * computed in the Angular component; the e-mail sender needs the same rule, and
 * two implementations of one business rule in two languages drift. The backend
 * owns it now and this is the read model — never recompute `heureConvocation`
 * here, or the screen and the student's e-mail can disagree.
 */
export interface Convocation {
  participationId: number;
  etudiantId: number | null;
  nom: string | null;
  prenom: string | null;
  numero_inscription: string | null;
  email: string | null;
  ordre_import: number | null;
  lotId: number | null;
  lotNumero: number | null;
  /** yyyy-MM-dd — the student's own day (lot.jour, else the exam date). */
  jour: string | null;
  /** "HH:mm" — when to show up. */
  heureConvocation: string | null;
  /** ISO instant, or null if this student's convocation was never sent. */
  convocationEnvoyeeA: string | null;
}

/** Per-student outcome of a send. `statut`: ENVOYE | SANS_ADRESSE | ECHEC. */
export interface EnvoiLigne {
  participationId: number;
  nom: string | null;
  prenom: string | null;
  email: string | null;
  statut: 'ENVOYE' | 'SANS_ADRESSE' | 'ECHEC';
  message: string | null;
}

/**
 * Outcome of sending an exam's convocations. `simule` is true when the mail
 * transport is off (the DEFAULT) — nothing actually left, and the UI must say
 * so rather than claim success.
 */
export interface EnvoiConvocationsResult {
  total: number;
  envoyes: number;
  sansAdresse: number;
  echecs: number;
  simule: boolean;
  lignes: EnvoiLigne[];
}

export interface UpdateEtudiantRequest {
  nom?: string;
  prenom?: string;
  numero_inscription?: string;
  email?: string;
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
  /**
   * #256/#227 — 1-based position in the imported roster sheet. The supervisor
   * ruled the sheet's row order IS the official listing order, and convocations
   * are that listing. `null` for students added by hand, who sort last.
   */
  ordre_import?: number | null;
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
  /**
   * ADR-0014-A §5 / #147 — the day this lot runs (yyyy-MM-dd). `null` = it runs
   * on the exam's own `dateExamen` (the single-day default). Set only when a
   * cohort is split across days.
   */
  jour: string | null;
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
 * #265 — one EN_COURS exam sharing évaluateurs with the exam being prepared
 * (GET /examens/{id}/conflits-evaluateurs). Non-empty = launching would
 * double-book a human across two rooms; the backend refuses it at changerStatut.
 */
export interface ConflitEvaluateur {
  examenId: number;
  examenNom: string;
  evaluateurIds: number[];
}

/**
 * #276/#280 — one station whose grille does not let a faultless student reach
 * the announced noteMax (GET /examens/{id}/baremes-incomplets). Non-empty =
 * launching would silently cap the whole cohort; the backend refuses it at
 * changerStatut (ADR-0015 freezes the definition at launch). noteMax and
 * maxAtteignable come separately so the row can say « 10 points saisis sur
 * 20 » — the phrase the responsable can act on.
 */
export interface BaremeIncomplet {
  stationId: number;
  stationNom: string;
  grilleId: number;
  noteMax: number;
  maxAtteignable: number;
}

/**
 * Result of POST /lots/{lotId}/presence-et-demarrer (#185 — « Présence &
 * démarrer », the conductor's single act: presence + rotation generation in one
 * transaction; the wave opening stays delegated to ADR-0014-B).
 */
export interface DemarrageResult {
  lotId: number;
  presents: number;
  absents: number;
  rotations: number;
  assignments: number;
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
 * #208 / #252 — progression du Suivi, DÉRIVÉE PAR LE SERVEUR
 * (GET /lots/examens/{id}/progression).
 *
 * <p><b>Le front ne recalcule rien de tout ceci.</b> C'est justement en re-déduisant l'état
 * depuis `Date.now()` que ce tableau affichait « dépassement — créneau écoulé, encore en cours »
 * sur des rotations qui étaient `TERMINE` en base. Le statut d'une station se LIT ici.
 *
 * <p>Il n'existe volontairement <b>aucune valeur « dépassement »</b> dans ce contrat : ce n'était
 * pas un état mais une opinion de l'horloge sur un travail qu'elle ne voyait pas (ADR-0014).
 */
export interface SuiviProgression {
  examenId: number;
  /** La vague affichée : celle en cours, ou celle qui vient de finir. Null avant toute ouverture. */
  lotOuvert: LotEnCoursProgression | null;
  /** Alerte responsable : la vague est finie ET une suivante attend (ADR-0014-B). */
  lotTermine: boolean;
  /** La vague que « Lot suivant » ouvrira ; null s'il n'en reste aucune. */
  lotSuivant: LotSuivantProgression | null;
  stations: StationProgression[];
}

export interface LotEnCoursProgression {
  id: number;
  numeroLot: number;
  /** Instant d'ouverture réel. Null pour une vague ouverte avant la migration V9. */
  ouvertA: string | null;
  /**
   * #252 — secondes écoulées depuis l'ouverture, **calculées par le serveur**.
   *
   * <p>⚠️ Ne JAMAIS recalculer ceci dans le navigateur. Le conteneur tourne en CEST (UTC+2),
   * le `Clock` applicatif est épinglé Africa/Tunis (UTC+1, ADR-0010) et le poste suit sa
   * propre heure : un `Date.now() - ouvertA` afficherait **+1:00:00 dès l'ouverture d'une
   * vague** (mesuré : serveur 47:31 vs soustraction locale 1h47).
   *
   * <p>⚠️ `null` dès que la vague est TERMINÉE — le compteur s'arrête. Le laisser courir
   * pendant l'attente entre deux vagues recréerait le « +42:16 et croissant » que #243/#252
   * suppriment : le plafond, sous un autre nom.
   */
  ecouleSec: number | null;
  groupesTermines: number;
  groupesTotal: number;
}

export interface LotSuivantProgression {
  id: number;
  numeroLot: number;
  /** Faux tant que le planning du lot n'est pas généré : le bouton doit le dire, pas échouer. */
  rotationsGenerees: boolean;
}

export interface StationProgression {
  stationId: number;
  evaluateurId: number | null;
  /** Groupe actuellement noté ; null si la station a fini sa vague. */
  groupeEnCours: number | null;
  rangEnCours: number | null;
  /** « 2/4 notés » — la seule statistique demandée par le responsable. */
  etudiantsNotes: number;
  etudiantsTotal: number;
  groupesTermines: number;
  groupesTotal: number;
  /** `EN_ATTENTE` | `EN_COURS` | `TERMINE` — lu, jamais calculé depuis l'heure. */
  statut: RotationStatus;
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

/**
 * Disposition of a student complaint in the responsable register (#136).
 * EN_ATTENTE (filed, not yet decided) → ACCEPTEE (upheld) | REJETEE (rejected).
 */
export type ReclamationStatus = 'EN_ATTENTE' | 'ACCEPTEE' | 'REJETEE';

/**
 * A student complaint on an exam result (scoring ReclamationDTO — #136). Filed by
 * a RESPONSABLE_MATIERE / SUPER_ADMIN on the student's behalf (students have no
 * login). camelCase — this is a Java record DTO, NOT the snake_case Etudiant/
 * Participation convention. `notationId`/`adjustmentId` are optional logical FKs;
 * the score change itself is done through the separate réajustement endpoint, so
 * this register only RECORDS the decision (crucially it also records REJETEE, which
 * the réajustement audit trail alone cannot). Resolve fields (`reponse`,
 * `resolvedByUserId`, `resolvedAt`) are null until the complaint is decided.
 */
export interface Reclamation {
  id: number;
  examenId: number;
  participationId: number;
  notationId: number | null;
  objet: string;
  statut: ReclamationStatus;
  reponse: string | null;
  adjustmentId: number | null;
  createdByUserId: number | null;
  createdAt: string; // "yyyy-MM-ddTHH:mm:ss" (LocalDateTime)
  resolvedByUserId: number | null;
  resolvedAt: string | null;
}

/**
 * Body of POST /reclamations (scoring ReclamationRequest). objet is required
 * (≤1000, the complaint reason); notationId is optional (the contested score may
 * not be a specific notation, or the complaint is about the result in general).
 */
export interface ReclamationRequest {
  examenId: number;
  participationId: number;
  notationId?: number | null;
  objet: string;
}

/**
 * Body of PATCH /reclamations/{id}/resoudre (scoring ReclamationResolveRequest).
 * statut must be a terminal decision (ACCEPTEE | REJETEE — never EN_ATTENTE);
 * reponse is the mandatory written justification (≤1000). Decide-ONCE: re-resolving
 * a decided complaint → 400. adjustmentId optionally links the notation_adjustments
 * row when an upheld complaint was corrected via the réajustement flow.
 */
export interface ReclamationResolveRequest {
  statut: Extract<ReclamationStatus, 'ACCEPTEE' | 'REJETEE'>;
  reponse: string;
  adjustmentId?: number | null;
}

export interface MatiereResponse {
  id: number;
  code: string;
  libelle: string;
}

/** One (role, matière) grant — mirrors auth-service RoleAssignmentDto. */
export interface RoleAssignment {
  role: import('../auth/auth.models').RoleType;
  matiereId: number | null;
}

export interface UserResponse {
  id: number;
  email: string;
  nom: string;
  prenom: string;
  isActive: boolean;
  createdAt: string | null;
  /**
   * #294 — fin du verrou TEMPORAIRE (3 mots de passe ratés), ou null. À ne pas
   * confondre avec `isActive`, qui ne porte plus QUE le retrait administratif :
   * les deux causes partageaient un drapeau avant la V2, et leurs remèdes sont
   * opposés (attendre vs voir l'administration).
   */
  lockedUntil?: string | null;
  /** Full grant list — a person holds SEVERAL roles on one account (auth doctrine). */
  roles: RoleAssignment[];
}

/**
 * Body for POST /users (auth-service UserCreateRequest). Password policy is
 * validated server-side too: min 8, at least one uppercase and one digit.
 * There is NO email infrastructure — the creator hands the password to the
 * person directly, which is why the UI generates and displays it once.
 */
export interface UserCreateRequest {
  email: string;
  password: string;
  nom: string;
  prenom: string;
  roles: RoleAssignment[];
}
