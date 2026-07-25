import { Injectable, computed, inject, signal } from '@angular/core';
import { forkJoin } from 'rxjs';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ScoringApiService } from '../../core/api/scoring-api.service';
import { ExamenResponse, LotSummary, StationSummary, StatutExamen } from '../../core/api/models';
import { isFutureDate, isLaunchDay } from '../../core/api/exam-status';

export interface PreflightCheck {
  label: string;
  ok: boolean;
  /** A blocking check must be green before the lifecycle action is allowed. */
  blocking: boolean;
  hint?: string;
}

export type PrepStepKey =
  | 'examen'
  | 'stations'
  | 'etudiants'
  | 'finalisation'
  | 'lots'
  | 'jours'
  | 'convocations'
  | 'lancement';

/**
 * One step of the preparation stepper (#185). The step ORDER is Nada's spec —
 * créer l'examen → stations & grilles → étudiants → générer les lots →
 * (optionnel) plusieurs jours → convocations → lancer — with one addition the
 * real state machine imposes: « Finaliser la configuration » sits between
 * étudiants and lots, because répartition is gated to CONFIGURE (backend +
 * Lots tab both refuse it at BROUILLON — the very constraint #185 complains
 * was only discoverable by failing). `tracked` steps have an observable
 * done-state; annex steps (jours, convocations) are deliverables with no
 * persisted "done" and never gate the chain.
 */
export interface PrepStep {
  key: PrepStepKey;
  label: string;
  /** Workspace tab segment the step deep-links to. */
  segment: string;
  /** False for annex steps whose completion is not observable server-side. */
  tracked: boolean;
  optional: boolean;
  done: boolean;
  /** Sequential unlock — a required step opens once the previous one is done. */
  unlocked: boolean;
  /** The single next required action (at most one step is current). */
  current: boolean;
  hint: string;
}

/**
 * Per-workspace exam state, shared by the ExamenWorkspaceComponent and every tab
 * under examens/:id. Provided at the examens/:id route (route-scoped, not root),
 * so one instance is created per open workspace and torn down on leave.
 *
 * Why a store rather than each component fetching independently: a tab that
 * mutates the exam's lifecycle (Lancement → changerStatut) must make the parent's
 * status-aware tab list + lifecycle bar update without a manual page refresh.
 * With the exam in a shared signal, the parent's derived tabs recompute the
 * moment a child calls reload().
 *
 * #185: the store also owns the PREPARATION state (stations / roster / lots) and
 * derives both the workspace stepper ({@link prepSteps}) and the Lancement
 * pre-flight checklist ({@link checks}) from it — one source, no drift between
 * the two surfaces. Mutating tabs call {@link reloadPrep} on success so the
 * stepper ticks live.
 */
@Injectable()
export class ExamenWorkspaceStore {
  private readonly examApi = inject(ExamApiService);
  private readonly scoring = inject(ScoringApiService);

  readonly exam = signal<ExamenResponse | null>(null);
  readonly loading = signal(true);
  readonly error = signal(false);

  // ---- preparation data (#185 stepper + Lancement pre-flight) --------------
  readonly stations = signal<StationSummary[]>([]);
  readonly rosterCount = signal(0);
  readonly lots = signal<LotSummary[]>([]);
  readonly prepLoading = signal(true);
  readonly prepError = signal(false);

  /** Tracks the exam currently loaded, so reload() needs no argument. */
  private currentId: number | null = null;

  load(id: number): void {
    this.currentId = id;
    this.loading.set(true);
    this.error.set(false);
    this.examApi.getExamen(id).subscribe({
      next: (e) => {
        this.exam.set(e);
        this.loading.set(false);
        this.loadPrep(id);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
        // Prep never loads without the exam — settle its flags so consumers
        // (Lancement) fall through to their error state, not an eternal skeleton.
        this.prepError.set(true);
        this.prepLoading.set(false);
      },
    });
  }

  /** Re-fetch the current exam — used after a child mutates its lifecycle. */
  reload(): void {
    if (this.currentId != null) this.load(this.currentId);
  }

  /**
   * Re-fetch stations / roster / lots so the stepper + pre-flight reflect a
   * mutation a tab just performed. Skipped while a fetch is already in flight.
   */
  reloadPrep(): void {
    if (this.currentId != null && !this.prepLoading()) this.loadPrep(this.currentId);
  }

