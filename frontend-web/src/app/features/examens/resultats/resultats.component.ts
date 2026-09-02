import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { catchError, forkJoin, of } from 'rxjs';
import { AiApiService } from '../../../core/api/ai-api.service';
import { ExamApiService } from '../../../core/api/exam-api.service';
import { ScoringApiService } from '../../../core/api/scoring-api.service';
import {
  ExamenResult,
  GrilleDetail,
  GrilleItem,
  IndiceAi,
  IndiceCritereAi,
  IndicesExamen,
  NotationAdjustmentSummary,
  NotationItemSummary,
  ParticipationSummary,
  StationGrilleSnapshot,
  StationSummary,
} from '../../../core/api/models';
import { ExamenWorkspaceStore } from '../workspace/examen-workspace.store';
import { ReclamationsPanelComponent } from './reclamations-panel.component';

/** A station column header — name + ordre + the grille's noteMax denominator. */
interface StationCol {
  stationId: number;
  nom: string;
  ordre: number;
  noteMax: number;
}

/** The exam-level scoring-completeness summary that feeds the warning banner. */
interface CompletenessSummary {
  /** Present students with zero notations across all stations. */
  nonNotes: number;
  /** Present students scored at some but not all stations. */
  partiels: number;
  /** Station scores recorded but not yet locked (verrouillée=false). */
  nonVerrouillees: number;
  /** Present students expected to be fully scored (the denominator). */
  presentTotal: number;
  /** Stations in the exam (per-student expected notation count). */
  totalStations: number;
}

/** One resolved critère line inside a station deep-dive. */
interface CritereLine {
  itemId: number;
  libelle: string;
  binaire: boolean;
  /** ponderation (BINAIRE max) or valeurMax (NUMERIQUE max). */
  bareme: number | null;
  /** The student's recorded value, null when no per-critère detail exists. */
  valeur: number | null;
  commentaire: string | null;
  /** Answer key (#162): free-text expected answer from the grille, null when unset. */
  conditionsAttendues: string | null;
}

/** Lazy-loaded state of one station's per-critère deep-dive. */
type DeepDive =
  | { state: 'loading' }
  | { state: 'error' }
  | { state: 'ready'; lignes: CritereLine[]; hasDetail: boolean };

/** One student's row, enriched with the derived /20 average + rank + mention. */
interface ResultRow {
  rang: number;
  participationId: number;
  etudiantId: number | null;
  nom: string;
  numeroInscription: string | null;
  numEchantillon: string | null;
  /** stationId → score for O(1) cell lookup. */
  scoreByStation: Map<number, number | null>;
  /** stationId → verrouillée flag. */
  lockedByStation: Map<number, boolean>;
  /** stationId → notationId, for the per-critère deep-dive fetch. */
  notationIdByStation: Map<number, number>;
  total: number;
  totalMax: number;
  /** Normalised average on /20, null when no max is known. */
  moyenne20: number | null;
  mention: string;
  mentionClass: string;
  /** #363 — la SECONDE lecture (ADR-0030 D4) : /20 sous le barème de
   * délibération courant, null sans barème. Servie par scoring, jamais
   * recalculée ici. */
  moyenne20Delibere: number | null;
  totalDelibere: number | null;
  denominateurDelibere: number | null;
  baremeVersion: number | null;
}

/** One histogram bin of a station's locked-score distribution (#355). */
interface DeliberationBin {
  /** Bin range label, e.g. "8–12". */
  label: string;
  count: number;
  /** Share of the station's locked notations, 0–100 (bar width). */
  pct: number;
  /** True when the whole bin sits under the échec threshold (score < noteMax/2). */
  sousSeuil: boolean;
}

/** One failing student chip on a deliberation card — click opens their cell. */
interface DeliberationEchec {
  participationId: number;
  nom: string;
  score: number;
}

/**
 * The deliberation reading of ONE station (#355, ADR-0021 D4 items 1–2):
 * distribution + failure concentration, computed over LOCKED notations only —
 * the same "verdict = verrou" rule as the ranking (#297). Unlocked scores are
 * counted separately as `nEnCours`, never aggregated.
 */
interface StationDeliberation {
  stationId: number;
  nom: string;
  ordre: number;
  noteMax: number;
  /** Locked notations — the n every aggregate below is computed on. */
  nVerrouillees: number;
  /** Scores saisis mais non verrouillés — affichés, jamais agrégés. */
  nEnCours: number;
  moyenne: number | null;
  mediane: number | null;
  min: number | null;
  max: number | null;
  /** Locked scores strictly under noteMax/2. */
  nEchecs: number;
  /** nEchecs / nVerrouillees, 0–100; null when nothing is locked. */
  tauxEchec: number | null;
  bins: DeliberationBin[];
  /** The failing students, worst first — the réajustement entry points. */
  echecs: DeliberationEchec[];
}

const DEFAULT_NOTE_MAX = 20;
/** Histogram bin count for the per-station distributions (#355). */
const DELIBERATION_BINS = 5;

