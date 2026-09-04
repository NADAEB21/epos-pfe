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

/** Per-student outcome of a bulk enrolment from the directory (#186). */
export interface BulkEnrolLigne {
  etudiantId: number;
  nom: string | null;
  prenom: string | null;
  statut: 'ENROLLED' | 'ALREADY_ENROLLED' | 'ERROR';
  message: string | null;
}

/**
 * Summary of a bulk enrolment (scoring BulkEnrolResult — #186, POST
 * /participations/bulk?examenId=X). Same honesty contract as {@link ImportResult}:
 * ALREADY_ENROLLED is never counted as an error.
 */
export interface BulkEnrolResult {
  total: number;
  enrolled: number;
  alreadyEnrolled: number;
  errors: number;
  lignes: BulkEnrolLigne[];
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
 * ADR-0017 §3 / #296 — corps de la suppléance en pleine épreuve
 * (POST /lots/{lotId}/stations/{stationId}/remplacer-evaluateur).
 *
 * Le motif est OBLIGATOIRE côté serveur (@NotBlank, ≤500) : une suppléance
 * doit pouvoir s'expliquer après coup, comme un réajustement de note
 * (ADR-0013). Un champ facultatif serait vide neuf fois sur dix.
 */
export interface RemplacerEvaluateurRequest {
  nouvelEvaluateurId: number;
  motif: string;
}

/**
 * Bilan d'une suppléance. `rotationsTransferees` et `rotationsConservees`
 * arrivent séparément parce que c'est LA question du responsable : le travail
 * déjà fait reste-t-il au nom de celui qui l'a fait ? Oui — seules les
 * rotations non terminées changent de main.
 */
export interface SubstitutionResult {
  lotId: number;
  stationId: number;
  ancienEvaluateur: number;
  nouvelEvaluateur: number;
  rotationsTransferees: number;
  rotationsConservees: number;
  message: string;
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
   * #306 — l'identifiant de QUI a ouvert cette vague : le conducteur de l'épreuve.
   *
   * Le backend renvoie l'id, pas un nom — scoring n'a aucun client vers auth et n'en gagne
   * pas un pour un libellé. Le Suivi le résout avec l'annuaire qu'il charge déjà.
   * `null` pour une vague ouverte avant la migration V18 : on affiche alors « — ».
   */
  ouvertPar: number | null;
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
  /** #361/#363 — les DEUX dénominateurs (ADR-0030 D4) : max déclaré au snapshot
   * V19 (null pré-V19) ; score/max sous le barème de délibération COURANT
   * (null sans barème, ou station exclue). Servis BRUTS — la reconversion /20
   * est un choix d'écran. */
  maxOriginal?: number | null;
  scoreDelibere?: number | null;
  maxDelibere?: number | null;
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
  /** #361/#363 — Σ note_max des stations snapshotées (null si couverture
   * incomplète / pré-V19) ; total et dénominateur sous le barème de délibération
   * COURANT, null sans barème ; numéro de la version appliquée. Toujours les
   * deux lectures (ADR-0030 D4). */
  denominateurOriginal?: number | null;
  totalDelibere?: number | null;
  denominateurDelibere?: number | null;
  baremeVersion?: number | null;
}

// ─── Barème de délibération (#361 N7 / #363 N9, ADR-0030) — camelCase (DTO Résultats) ───

/** L'énumération FERMÉE de scoring (TypeOperationBareme) — les 3 opérations d'ADR-0021 D8. */
export type TypeOperationBareme = 'EXCLURE_CRITERE' | 'EXCLURE_STATION' | 'REPONDERER';

/**
 * Une opération, forme EXACTE du fil scoring (`BaremeDeliberationRequest.OperationRequest`).
 * ai-service rend la même forme dans `operations_a_soumettre` : le client la
 * POSTe telle quelle. Exactement une cible : `cibleItemId` (critère) OU
 * `cibleStationId` (station) ; `nouvelleEchelle` seulement pour REPONDERER.
 */
export interface OperationBareme {
  type: TypeOperationBareme;
  cibleItemId: number | null;
  cibleStationId: number | null;
  nouvelleEchelle: number | null;
}

/** POST /notations/examen/{id}/bareme-deliberation — motif OBLIGATOIRE, versions COMPLÈTES (vide = retour à l'origine). */
export interface BaremeDeliberationRequest {
  motif: string;
  operations: OperationBareme[];
}

/** Une version du barème (BaremeDeliberationDTO) — immuable, historique visible. */
export interface BaremeDeliberation {
  id: number;
  examenId: number;
  version: number;
  motif: string;
  creePar: number | null;
  createdAt: string | null;
  operations: OperationBareme[];
}

// ─── Propositions du module IA (#362 N8, ADR-0021 D8/D10) — snake_case verbatim, opérations en camelCase (fil scoring) ───

/** Le résumé d'une distribution de totaux (médiane, taux de réussite = fraction 0–1, dénominateur brut). */
export interface ResumeEffetAi {
  n_etudiants: number;
  denominateur: number | null;
  mediane: number | null;
  moyenne: number | null;
  taux_reussite: number | null;
}

/** L'effet PROJETÉ avant décision (D10) : à l'origine, au barème courant, au barème proposé. */
export interface EffetProjeteAi {
  origine: ResumeEffetAi;
  avant: ResumeEffetAi;
  apres: ResumeEffetAi;
}

/** Un déclencheur chiffré : l'indice, sa valeur, le seuil de NOTRE choix, la règle. */
export interface DeclencheurAi {
  code: string;
  valeur: number | null;
  ic: [number, number] | null;
  n: number | null;
  seuil: number;
  regle: string;
  [k: string]: unknown;
}

export interface DecisionPropositionAi {
  decision: 'ACCEPTER' | 'REFUSER';
  motif: string | null;
  decide_par: number | null;
  decide_a: string | null;
  bareme_version_resultat: number | null;
  /** L'id de la ligne qui porte l'acte (≠ id courant si la version de base a changé). */
  proposition_id: string;
}

export interface CibleAi {
  item_id?: number;
  libelle?: string | null;
  type?: string | null;
  grille_id?: number;
  station_id?: number | null;
  max?: number | null;
}

export interface PropositionAi {
  proposition_id: string;
  rang_defendabilite: number;
  lecture_code: string;
  operation: OperationBareme;
  /** La version COMPLÈTE à POSTer à scoring (courante + opération). */
  operations_a_soumettre: OperationBareme[];
  cible: CibleAi;
  declencheur: DeclencheurAi[];
  /** null quand la couverture snapshot est incomplète (rien de tenable à projeter). */
  effet_projete: EffetProjeteAi | null;
  deja_appliquee: boolean;
  decision: DecisionPropositionAi | null;
}

/** Le silence est dit : ce que le module n'a PAS proposé, et pourquoi (raison backend VERBATIM). */
export interface LectureSansPropositionAi {
  code: string;
  lecture_code?: string;
  operation?: OperationBareme;
  cible?: CibleAi;
  declencheur?: DeclencheurAi[];
  station_id?: number | null;
  grille_id?: number | null;
  details: Record<string, unknown>;
  raison: string;
}

/** GET /ai/examens/{id}/propositions */
export interface PropositionsExamen {
  examen_id: number;
  entrees_hash: string;
  moteur_version: string;
  bareme_courant: { version: number; operations: OperationBareme[] } | null;
  couverture_snapshot_complete: boolean;
  seuils: Record<string, number>;
  propositions: PropositionAi[];
  lectures_sans_proposition: LectureSansPropositionAi[];
}

/** POST /ai/examens/{id}/propositions/{pid}/decision */
export interface DecisionRequestAi {
  decision: 'ACCEPTER' | 'REFUSER';
  motif: string;
  bareme_version_resultat: number | null;
}

/** POST /ai/examens/{id}/projection — prévisualisation D10 d'une composition manuelle. */
export interface ProjectionAi {
  examen_id: number;
  bareme_courant: { version: number; operations: OperationBareme[] } | null;
  operations: OperationBareme[];
  couverture_snapshot_complete: boolean;
  max_delibere_par_station: Record<string, number>;
  max_original_par_station: Record<string, number>;
  effet_projete: EffetProjeteAi | null;
}

/**
 * One station's grille barème AS IT ACTUALLY GRADED (scoring
 * StationGrilleSnapshotDTO — GET /notations/examen/{examenId}/grilles, #355).
 * Served from scoring's exam_grille_snapshot (ADR-0015), NOT the live
 * exam-service grille, which may have moved since the exam. `items` carries the
 * verbatim item tree captured at launch — same {@link GrilleItem} shape as the
 * live endpoint. An exam launched before V19 has no snapshot rows: the list is
 * empty and the Résultats screen falls back to the live grille, saying so.
 */
export interface StationGrilleSnapshot {
  stationId: number;
  grilleId: number | null;
  nom: string;
  noteMax: number | null;
  items: GrilleItem[] | null;
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
  /**
   * #134 — false = matière RETIRÉE du catalogue. La liste serveur reste
   * complète (les libellés des examens et rôles historiques en dépendent) ;
   * ce sont les PICKERS qui excluent les retirées. Jamais de DELETE :
   * matiere_id traverse les services en clé logique (ADR-0006).
   */
  active: boolean;
  /** #134 — provenance du retrait (qui, quand, pourquoi), même doctrine que #289. */
  retiredAt?: string | null;
  retiredBy?: number | null;
  retirementMotif?: string | null;
}

/** Body for POST/PUT /matieres (auth-service MatiereRequest). #134. */
export interface MatiereRequest {
  code: string;
  libelle: string;
}

/** #134 — one row of the bulk import payload (no client-side constraints: the server verdicts per row). */
export interface MatiereImportRow {
  code: string;
  libelle: string;
}

/** #134 — per-row verdict of POST /matieres/import. `ligne` is 1-based in the sent payload. */
export interface MatiereImportRowResult {
  ligne: number;
  code: string;
  statut: 'CREATED' | 'DUPLICATE' | 'ERROR';
  message: string;
}

export interface MatiereImportResult {
  crees: number;
  doublons: number;
  erreurs: number;
  rows: MatiereImportRowResult[];
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
  /**
   * #289 — le « pourquoi » d'un compte fermé. Null sur les comptes fermés avant
   * la V3 : l'écran le dit honnêtement plutôt que d'inventer un motif.
   */
  deactivatedAt?: string | null;
  deactivatedBy?: number | null;
  deactivationMotif?: string | null;
  /** Full grant list — a person holds SEVERAL roles on one account (auth doctrine). */
  roles: RoleAssignment[];
  /** #389 — renseigné UNIQUEMENT sur la réponse de création (absent des listes). */
  invitation?: InvitationStatus | null;
}

/**
 * #389 — ce qui s'est passé côté messagerie après la création ou le renvoi.
 * `simulee` = messagerie désactivée (`app.mail.enabled=false`) : RIEN n'est
 * parti, l'écran doit le dire (précédent scoring : `EnvoiConvocationsResult.simule`).
 */
export interface InvitationStatus {
  envoyee: boolean;
  simulee: boolean;
}

/**
 * Body for POST /users (auth-service UserCreateRequest). #389 : `password` est
 * OPTIONNEL — omis, le serveur pose un jetable et envoie une invitation
 * « choisissez votre mot de passe » (lien 7 jours, usage unique). Le web ne
 * génère ni n'affiche plus jamais de mot de passe. S'il est fourni, la
 * politique serveur s'applique (min 8, une majuscule, un chiffre).
 */
export interface UserCreateRequest {
  email: string;
  password?: string;
  nom: string;
  prenom: string;
  roles: RoleAssignment[];
}

// ─── Module IA/BI (#359, ADR-0029) — snake_case verbatim du fil (convention scoring) ───

/**
 * Le résultat d'UN indice psychométrique, contrat de refus compris (ADR-0021 D2,
 * ADR-0029 D6) : sous les effectifs minimaux, `statut` est NON_CONCLUANT et
 * `raison` porte le gabarit français EXACT du backend — l'écran l'affiche
 * VERBATIM, il ne compose jamais son propre texte (les seuils vivent côté
 * moteur, le client ne les re-dérive pas).
 */
export interface IndiceAi {
  code: string;
  statut: 'CONCLUANT' | 'NON_CONCLUANT';
  /** L'effectif de CET indice — généralement < nVerrouillees de la carte (les
   * notations sans détail complet en sortent) : toujours affiché avec la valeur. */
  n: number;
  valeur: number | null;
  /** IC 95 % bootstrap [lo, hi], null quand refusé ou inestimable. */
  ic: [number, number] | null;
  raison: string | null;
  details: Record<string, unknown>;
}

/** Ce que le moteur a écarté — compté et DIT, jamais silencieux (#269). */
export interface ExclusionsAi {
  saisi_par_null: number;
  detail_incomplet: number;
  notations_analysees: number;
  /** Notations verrouillées SANS AUCUN item — invisibles des vues (V23). */
  sans_aucun_item: number;
}

export interface IndiceCritereAi {
  item_id: number;
  libelle: string;
  type: string;
  grille_id: number;
  station_id: number;
  difficulte: IndiceAi;
  discrimination: IndiceAi;
}

export interface IndiceGrilleAi {
  grille_id: number;
  station_id: number;
  alpha_cronbach: IndiceAi;
}

export interface IndiceStationAi {
  station_id: number;
  concentration_echec: IndiceAi;
}

/** GET /ai/examens/{id}/indices — servi depuis le cache ai_db (clé = entrees_hash). */
export interface IndicesExamen {
  examen_id: number;
  entrees_hash: string;
  moteur_version: string;
  exclusions: ExclusionsAi;
  par_critere: IndiceCritereAi[];
  par_grille: IndiceGrilleAi[];
  par_station: IndiceStationAi[];
}

// ── BI (#365 / N10) — la face transversale : mêmes agrégats, autre échelle ──

/** Une lecture BI fermée (ai-service `app/bi.py`) — la `raison` s'affiche VERBATIM. */
export interface LectureBiAi {
  code: string;
  raison: string;
}

/** `projection.resume` côté ai-service : réussite = total >= dénominateur / 2. */
export interface ResumeBiAi {
  n_etudiants: number;
  denominateur: number | null;
  mediane: number | null;
  moyenne: number | null;
  taux_reussite: number | null;
}

export interface HistogrammeBinAi {
  label: string;
  count: number;
  pct: number;
  sousSeuil: boolean;
}

export interface StationTendanceAi {
  station_id: number;
  n: number;
  echecs: number;
  taux_echec: number;
  mediane: number;
  note_max: number;
}

/** Une session CLOSE d'une matière — la carte BI (jamais de ligne par étudiant). */
export interface ExamenTendanceAi {
  examen_id: number;
  nom: string | null;
  date_examen: string | null;
  statut: string;
  entrees_hash: string;
  moteur_version: string;
  n_notations_verrouillees: number;
  couverture_snapshot_complete: boolean;
  origine: ResumeBiAi;
  delibere: ResumeBiAi | null;
  /** #401 — la lecture qui FAIT le résultat : `delibere` quand une version est servie, `origine` sinon. */
  lecture: ResumeBiAi;
  lecture_officielle: 'DELIBERE' | 'ORIGINE';
  bareme_version: number | null;
  /** Classes sur les totaux de la lecture effective. */
  bins: HistogrammeBinAi[];
  /** Échec par station sous le barème effectif ; les stations exclues sont listées à part. */
  par_station: StationTendanceAi[];
  stations_exclues: number[];
  exclusions: ExclusionsAi & { sans_aucun_item: number };
  lectures: LectureBiAi[];
}

/** GET /ai/matieres/{id}/tendances — sessions closes dans l'ordre des dates. */
export interface TendancesMatiere {
  matiere_id: number;
  examens: ExamenTendanceAi[];
  exclusions: { non_clos: number; sans_snapshot: number; hors_snapshot: number };
  lectures: LectureBiAi[];
}

/** Agrégat poolé (/20) — sous l'effectif minimal, `statut` NON_CONCLUANT + `raison`. */
export interface AgregatBiAi {
  n_etudiants: number;
  mediane_sur_20: number | null;
  taux_reussite: number | null;
  statut: 'CONCLUANT' | 'NON_CONCLUANT';
  raison: string | null;
}

export interface SessionSyntheseAi {
  examen_id: number;
  nom: string | null;
  date_examen: string | null;
  n_etudiants: number;
  taux_reussite: number | null;
  mediane_sur_20: number | null;
  bareme_version: number | null;
  lecture_officielle: 'DELIBERE' | 'ORIGINE';
  lectures: LectureBiAi[];
}

export interface MatiereSyntheseAi extends AgregatBiAi {
  matiere_id: number;
  nb_examens_clos: number;
  nb_avec_bareme_delibere: number;
  dernier_examen: { examen_id: number; nom: string | null; date_examen: string | null } | null;
  hors_snapshot: number;
  sessions: SessionSyntheseAi[];
}

/** GET /ai/faculte/synthese — SUPER_ADMIN, agrégé d'abord (ADR-0021 D5). */
export interface SyntheseFaculte {
  faculte: AgregatBiAi & { nb_matieres: number; nb_examens_clos: number };
  matieres: MatiereSyntheseAi[];
  exclusions: { sans_snapshot: number };
}