  private loadPrep(id: number): void {
    this.prepLoading.set(true);
    this.prepError.set(false);
    forkJoin({
      stations: this.examApi.listStations(id),
      participations: this.scoring.listParticipations(id),
      lots: this.scoring.listLots(id),
    }).subscribe({
      next: ({ stations, participations, lots }) => {
        this.stations.set(stations);
        this.rosterCount.set(participations.length);
        this.lots.set(lots);
        this.prepLoading.set(false);
      },
      error: () => {
        this.prepError.set(true);
        this.prepLoading.set(false);
      },
    });
  }

  // ---- derived preparation state -------------------------------------------

  private readonly statut = computed<StatutExamen | null>(() => this.exam()?.statut ?? null);

  /** Setup phase = the stepper's lifetime; EN_COURS+ hands over to the day-of flow. */
  readonly isSetup = computed(() => {
    const s = this.statut();
    return s === 'BROUILLON' || s === 'CONFIGURE';
  });

  readonly lotsCount = computed(() => this.lots().length);
  private readonly lotJours = computed(() => this.lots().map((l) => l.jour));

  private readonly sansEvaluateur = computed(
    () => this.stations().filter((s) => (s.evaluateurIds?.length ?? 0) === 0).length,
  );
  private readonly sansGrille = computed(
    () => this.stations().filter((s) => !s.hasGrille).length,
  );

  /** Roster is a hard requirement only for the launch edge, not for finalising. */
  private readonly rosterBlocks = computed(() => this.statut() === 'CONFIGURE');

  private readonly dateExamen = computed(() => this.exam()?.dateExamen ?? null);
  /** #147 — launch is allowed on ANY of the exam's lot-days (multi-day). For a
   *  single-day exam (no lot carries a `jour`) this reduces to the exam's own
   *  date (jour J). */
  readonly canLaunchDay = computed(() => isLaunchDay(this.dateExamen(), this.lotJours()));
  /** Hint shown on the date check when today is not (yet) a launch day. */
  private readonly dateHint = computed<string | undefined>(() => {
    const d = this.dateExamen();
    if (!d || this.canLaunchDay()) return undefined;
    return isFutureDate(d)
      ? `Lancement possible le jour J (${this.frDate(d)})`
      : 'Date dépassée — modifiez la date de l’examen pour le relancer';
  });

  private frDate(iso: string): string {
    const [y, m, d] = iso.split('-');
    return d && m && y ? `${d}/${m}/${y}` : iso;
  }

  /** Lancement pre-flight checklist — same base signals as the stepper. */
  readonly checks = computed<PreflightCheck[]>(() => {
    const n = this.stations().length;
    const hasStations = n > 0;
    return [
      {
        label: 'Stations definies',
        ok: hasStations,
        blocking: true,
        hint: hasStations ? `${n} station(s)` : 'Aucune station definie',
      },
      {
        label: 'Un evaluateur par station',
        ok: hasStations && this.sansEvaluateur() === 0,
        blocking: true,
        hint:
          hasStations && this.sansEvaluateur() > 0
            ? `${this.sansEvaluateur()} station(s) sans evaluateur`
            : undefined,
      },
      {
        label: 'Une grille par station',
        ok: hasStations && this.sansGrille() === 0,
        blocking: true,
        hint:
          hasStations && this.sansGrille() > 0
            ? `${this.sansGrille()} station(s) sans grille`
            : undefined,
      },
      {
        label: 'Etudiants inscrits',
        ok: this.rosterCount() > 0,
        blocking: this.rosterBlocks(),
        hint:
          this.rosterCount() > 0
            ? `${this.rosterCount()} etudiant(s)`
            : this.rosterBlocks()
              ? 'Aucun etudiant inscrit — requis pour lancer'
              : 'A inscrire avant le jour J',
      },
      {
        // The pre-flight that replaces #130's "rotations generated": rotations
        // are now built per-lot on exam day, so the launch gate is that the
        // waves are partitioned, not that the circuit exists.
        label: 'Lots repartis',
        ok: this.lotsCount() > 0,
        blocking: this.rosterBlocks(),
        hint:
          this.lotsCount() > 0
            ? `${this.lotsCount()} lot(s)`
            : this.rosterBlocks()
              ? 'Repartissez les etudiants en lots (onglet Lots) — requis pour lancer'
              : 'A repartir avant le jour J',
      },
      {
        // Day-of gate: a CONFIGURE exam can only be launched on one of its
        // lot-days (#147 multi-day; single-day = the exam's own date). Blocking
        // for the launch edge only; never blocks "Finaliser la configuration".
        label: "Jour de l'examen",
        ok: this.canLaunchDay(),
        blocking: this.rosterBlocks(),
        hint: this.canLaunchDay()
          ? "C'est un jour d'examen"
          : this.dateHint() ?? 'A lancer le jour J',
      },
      {
        label: 'Sujet PDF importe',
        ok: this.exam()?.hasPdfSujet ?? false,
        blocking: false,
      },
    ];
  });