/**
 * Résultats — the post-exam scores board (TERMINE/ARCHIVE tab). Closes the
 * lifecycle: the exam ends in Suivi → "Terminer" → here.
 *
 * <p><b>Data:</b> the per-student aggregate comes from the scoring endpoint
 * GET /notations/examen/{id}/results (issue #90) — one row per student, already
 * sorted by total desc, carrying the per-station {@code score_final}s. Station
 * names + the grille {@code noteMax} (the {@code /max} denominator) live in
 * exam-service, so we join them client-side: {@code listStations} for the column
 * set, then {@code getStationGrille} per station for its noteMax (fetched once for
 * the column headers, NOT per student).
 *
 * <p><b>Ranking:</b> the backend sorts by raw total, but students could in
 * principle sit different station counts, so we rank on the normalised /20
 * average computed here — identical to the total order when every station shares
 * the same noteMax (the common case), correct otherwise.
 *
 * <p><b>No data yet:</b> until the mobile évaluateur app writes notations, a
 * TERMINE exam can have an empty result set — the screen says so explicitly
 * rather than rendering a blank table.
 */
@Component({
  selector: 'app-resultats',
  standalone: true,
  imports: [DecimalPipe, DatePipe, ReclamationsPanelComponent],
  templateUrl: './resultats.component.html',
})
export class ResultatsComponent {
  private readonly examApi = inject(ExamApiService);
  private readonly scoring = inject(ScoringApiService);
  private readonly ai = inject(AiApiService);
  private readonly store = inject(ExamenWorkspaceStore);

  /** Inherited from the parent examens/:id route via withComponentInputBinding(). */
  readonly id = input.required<string>();

  /** The route id as a number, for child inputs (réclamations register). */
  readonly examenIdNum = computed(() => Number(this.id()));

  readonly loading = signal(true);
  readonly error = signal(false);
  readonly rows = signal<ResultRow[]>([]);

  /** #363 — la version du barème de délibération appliquée (servie par scoring), null sans barème. */
  readonly baremeVersion = computed<number | null>(() => {
    for (const r of this.rows()) if (r.baremeVersion != null) return r.baremeVersion;
    return null;
  });
  readonly stationCols = signal<StationCol[]>([]);

  /** Exam-level scoring-completeness summary for the warning banner. */
  readonly completeness = signal<CompletenessSummary | null>(null);

  /**
   * #355 — scored stations whose barème came from the LIVE exam-service grille
   * because scoring had no snapshot for them (pre-V19 exam, or the snapshot
   * endpoint failed). Non-empty → a badge names the degradation; the screen
   * never falls back silently (leçon du 403 avalé).
   */
  readonly baremeLiveStations = signal<number[]>([]);

  // ---- indices psychométriques (#359, ai-service) ------------------------
  /**
   * Abonnement SÉPARÉ du forkJoin principal, à dessein (ADR-0021 D4 : l'écran
   * de délibération ne dépend JAMAIS du module IA). Une clé de plus dans le
   * forkJoin hériterait du handler `error:` partagé — un catchError oublié
   * dans un refactor futur éteindrait la délibération ; et forkJoin attend le
   * bras le plus lent, donc un timeout ai-service retarderait toute la table.
   * Modèle : loadAdjustments (l'historique auxiliaire, même posture).
   *
   * `absents` avale 403/409/501/503/réseau SANS distinction — le responsable
   * n'a pas à connaître le mode de panne d'un module dont l'écran ne dépend
   * pas ; la note grise le dit, le silence est interdit (leçon du 403 avalé).
   */
  readonly indices = signal<IndicesExamen | null>(null);
  readonly indicesEtat = signal<'chargement' | 'absents' | 'prets'>('chargement');

  /** station_id → concentration d'échec (cohorte), pour la carte de délibération. */
  readonly indiceConcentrationParStation = computed<Map<number, IndiceAi>>(() => {
    const m = new Map<number, IndiceAi>();
    for (const s of this.indices()?.par_station ?? []) m.set(s.station_id, s.concentration_echec);
    return m;
  });

  /** station_id → α de Cronbach de SA grille, pour la carte de délibération. */
  readonly indiceAlphaParStation = computed<Map<number, IndiceAi>>(() => {
    const m = new Map<number, IndiceAi>();
    for (const g of this.indices()?.par_grille ?? []) m.set(g.station_id, g.alpha_cronbach);
    return m;
  });

  /** item_id → indices cohorte du critère, pour les colonnes du deep-dive. */
  readonly indicesParItem = computed<Map<number, IndiceCritereAi>>(() => {
    const m = new Map<number, IndiceCritereAi>();
    for (const c of this.indices()?.par_critere ?? []) m.set(c.item_id, c);
    return m;
  });

  alphaDe(stationId: number): IndiceAi | null {
    return this.indiceAlphaParStation().get(stationId) ?? null;
  }

  concentrationDe(stationId: number): IndiceAi | null {
    return this.indiceConcentrationParStation().get(stationId) ?? null;
  }

  indiceCritereDe(itemId: number): IndiceCritereAi | null {
    return this.indicesParItem().get(itemId) ?? null;
  }

