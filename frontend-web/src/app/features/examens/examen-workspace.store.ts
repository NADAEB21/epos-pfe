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
  | 'lots'
  | 'convocations'
  | 'lancement';

/**
 * One step of the preparation stepper (#185, reworked on Nada's test feedback
 * 2026-07-25). The chain is the teacher's mental model, verbatim: créer →
 * stations & grilles → étudiants → générer les lots → convocations (optionnelle,
 * skippable) → lancer. The BROUILLON→CONFIGURE transition is NOT a step — the
 * word « Finaliser » confused the one person this screen is for; the Lots tab
 * performs it silently inside « Générer les lots » (see LotsComponent.repartir).
 *
 * <p>Every step is ALWAYS navigable (Contrôle: the teacher may import students
 * before creating stations if they wish) — `done`/`current` are pure guidance,
 * never gates. The real gates live where they always did: on the actions
 * (backend + per-tab buttons), with explanatory refusals.
 */
export interface PrepStep {
  key: PrepStepKey;
  label: string;
  /** Workspace tab segment the step deep-links to. */
  segment: string;
  optional: boolean;
  done: boolean;
  /** The suggested next action (at most one step is current). */
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

  /**
   * #185 — the Convocations step is optional-but-real: « done » means the
   * teacher either printed/exported the slips (ConvocationsComponent reports it)
   * or explicitly skipped the step from the stepper. Persisted per exam in
   * localStorage — it is a UI acknowledgement, not domain data.
   */
  readonly convocationsFaites = signal(false);

  /** Tracks the exam currently loaded, so reload() needs no argument. */
  private currentId: number | null = null;

  private convocationsKey(id: number): string {
    return `epos.examen.${id}.convocations-faites`;
  }

  marquerConvocationsFaites(): void {
    if (this.currentId == null) return;
    this.convocationsFaites.set(true);
    try {
      localStorage.setItem(this.convocationsKey(this.currentId), '1');
    } catch {
      // Storage unavailable (private mode…) — the in-session signal still holds.
    }
  }

  load(id: number): void {
    this.currentId = id;
    this.loading.set(true);
    this.error.set(false);
    try {
      this.convocationsFaites.set(localStorage.getItem(this.convocationsKey(id)) === '1');
    } catch {
      this.convocationsFaites.set(false);
    }
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

  /**
   * Lancement pre-flight checklist — same base signals as the stepper. Every
   * check is blocking (the only action left on the tab is the launch itself;
   * « Finaliser » no longer exists as a user act), except the soft PDF one.
   */
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
        blocking: true,
        hint:
          this.rosterCount() > 0
            ? `${this.rosterCount()} etudiant(s)`
            : 'Aucun etudiant inscrit — requis pour lancer',
      },
      {
        // The pre-flight that replaces #130's "rotations generated": rotations
        // are now built per-lot on exam day, so the launch gate is that the
        // waves are partitioned, not that the circuit exists.
        label: 'Lots repartis',
        ok: this.lotsCount() > 0,
        blocking: true,
        hint:
          this.lotsCount() > 0
            ? `${this.lotsCount()} lot(s)`
            : 'Repartissez les etudiants en lots (onglet Lots) — requis pour lancer',
      },
      {
        // Day-of gate: launch is only allowed on one of the exam's lot-days
        // (#147 multi-day; single-day = the exam's own date).
        label: "Jour de l'examen",
        ok: this.canLaunchDay(),
        blocking: true,
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

  /**
   * The guided preparation path (#185, order = the teacher's mental model).
   * The BROUILLON→CONFIGURE transition is invisible: « Générer les lots »
   * performs it silently (LotsComponent), « Lancer l'examen » chains it
   * defensively (LancementComponent). No step ever mentions it.
   */
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
    const joursDistincts = new Set(this.lots().map((l) => l.jour).filter(Boolean)).size;
    const convocationsOk = this.convocationsFaites();
    const lanceOk = !this.isSetup();

    const stationsHint =
      n === 0
        ? 'Ajoutez au moins une station'
        : sansEval > 0
          ? `${sansEval} station(s) sans évaluateur`
          : sansGrille > 0
            ? `${sansGrille} station(s) sans grille`
            : `${n} station(s) prête(s)`;

    const lotsHint = !lotsOk
      ? rosterOk
        ? 'Répartissez les étudiants en vagues — un clic dans l’onglet Lots'
        : 'Inscrivez d’abord des étudiants, puis répartissez-les en vagues'
      : joursDistincts > 0
        ? `${lotsN} lot(s) répartis sur plusieurs jours`
        : `${lotsN} lot(s) répartis · étalables sur plusieurs jours (champ « Jour » de chaque lot)`;

    const steps: PrepStep[] = [
      {
        key: 'examen',
        label: "Créer l'examen",
        segment: 'vue-ensemble',
        optional: false,
        done: true,
        current: false,
        hint: 'Nom, date et paramètres du circuit',
      },
      {
        key: 'stations',
        label: 'Stations & grilles',
        segment: 'stations-grilles',
        optional: false,
        done: stationsOk,
        current: false,
        hint: stationsHint,
      },
      {
        key: 'etudiants',
        label: 'Étudiants',
        segment: 'etudiants',
        optional: false,
        done: rosterOk,
        current: false,
        hint: rosterOk ? `${roster} étudiant(s) inscrit(s)` : 'Importez la liste (CSV / Excel)',
      },
      {
        // Also home of the multi-day option (#147): each lot card carries a
        // « Jour » field once the lots exist — per-lot data lives with the lots.
        key: 'lots',
        label: 'Générer les lots',
        segment: 'lots',
        optional: false,
        done: lotsOk,
        current: false,
        hint: lotsHint,
      },
      {
        // #227 — printable slips; emailing stays data-blocked (no student
        // email). Optional but a REAL step: done = printed/exported, or
        // explicitly skipped from the stepper.
        key: 'convocations',
        label: 'Convocations',
        segment: 'convocations',
        optional: true,
        done: convocationsOk,
        current: false,
        hint: lotsOk
          ? 'Imprimez ou exportez les convocations — ou passez cette étape'
          : 'Disponibles une fois les lots répartis',
      },
      {
        key: 'lancement',
        label: "Lancer l'examen",
        segment: 'lancement',
        optional: false,
        done: lanceOk,
        current: false,
        hint: !lotsOk
          ? 'Dernière étape — une fois les lots répartis'
          : this.canLaunchDay()
            ? "Prêt — lancez l'examen"
            : 'Prêt — lancement le jour J',
      },
    ];

    const cur = steps.find((s) => !s.done);
    if (cur) cur.current = true;
    return steps;
  });

  /** The single suggested next action — the #185 "next step" affordance. */
  readonly nextStep = computed(() => this.prepSteps().find((s) => s.current) ?? null);

  /**
   * True while the suggested next step is the optional Convocations one — the
   * stepper then also offers « Passer au lancement » (skip), per Nada's spec:
   * optional, but genuinely the next step until decided.
   */
  readonly nextStepSkippable = computed(() => this.nextStep()?.key === 'convocations');
}