  readonly blockersRemaining = computed(
    () => this.checks().filter((c) => c.blocking && !c.ok).length,
  );

  /** The guided preparation path (#185) — order is Nada's spec, verbatim. */
  readonly prepSteps = computed<PrepStep[]>(() => {
    const e = this.exam();
    if (!e) return [];
    const n = this.stations().length;
    const sansEval = this.sansEvaluateur();
    const sansGrille = this.sansGrille();
    const stationsOk = n > 0 && sansEval === 0 && sansGrille === 0;
    const roster = this.rosterCount();
    const rosterOk = roster > 0;
    const lotsN = this.lotsCount();
    const lotsOk = lotsN > 0;
    const finaliseOk = e.statut !== 'BROUILLON';

    const stationsHint =
      n === 0
        ? 'Ajoutez au moins une station'
        : sansEval > 0
          ? `${sansEval} station(s) sans évaluateur`
          : sansGrille > 0
            ? `${sansGrille} station(s) sans grille`
            : `${n} station(s) prête(s)`;

    const steps: PrepStep[] = [
      {
        key: 'examen',
        label: "Créer l'examen",
        segment: 'vue-ensemble',
        tracked: true,
        optional: false,
        done: true,
        unlocked: true,
        current: false,
        hint: 'Nom, date et paramètres du circuit',
      },
      {
        key: 'stations',
        label: 'Stations & grilles',
        segment: 'stations-grilles',
        tracked: true,
        optional: false,
        done: stationsOk,
        unlocked: true,
        current: false,
        hint: stationsHint,
      },
      {
        key: 'etudiants',
        label: 'Étudiants',
        segment: 'etudiants',
        tracked: true,
        optional: false,
        done: rosterOk,
        unlocked: stationsOk,
        current: false,
        hint: rosterOk ? `${roster} étudiant(s) inscrit(s)` : 'Importez la liste (CSV / Excel)',
      },
      {
        // The state machine's own gate: répartition only exists at CONFIGURE,
        // so finalising is a REQUIRED step of the path, not an afterthought.
        key: 'finalisation',
        label: 'Finaliser la configuration',
        segment: 'lancement',
        tracked: true,
        optional: false,
        done: finaliseOk,
        unlocked: stationsOk && rosterOk,
        current: false,
        hint: finaliseOk
          ? 'Configuration verrouillée'
          : 'Verrouillez stations, grilles et étudiants pour débloquer les lots',
      },
      {
        key: 'lots',
        label: 'Générer les lots',
        segment: 'lots',
        tracked: true,
        optional: false,
        done: lotsOk,
        unlocked: finaliseOk,
        current: false,
        hint: lotsOk ? `${lotsN} lot(s) répartis` : 'Répartissez les étudiants en vagues',
      },
      {
        // #147 — Lot.jour exists server-side but has no editing UI yet; the
        // step stays visible (Nada's list) and honest about being unavailable.
        key: 'jours',
        label: 'Plusieurs jours',
        segment: 'lots',
        tracked: false,
        optional: true,
        done: false,
        unlocked: false,
        current: false,
        hint: 'Optionnel — répartir les lots sur plusieurs jours (bientôt disponible)',
      },
      {
        // #227 — printable slips work; emailing is data-blocked (no student
        // email). Annex step: a print run is not observable, so no done-state.
        key: 'convocations',
        label: 'Convocations',
        segment: 'convocations',
        tracked: false,
        optional: true,
        done: false,
        unlocked: lotsOk,
        current: false,
        hint: lotsOk
          ? 'Imprimables — envoi par email indisponible (emails étudiants manquants)'
          : 'Disponibles une fois les lots répartis',
      },
      {
        // The bridge into the day-of conductor (slice 3): during setup this is
        // never done, so the "next step" line ends on it once all else is ✓.
        key: 'lancement',
        label: "Lancer l'examen",
        segment: 'lancement',
        tracked: true,
        optional: false,
        done: !this.isSetup(),
        unlocked: finaliseOk && lotsOk,
        current: false,
        hint: !finaliseOk || !lotsOk
          ? 'Se débloque une fois les lots répartis'
          : this.canLaunchDay()
            ? "Prêt — lancez l'examen"
            : 'Prêt — lancement le jour J',
      },
    ];

    const cur = steps.find((s) => s.tracked && !s.done);
    if (cur) cur.current = true;
    return steps;
  });

  /** The single next required action — the #185 "next step" affordance. */
  readonly nextStep = computed(() => this.prepSteps().find((s) => s.current) ?? null);
}