  /** ", p=0,032" quand le test en porte une — construit ici pour éviter un @if
   *  imbriqué dans une interpolation (famille NG5002). Virgule décimale (fr). */
  pLabel(indice: IndiceAi): string {
    const p = indice.details?.['p_value'];
    if (typeof p !== 'number') return '';
    return `, p=${p.toFixed(3).replace('.', ',')}`;
  }

  // ---- per-critère deep-dive (lazy, per student×station) -----------------
  /** Currently expanded cell, keyed `${participationId}:${stationId}`; null = none. */
  readonly expandedKey = signal<string | null>(null);
  /** notationId → loaded/loading deep-dive state (cached across expand toggles). */
  readonly deepDives = signal<Map<number, DeepDive>>(new Map());
  /** stationId → full grille (items resolved once during load) for critère labels. */
  private readonly grilleByStation = new Map<number, GrilleDetail>();

  // ---- audited réajustement (ADR-0013 Part 2) ----------------------------
  /** notationId → its adjustment history (loaded when a locked cell expands). */
  readonly adjustmentsByNotation = signal<Map<number, NotationAdjustmentSummary[]>>(new Map());
  /** notationId whose réajustement form is open, or null. */
  readonly reajustOpen = signal<number | null>(null);
  readonly reajustValeur = signal<number | null>(null);
  readonly reajustMotif = signal('');
  readonly reajustBusy = signal(false);
  readonly reajustError = signal<string | null>(null);

  // Shared with the workspace shell — archiving updates the header chip + lifecycle
  // bar reactively (the store is route-scoped, one instance per open workspace).
  readonly exam = this.store.exam;
  readonly archiving = signal(false);
  readonly confirmingArchive = signal(false);
  readonly archiveError = signal<string | null>(null);
  /** Only a TERMINE exam can be archived (the next legal lifecycle edge). */
  readonly canArchive = computed(() => this.exam()?.statut === 'TERMINE');

  readonly moyennePromo = computed(() => {
    const r = this.rows().filter((x) => x.moyenne20 != null);
    if (r.length === 0) return null;
    return r.reduce((s, x) => s + (x.moyenne20 as number), 0) / r.length;
  });

  readonly tauxReussite = computed(() => {
    const r = this.rows().filter((x) => x.moyenne20 != null);
    if (r.length === 0) return null;
    const ok = r.filter((x) => (x.moyenne20 as number) >= 10).length;
    return (ok / r.length) * 100;
  });

  /** Any non-noté / partiel / unlocked score → the banner warns rather than confirms. */
  readonly hasScoringGaps = computed(() => {
    const c = this.completeness();
    return !!c && (c.nonNotes > 0 || c.partiels > 0 || c.nonVerrouillees > 0);
  });

  /**
   * #355 — the deliberation reading, ADR-0021 D4 items 1–2: per-station score
   * distributions + failure concentration. Pure derivation over the rows the
   * table already built (no extra fetch, no new failure mode). Aggregated over
   * LOCKED notations only — same verdict rule as the ranking (#297); unlocked
   * scores are shown as "en cours", never averaged. Cards are sorted by failure
   * rate desc (the concentration reading), stations without any locked notation
   * last, ties by station order.
   */
  readonly deliberation = computed<StationDeliberation[]>(() => {
    const rows = this.rows();
    return this.stationCols()
      .map((col) => this.deliberationForStation(col, rows))
      .sort((a, b) => {
        if (a.tauxEchec == null && b.tauxEchec == null) return a.ordre - b.ordre;
        if (a.tauxEchec == null) return 1;
        if (b.tauxEchec == null) return -1;
        return b.tauxEchec - a.tauxEchec || a.ordre - b.ordre;
      });
  });

  private deliberationForStation(col: StationCol, rows: ResultRow[]): StationDeliberation {
    const seuil = col.noteMax / 2;
    const verrouillees: DeliberationEchec[] = [];
    let nEnCours = 0;
    for (const row of rows) {
      const score = row.scoreByStation.get(col.stationId);
      if (score == null) continue;
      if (row.lockedByStation.get(col.stationId) === true) {
        verrouillees.push({ participationId: row.participationId, nom: row.nom, score });
      } else {
        nEnCours++;
      }
    }

    const scores = verrouillees.map((v) => v.score).sort((a, b) => a - b);
    const n = scores.length;
    const echecs = verrouillees.filter((v) => v.score < seuil).sort((a, b) => a.score - b.score);

    return {
      stationId: col.stationId,
      nom: col.nom,
      ordre: col.ordre,
      noteMax: col.noteMax,
      nVerrouillees: n,
      nEnCours,
      moyenne: n > 0 ? scores.reduce((s, v) => s + v, 0) / n : null,
      mediane: n > 0 ? this.medianOf(scores) : null,
      min: n > 0 ? scores[0] : null,
      max: n > 0 ? scores[n - 1] : null,
      nEchecs: echecs.length,
      tauxEchec: n > 0 ? (echecs.length / n) * 100 : null,
      bins: this.binsFor(scores, col.noteMax, seuil),
      echecs,
    };
  }

  /** Median of an ASCENDING-sorted non-empty score list. */
  private medianOf(sorted: number[]): number {
    const mid = Math.floor(sorted.length / 2);
    return sorted.length % 2 === 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
  }

  /**
   * Equal-width histogram over [0, noteMax]. A score equal to noteMax lands in
   * the LAST bin (the classic right-edge case); `sousSeuil` marks bins lying
   * entirely under the échec threshold so the template can color them.
   */
  private binsFor(scores: number[], noteMax: number, seuil: number): DeliberationBin[] {
    const max = noteMax > 0 ? noteMax : DEFAULT_NOTE_MAX;
    const width = max / DELIBERATION_BINS;
    const counts = new Array<number>(DELIBERATION_BINS).fill(0);
    for (const s of scores) {
      const idx = Math.min(DELIBERATION_BINS - 1, Math.max(0, Math.floor(s / width)));
      counts[idx]++;
    }
    const fmt = (v: number) => (Number.isInteger(v) ? String(v) : v.toFixed(1));
    return counts.map((count, i) => ({
      label: `${fmt(i * width)}–${fmt((i + 1) * width)}`,
      count,
      pct: scores.length > 0 ? (count / scores.length) * 100 : 0,
      sousSeuil: (i + 1) * width <= seuil,
    }));
  }

  /**
   * #355 — "réajustement à portée de clic" (ADR-0021 D4 item 4): a chip on a
   * deliberation card opens that student×station cell — the per-critère
   * breakdown with the réajustement panel already inside — and scrolls to it.
   * One click from the failure reading to the sanctioned act; no new write UI.
   */
  ouvrirCelluleDepuisDeliberation(participationId: number, stationId: number): void {
    const row = this.rows().find((r) => r.participationId === participationId);
    const col = this.stationCols().find((c) => c.stationId === stationId);
    if (!row || !col) return;
    if (!this.isExpanded(row, col)) this.toggleCell(row, col);
    // Après le rendu de la ligne dépliée — sinon la cible n'existe pas encore.
    setTimeout(() => {
      document
        .getElementById(`resultat-row-${participationId}`)
        ?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
  }

  readonly minMaxLabel = computed(() => {
    const vals = this.rows()
      .map((x) => x.moyenne20)
      .filter((v): v is number => v != null);
    if (vals.length === 0) return '—';
    const min = Math.min(...vals);
    const max = Math.max(...vals);
    return `${min.toFixed(1)} · ${max.toFixed(1)}`;
  });

  constructor() {
    effect(
      () => {
        const examId = Number(this.id());
        if (!Number.isFinite(examId)) {
          this.error.set(true);
          this.loading.set(false);
          return;
        }
        this.load(examId);
      },
      { allowSignalWrites: true },
    );
  }

  reload(): void {
    this.load(Number(this.id()));
  }

  // ---- archive (TERMINE → ARCHIVE) ---------------------------------------

  askArchive(): void {
    this.archiveError.set(null);
    this.confirmingArchive.set(true);
  }

  cancelArchive(): void {
    this.confirmingArchive.set(false);
  }

  /**
   * Archive the finished exam (TERMINE → ARCHIVE) — the last legal lifecycle
   * edge. The backend validates the state-machine transition; on success the
   * store's exam is updated so the header chip flips to "Archivé" and the archive
   * bar disappears (canArchive → false). Résultats stays viewable (TABS_DONE
   * covers TERMINE and ARCHIVE alike).
   */
  archive(): void {
    if (this.archiving()) return;
    this.archiving.set(true);
    this.archiveError.set(null);
    this.examApi.changerStatut(Number(this.id()), 'ARCHIVE').subscribe({
      next: (e) => {
        this.store.exam.set(e);
        this.archiving.set(false);
        this.confirmingArchive.set(false);
      },
      error: (err: { status?: number; error?: { message?: string } }) => {
        this.archiving.set(false);
        const msg =
          err?.status === 403
            ? "Vous n'avez pas les droits sur cet examen."
            : (err?.error?.message ?? "Impossible d'archiver l'examen.");
        this.archiveError.set(msg);
      },
    });
  }

  private load(examId: number): void {
    this.loading.set(true);
    this.error.set(false);
    this.expandedKey.set(null);
    this.deepDives.set(new Map());
    this.grilleByStation.clear();
    this.baremeLiveStations.set([]);
    this.loadIndices(examId);
    // The roster (participations) gives the present-student denominator the
    // results endpoint can't — it only returns students who have ≥1 notation.
    // #355 — the barèmes come FIRST from scoring's snapshots (what actually
    // graded, ADR-0015); the call is fail-soft (empty on error) so a snapshot
    // outage degrades to the live-grille path instead of blanking the screen.
    forkJoin({
      results: this.scoring.getExamenResults(examId),
      // Fail-soft (#355) : exam-service ne sert ici QUE des métadonnées
      // d'affichage (noms de stations, ordre). Sa panne dégrade les en-têtes en
      // « Station {id} » — elle n'a plus le droit d'éteindre la délibération.
      stations: this.examApi
        .listStations(examId)
        .pipe(catchError(() => of([] as StationSummary[]))),
      participations: this.scoring.listParticipations(examId),
      snapshots: this.scoring
        .getExamenGrillesSnapshot(examId)
        .pipe(catchError(() => of([] as StationGrilleSnapshot[]))),
    }).subscribe({
      next: ({ results, stations, participations, snapshots }) => {
        if (results.length === 0) {
          this.rows.set([]);
          this.stationCols.set([]);
          this.completeness.set(this.computeCompleteness([], stations, participations));
          this.loading.set(false);
          return;
        }
        // Resolve each scored station's grille (once) — noteMax for the column
        // denominator AND the full item list for the per-critère deep-dive.
        const scoredStationIds = new Set<number>();
        for (const r of results) {
          for (const s of r.stations) if (s.stationId != null) scoredStationIds.add(s.stationId);
        }

        // #355 — snapshot first: one batched payload, keyed by stationId. The
        // snapshot's items[] is the same GrilleItem tree the live endpoint
        // returns (captured verbatim at launch), so the deep-dive join is
        // source-agnostic. Keyed on the SCORED ids (from scoring), not on the
        // exam-service station list — the barème path must not depend on it.
        const snapByStation = new Map(snapshots.map((s) => [s.stationId, s]));
        const noteMaxByStation = new Map<number, number>();
        const manquantes: number[] = [];
        for (const sid of scoredStationIds) {
          const snap = snapByStation.get(sid);
          if (snap) {
            noteMaxByStation.set(sid, snap.noteMax ?? DEFAULT_NOTE_MAX);
            this.grilleByStation.set(sid, {
              id: snap.grilleId ?? 0,
              nom: snap.nom,
              noteMax: snap.noteMax,
              items: snap.items ?? [],
            });
          } else {
            manquantes.push(sid);
          }
        }

        const terminer = () => {
          this.buildTable(results, stations, noteMaxByStation);
          this.completeness.set(this.computeCompleteness(results, stations, participations));
          this.loading.set(false);
        };

        if (manquantes.length === 0) {
          terminer();
          return;
        }
        // Fallback (pre-V19 exam or snapshot row missing): the LIVE exam-service
        // grille, and the badge SAYS so — never a silent substitution. Each call
        // is fail-soft on its own: one station's failure (or exam-service down)
        // degrades THAT station to the /20 default, not the whole screen.
        this.baremeLiveStations.set(manquantes);
        const metaById = new Map(stations.map((s) => [s.id, s]));
        const grilleCalls = manquantes.map((sid) => {
          const meta = metaById.get(sid);
          return meta && meta.hasGrille === false
            ? of(null)
            : this.examApi.getStationGrille(sid).pipe(catchError(() => of(null)));
        });
        forkJoin(grilleCalls).subscribe({
          next: (grilles) => {
            manquantes.forEach((sid, i) => {
              const g = grilles[i];
              noteMaxByStation.set(sid, g?.noteMax ?? DEFAULT_NOTE_MAX);
              if (g) this.grilleByStation.set(sid, g);
            });
            terminer();
          },
        });
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  /** #359 — voir le commentaire des signaux `indices`/`indicesEtat`. */
  private loadIndices(examId: number): void {
    this.indices.set(null);
    this.indicesEtat.set('chargement');
    this.ai.getIndices(examId).subscribe({
      next: (data) => {
        this.indices.set(data);
        this.indicesEtat.set('prets');
      },
      error: () => this.indicesEtat.set('absents'),
    });
  }

  /**
   * Exam-level scoring completeness, computed FE-side (no backend summary exists).
   * Denominator is the PRESENT roster — absent students legitimately have no notes
   * and must not read as "missing". A student is "non noté" when no notation row
   * came back for their participation, "partiel" when scored at some-but-not-all
   * stations, and a station score is "non verrouillée" until an évaluateur locks it.
   */
  private computeCompleteness(
    results: ExamenResult[],
    stations: StationSummary[],
    participations: ParticipationSummary[],
  ): CompletenessSummary {
    const totalStations = stations.length;
    const present = participations.filter((p) => p.est_present === true);
    const byParticipation = new Map(results.map((r) => [r.participationId, r]));

    let nonNotes = 0;
    let partiels = 0;
    let nonVerrouillees = 0;

    for (const p of present) {
      const r = byParticipation.get(p.id);
      // #297 — MÊME critère que buildTable() : le VERROU, pas la simple
      // présence d'une ligne de notation. Avant, la bannière comptait
      // "stationsNotees" (notations existantes, verrouillées ou non) alors que
      // le tableau exige lockedCount === stations.length pour attribuer un
      // rang — un étudiant pouvait lire "0 partiel" en haut et "aucun rang"
      // en bas pour LUI-MÊME. Les deux widgets doivent parler de la même
      // notion de "verdict" : verrouillé, pas seulement saisi.
      const lockedCount = r ? r.stations.filter((s) => s.verrouillee === true).length : 0;

      if (lockedCount === 0) {
        nonNotes++;
      } else if (totalStations > 0 && lockedCount < totalStations) {
        partiels++;
      }
    }

    // Compteur auxiliaire affiché séparément dans la bannière : notes SAISIES
    // mais pas encore verrouillées. Recoupe volontairement "partiels"
    // ci-dessus (un étudiant partiel a le plus souvent une note en attente de
    // verrou) — c'est un signal indicatif, pas une troisième catégorie disjointe.
    for (const r of results) {
      for (const s of r.stations) {
        if (s.score != null && s.verrouillee !== true) nonVerrouillees++;
      }
    }

    return { nonNotes, partiels, nonVerrouillees, presentTotal: present.length, totalStations };
  }

  private buildTable(
    results: ExamenResult[],
    stations: StationSummary[],
    noteMaxByStation: Map<number, number>,
  ): void {
    const stationMeta = new Map(stations.map((s) => [s.id, s]));
    const cols: StationCol[] = [...noteMaxByStation.keys()]
      .map((sid) => {
        const meta = stationMeta.get(sid);
        return {
          stationId: sid,
          nom: meta?.nom ?? `Station ${sid}`,
          ordre: meta?.ordre ?? 0,
          noteMax: noteMaxByStation.get(sid) ?? DEFAULT_NOTE_MAX,
        };
      })
      .sort((a, b) => a.ordre - b.ordre);
    this.stationCols.set(cols);

    const rows: ResultRow[] = results.map((r) => {
      const scoreByStation = new Map<number, number | null>();
      const lockedByStation = new Map<number, boolean>();
      const notationIdByStation = new Map<number, number>();
      let totalMax = 0;
      let lockedCount = 0;
      for (const s of r.stations) {
        if (s.stationId == null) continue;
        scoreByStation.set(s.stationId, s.score);
        const locked = s.verrouillee === true;
        lockedByStation.set(s.stationId, locked);
        if (s.notationId != null) notationIdByStation.set(s.stationId, s.notationId);
        if (s.score != null) totalMax += noteMaxByStation.get(s.stationId) ?? DEFAULT_NOTE_MAX;
        if (locked) lockedCount++;
      }
      // #297 — un verdict d'examen (moyenne, mention, rang) exige un verrou
      // sur TOUTES les stations de l'examen, pas seulement celles déjà
      // saisies. Avant : totalMax ne comptait que les stations SAISIES, donc
      // une station jamais touchée disparaissait silencieusement du barème
      // (45/60 au lieu de 45/80) — un re-barème que personne n'avait décidé,
      // exactement le défaut nommé par le ticket.
      const complete = stations.length > 0 && lockedCount === stations.length;
      const moyenne20 = complete && totalMax > 0 ? (r.totalScore / totalMax) * 20 : null;
      const { mention, mentionClass } = this.mentionFor(moyenne20, lockedCount, stations.length);
      // #363 — la lecture délibérée vient TELLE QUELLE de scoring (deux
      // dénominateurs, ADR-0030 D4) ; seule la reconversion /20 est un choix d'écran.
      const totalDelibere = r.totalDelibere ?? null;
      const denominateurDelibere = r.denominateurDelibere ?? null;
      const moyenne20Delibere =
        totalDelibere != null && denominateurDelibere != null && denominateurDelibere > 0
          ? (totalDelibere / denominateurDelibere) * 20
          : null;
      return {
        rang: 0, // assigné après tri, 0 = "pas de rang" pour un résultat incomplet
        participationId: r.participationId,
        etudiantId: r.etudiantId,
        nom: `${r.prenom ?? ''} ${r.nom ?? ''}`.trim() || 'Étudiant inconnu',
        numeroInscription: r.numeroInscription,
        numEchantillon: r.numEchantillon,
        scoreByStation,
        lockedByStation,
        notationIdByStation,
        total: r.totalScore,
        totalMax,
        moyenne20,
        mention,
        mentionClass,
        moyenne20Delibere,
        totalDelibere,
        denominateurDelibere,
        baremeVersion: r.baremeVersion ?? null,
      };
    });

    // #297 — un résultat sans verdict complet est affiché (le responsable
    // doit pouvoir agir dessus) mais ne consomme JAMAIS un numéro de rang :
    // il ne doit pas être classé contre des dossiers clos.
    rows.sort((a, b) => (b.moyenne20 ?? -1) - (a.moyenne20 ?? -1));
    let rang = 0;
    for (const row of rows) {
      row.rang = row.moyenne20 != null ? ++rang : 0;
    }
    this.rows.set(rows);
  }

  private mentionFor(
    moyenne20: number | null,
    lockedCount: number,
    totalStations: number,
  ): { mention: string; mentionClass: string } {
    if (moyenne20 == null) {
      const mention = lockedCount === 0 ? 'Non noté' : `Incomplet (${lockedCount}/${totalStations})`;
      return { mention, mentionClass: 'bg-gray-100 text-gray-500' };
    }
    if (moyenne20 >= 16) return { mention: 'Très bien', mentionClass: 'bg-green-100 text-green-700' };
    if (moyenne20 >= 14) return { mention: 'Bien', mentionClass: 'bg-emerald-50 text-emerald-700' };
    if (moyenne20 >= 12) return { mention: 'Assez bien', mentionClass: 'bg-brand-50 text-brand-dark' };
    if (moyenne20 >= 10) return { mention: 'Passable', mentionClass: 'bg-amber-50 text-amber-700' };
    return { mention: 'Insuffisant', mentionClass: 'bg-red-100 text-red-700' };
  }

  isStationFail(row: ResultRow, col: StationCol): boolean {
    const score = row.scoreByStation.get(col.stationId);
    return score != null && score < col.noteMax / 2;
  }

  // ---- per-critère deep-dive --------------------------------------------

  cellKey(row: ResultRow, col: StationCol): string {
    return `${row.participationId}:${col.stationId}`;
  }

  isExpanded(row: ResultRow, col: StationCol): boolean {
    return this.expandedKey() === this.cellKey(row, col);
  }

  /** Whether a cell can be drilled into — only scored cells have a notation. */
  hasNotation(row: ResultRow, col: StationCol): boolean {
    return row.notationIdByStation.has(col.stationId);
  }

  /** The notationId behind a student×station cell, or null when not scored. */
  notationIdOf(row: ResultRow, col: StationCol): number | null {
    return row.notationIdByStation.get(col.stationId) ?? null;
  }

  /**
   * Toggle the per-critère drill-down for one student×station cell. Collapses if
   * already open; otherwise opens it and lazily fetches the notation's critère
   * scores (cached by notationId so re-expanding is instant). Only one cell is
   * open at a time to keep the table readable.
   */
  toggleCell(row: ResultRow, col: StationCol): void {
    const notationId = row.notationIdByStation.get(col.stationId);
    if (notationId == null) return;
    const key = this.cellKey(row, col);
    if (this.expandedKey() === key) {
      this.expandedKey.set(null);
      this.reajustOpen.set(null);
      return;
    }
    this.expandedKey.set(key);
    this.reajustOpen.set(null);
    if (!this.deepDives().has(notationId)) {
      this.fetchDeepDive(notationId, col.stationId);
    }
    // Locked score → surface its réclamation trail alongside the critères.
    if (row.lockedByStation.get(col.stationId) === true && !this.adjustmentsByNotation().has(notationId)) {
      this.loadAdjustments(notationId);
    }
  }

  /** The station column currently expanded within this row, or null. */
  expandedColFor(row: ResultRow): StationCol | null {
    const key = this.expandedKey();
    if (!key || !key.startsWith(`${row.participationId}:`)) return null;
    const stationId = Number(key.slice(key.indexOf(':') + 1));
    return this.stationCols().find((c) => c.stationId === stationId) ?? null;
  }

  /** The deep-dive state for the currently expanded cell of this row, or null. */
  deepDiveFor(row: ResultRow, col: StationCol): DeepDive | null {
    const notationId = row.notationIdByStation.get(col.stationId);
    if (notationId == null) return null;
    return this.deepDives().get(notationId) ?? null;
  }

  private fetchDeepDive(notationId: number, stationId: number): void {
    this.patchDeepDive(notationId, { state: 'loading' });
    this.scoring.getNotationItems(notationId).subscribe({
      next: (items) => {
        const lignes = this.resolveCriteres(stationId, items);
        this.patchDeepDive(notationId, {
          state: 'ready',
          lignes,
          hasDetail: items.length > 0,
        });
      },
      error: () => this.patchDeepDive(notationId, { state: 'error' }),
    });
  }

  /**
   * Join the notation's recorded values onto the station grille's critère
   * definitions (libellé + barème). Driven by the grille so the full critère list
   * shows even when no per-critère detail was captured (empty values then).
   */
  private resolveCriteres(stationId: number, items: NotationItemSummary[]): CritereLine[] {
    const grille = this.grilleByStation.get(stationId);
    const valueByItem = new Map<number, NotationItemSummary>();
    for (const it of items) if (it.item_id != null) valueByItem.set(it.item_id, it);
    // #160 — grille.items are TOP-LEVEL only, with sub-criteria nested. Only
    // LEAVES are ever notated (scoring rejects grading a parent), so flatten the
    // hierarchy to leaves before joining the recorded per-critère values — else a
    // decomposed critère would show an empty parent row and hide its scored leaves.
    const grilleItems: GrilleItem[] = this.flattenLeaves(grille?.items ?? []);
    if (grilleItems.length === 0) {
      // Grille unavailable — surface the raw recorded values rather than nothing.
      return items.map((it) => ({
        itemId: it.item_id ?? 0,
        libelle: it.item_id != null ? `Critère #${it.item_id}` : 'Critère',
        binaire: false,
        bareme: null,
        valeur: it.valeur,
        commentaire: it.commentaire,
        conditionsAttendues: null,
      }));
    }
    return grilleItems.map((gi) => {
      const binaire = gi.type === 'BINAIRE';
      const rec = valueByItem.get(gi.id);
      return {
        itemId: gi.id,
        libelle: gi.libelle,
        binaire,
        bareme: binaire ? (gi.ponderation ?? null) : (gi.valeurMax ?? null),
        valeur: rec?.valeur ?? null,
        commentaire: rec?.commentaire ?? null,
        conditionsAttendues: gi.conditionsAttendues ?? null,
      };
    });
  }

  /** Flatten a grille's item tree to its notable LEAVES (#160), sorted by ordre at
   *  each level — mirrors the backend GrilleServiceImpl.aplatirFeuilles. A critère
   *  with sub-criteria contributes its children; a plain critère contributes itself. */
  private flattenLeaves(items: GrilleItem[]): GrilleItem[] {
    const sorted = items.slice().sort((a, b) => (a.ordre ?? 0) - (b.ordre ?? 0));
    const leaves: GrilleItem[] = [];
    for (const it of sorted) {
      if (it.sousCriteres?.length) {
        leaves.push(...this.flattenLeaves(it.sousCriteres));
      } else {
        leaves.push(it);
      }
    }
    return leaves;
  }

  private patchDeepDive(notationId: number, state: DeepDive): void {
    const next = new Map(this.deepDives());
    next.set(notationId, state);
    this.deepDives.set(next);
  }

  // ---- audited réajustement (ADR-0013 Part 2) ----------------------------

  /** True when this student×station score is locked (verrouillée). */
  isLocked(row: ResultRow, col: StationCol): boolean {
    return row.lockedByStation.get(col.stationId) === true;
  }

  /** The adjustment history loaded for a notation (most-recent first). */
  adjustmentsFor(notationId: number): NotationAdjustmentSummary[] {
    return this.adjustmentsByNotation().get(notationId) ?? [];
  }

  private loadAdjustments(notationId: number): void {
    this.scoring.listReajustements(notationId).subscribe({
      next: (list) => {
        const next = new Map(this.adjustmentsByNotation());
        next.set(notationId, list);
        this.adjustmentsByNotation.set(next);
      },
      // History is auxiliary — a load failure just leaves it empty, no error UI.
      error: () => void 0,
    });
  }

  /** Open the réajustement form for a locked station, prefilled with its total. */
  openReajust(notationId: number, currentScore: number | null): void {
    this.reajustOpen.set(notationId);
    this.reajustValeur.set(currentScore);
    this.reajustMotif.set('');
    this.reajustError.set(null);
  }

  cancelReajust(): void {
    this.reajustOpen.set(null);
    this.reajustBusy.set(false);
    this.reajustError.set(null);
  }

  /**
   * Submit an audited réajustement of a locked total (ADR-0013 Part 2). motif is
   * required; the notation stays locked. On success we reload the whole table (the
   * total moved) and refresh this notation's history.
   */
  submitReajust(notationId: number): void {
    const valeur = this.reajustValeur();
    const motif = this.reajustMotif().trim();
    if (valeur == null || Number.isNaN(valeur)) {
      this.reajustError.set('Renseignez la nouvelle note.');
      return;
    }
    if (!motif) {
      this.reajustError.set('Le motif de la réclamation est obligatoire.');
      return;
    }
    this.reajustBusy.set(true);
    this.reajustError.set(null);
    this.scoring.reajusterNotation(notationId, { itemId: null, nouvelleValeur: valeur, motif }).subscribe({
      next: () => {
        this.reajustBusy.set(false);
        this.reajustOpen.set(null);
        this.loadAdjustments(notationId);
        this.load(Number(this.id()));
      },
      error: (err: { status?: number; error?: { message?: string } }) => {
        this.reajustBusy.set(false);
        this.reajustError.set(
          err?.status === 403
            ? "Vous n'avez pas les droits pour réajuster cette note."
            : (err?.error?.message ?? 'Le réajustement a échoué.'),
        );
      },
    });
  }


  /** Client-side CSV export of the current classement — no backend round-trip. */
  exportCsv(): void {
    const cols = this.stationCols();
    const header = [
      'Rang',
      'Nom',
      'Numero',
      'Echantillon',
      ...cols.map((c) => `${c.nom} (/${c.noteMax})`),
      'Total',
      'Moyenne/20',
      'Mention',
      'Etat', // #297 — l'export ne doit jamais laisser un chiffre parler seul
    ];
    const lines = this.rows().map((row) => {
      const cells = [
        row.rang > 0 ? row.rang : '',
        row.nom,
        row.numeroInscription ?? '',
        row.numEchantillon ?? '',
        ...cols.map((c) => {
          const v = row.scoreByStation.get(c.stationId);
          return v != null ? v : '';
        }),
        row.total,
        row.moyenne20 != null ? row.moyenne20.toFixed(2) : '',
        row.mention,
        row.moyenne20 != null ? 'Complet' : 'Incomplet',
      ];
      return cells.map((c) => this.csvCell(c)).join(',');
    });
    const csv = [header.map((h) => this.csvCell(h)).join(','), ...lines].join('\r\n');
    const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `resultats-examen-${this.id()}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  private csvCell(value: unknown): string {
    const s = String(value ?? '');
    return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  }
}
