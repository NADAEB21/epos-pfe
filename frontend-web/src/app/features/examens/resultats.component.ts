import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { forkJoin, of } from 'rxjs';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ScoringApiService } from '../../core/api/scoring-api.service';
import {
  ExamenResult,
  GrilleDetail,
  GrilleItem,
  NotationAdjustmentSummary,
  NotationItemSummary,
  ParticipationSummary,
  StationSummary,
} from '../../core/api/models';
import { ExamenWorkspaceStore } from './examen-workspace.store';

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
}

const DEFAULT_NOTE_MAX = 20;

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
  imports: [DecimalPipe, DatePipe],
  template: `
    @if (loading()) {
      <div class="space-y-6 animate-pulse">
        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          <div class="h-20 rounded-xl bg-gray-200"></div>
          <div class="h-20 rounded-xl bg-gray-200"></div>
          <div class="h-20 rounded-xl bg-gray-200"></div>
          <div class="h-20 rounded-xl bg-gray-200"></div>
        </div>
        <div class="h-64 rounded-xl bg-gray-200"></div>
      </div>
    } @else if (error()) {
      <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card">
        <p class="text-gray-700 mb-1">Impossible de charger les résultats.</p>
        <p class="text-sm text-gray-500 mb-4">Vérifiez votre connexion puis réessayez.</p>
        <button
          type="button"
          (click)="reload()"
          class="inline-flex items-center px-4 py-2 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark transition-colors"
        >
          Réessayer
        </button>
      </div>
    } @else {
      <!-- Lifecycle closer: archive a finished exam (TERMINE → ARCHIVE). -->
      @if (canArchive()) {
        <div class="flex flex-wrap items-center justify-between gap-3 rounded-xl bg-gray-50 border border-gray-200 px-4 py-3 mb-6">
          <p class="text-sm text-gray-600">
            Examen terminé. Une fois les résultats validés, vous pouvez l'archiver
            (lecture seule, retiré des examens actifs).
          </p>
          <div class="flex items-center gap-2">
            @if (archiveError()) {
              <span role="alert" class="text-sm text-status-danger">{{ archiveError() }}</span>
            }
            @if (confirmingArchive()) {
              <span class="text-sm font-medium text-gray-800">Archiver&nbsp;?</span>
              <button
                type="button"
                [disabled]="archiving()"
                (click)="archive()"
                class="inline-flex items-center px-4 py-2 rounded-lg bg-gray-800 text-white text-sm font-medium hover:bg-gray-900 transition-colors disabled:opacity-50"
              >
                {{ archiving() ? '…' : 'Oui, archiver' }}
              </button>
              <button
                type="button"
                [disabled]="archiving()"
                (click)="cancelArchive()"
                class="inline-flex items-center px-3 py-2 rounded-lg border border-gray-300 text-gray-600 text-sm font-medium hover:bg-gray-50 transition-colors disabled:opacity-50"
              >
                Annuler
              </button>
            } @else {
              <button
                type="button"
                (click)="askArchive()"
                class="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg border border-gray-400 text-gray-700 text-sm font-medium hover:bg-gray-100 transition-colors"
              >
                Archiver l'examen
              </button>
            }
          </div>
        </div>
      }

      @if (rows().length === 0) {
      <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card">
        <p class="text-gray-700 mb-1">Aucune note saisie pour cet examen.</p>
        <p class="text-sm text-gray-500 max-w-md mx-auto">
          Les notes sont remontées par les évaluateurs depuis l'application mobile,
          station par station. Dès qu'une notation est enregistrée, le classement
          apparaît ici.
        </p>
      </div>
      } @else {
      <!-- Scoring-completeness banner (gap #3): aggregate status across the present
           roster, computed FE-side — warns while grading is incomplete or unlocked. -->
      @if (completeness(); as c) {
        @if (hasScoringGaps()) {
          <div role="status" class="flex items-start gap-3 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 mb-6">
            <svg class="w-5 h-5 text-amber-500 shrink-0 mt-0.5" fill="none" viewBox="0 0 24 24" stroke-width="1.8" stroke="currentColor" aria-hidden="true">
              <path stroke-linecap="round" stroke-linejoin="round" d="M12 9v3.75m9-.75a9 9 0 11-18 0 9 9 0 0118 0zm-9 3.75h.008v.008H12v-.008z" />
            </svg>
            <div class="text-sm text-amber-800">
              <p class="font-medium">Notation incomplète</p>
              <p class="text-amber-700">
                @if (c.nonNotes > 0) {
                  <span>{{ c.nonNotes }} étudiant{{ c.nonNotes > 1 ? 's' : '' }} non noté{{ c.nonNotes > 1 ? 's' : '' }}</span>
                }
                @if (c.partiels > 0) {
                  <span>{{ c.nonNotes > 0 ? ' · ' : '' }}{{ c.partiels }} partiellement noté{{ c.partiels > 1 ? 's' : '' }}</span>
                }
                @if (c.nonVerrouillees > 0) {
                  <span>{{ (c.nonNotes > 0 || c.partiels > 0) ? ' · ' : '' }}{{ c.nonVerrouillees }} note{{ c.nonVerrouillees > 1 ? 's' : '' }} non verrouillée{{ c.nonVerrouillees > 1 ? 's' : '' }}</span>
                }
                <span class="text-amber-600"> (sur {{ c.presentTotal }} présent{{ c.presentTotal > 1 ? 's' : '' }} · {{ c.totalStations }} stations)</span>
              </p>
            </div>
          </div>
        } @else {
          <div role="status" class="flex items-center gap-2.5 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 mb-6">
            <svg class="w-5 h-5 text-emerald-500 shrink-0" fill="none" viewBox="0 0 24 24" stroke-width="1.8" stroke="currentColor" aria-hidden="true">
              <path stroke-linecap="round" stroke-linejoin="round" d="M9 12.75 11.25 15 15 9.75M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
            <p class="text-sm text-emerald-800">
              Notation complète — {{ c.presentTotal }} étudiant{{ c.presentTotal > 1 ? 's' : '' }} présent{{ c.presentTotal > 1 ? 's' : '' }} noté{{ c.presentTotal > 1 ? 's' : '' }} et verrouillé{{ c.presentTotal > 1 ? 's' : '' }} sur {{ c.totalStations }} stations.
            </p>
          </div>
        }
      }
      <!-- Summary stats -->
      <div class="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
        <div class="rounded-xl bg-white border border-gray-200 shadow-card p-4">
          <div class="text-xs text-gray-400 uppercase tracking-wide">Étudiants notés</div>
          <div class="text-2xl font-semibold text-gray-900 tabular-nums">{{ rows().length }}</div>
        </div>
        <div class="rounded-xl bg-white border border-gray-200 shadow-card p-4">
          <div class="text-xs text-gray-400 uppercase tracking-wide">Moyenne promo</div>
          <div class="text-2xl font-semibold text-gray-900 tabular-nums">
            {{ moyennePromo() != null ? (moyennePromo() | number: '1.1-1') + ' /20' : '—' }}
          </div>
        </div>
        <div class="rounded-xl bg-white border border-gray-200 shadow-card p-4">
          <div class="text-xs text-gray-400 uppercase tracking-wide">Taux de réussite</div>
          <div class="text-2xl font-semibold text-gray-900 tabular-nums">
            {{ tauxReussite() != null ? (tauxReussite() | number: '1.0-0') + ' %' : '—' }}
          </div>
          <div class="text-xs text-gray-400 mt-0.5">≥ 10/20</div>
        </div>
        <div class="rounded-xl bg-white border border-gray-200 shadow-card p-4">
          <div class="text-xs text-gray-400 uppercase tracking-wide">Min · Max</div>
          <div class="text-2xl font-semibold text-gray-900 tabular-nums">
            {{ minMaxLabel() }}
          </div>
        </div>
      </div>

      <div class="flex items-center justify-between mb-3">
        <h2 class="text-sm font-medium text-gray-500">
          Classement &middot; {{ stationCols().length }} stations
        </h2>
        <button
          type="button"
          (click)="exportCsv()"
          class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-gray-300 text-gray-600 text-sm font-medium hover:bg-gray-50 transition-colors"
        >
          Exporter (CSV)
        </button>
      </div>

      <!-- Results table -->
      <div class="rounded-xl bg-white border border-gray-200 shadow-card overflow-x-auto">
        <table class="w-full text-sm">
          <thead>
            <tr class="border-b border-gray-200 text-left text-gray-500">
              <th class="px-3 py-3 font-medium w-12">Rang</th>
              <th class="px-3 py-3 font-medium">Étudiant</th>
              <th class="px-3 py-3 font-medium">Échantillon</th>
              @for (col of stationCols(); track col.stationId) {
                <th class="px-3 py-3 font-medium text-center whitespace-nowrap" [title]="col.nom">
                  <span class="text-gray-400">#{{ col.ordre }}</span>
                  <span class="block text-xs text-gray-400 font-normal">/ {{ col.noteMax }}</span>
                </th>
              }
              <th class="px-3 py-3 font-medium text-center">Moyenne</th>
              <th class="px-3 py-3 font-medium">Mention</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-gray-50">
            @for (row of rows(); track row.participationId) {
              <tr class="hover:bg-gray-50/60">
                <td class="px-3 py-2.5 tabular-nums text-gray-400">{{ row.rang }}</td>
                <td class="px-3 py-2.5">
                  <div class="font-medium text-gray-900">{{ row.nom }}</div>
                  @if (row.numeroInscription) {
                    <div class="text-xs text-gray-400">N° {{ row.numeroInscription }}</div>
                  }
                </td>
                <td class="px-3 py-2.5 text-gray-500 tabular-nums">{{ row.numEchantillon || '—' }}</td>
                @for (col of stationCols(); track col.stationId) {
                  <td class="px-1 py-2 text-center tabular-nums">
                    @if (row.scoreByStation.get(col.stationId) != null) {
                      <!-- Scored cell: click to drill into the per-critère breakdown. -->
                      <button
                        type="button"
                        (click)="toggleCell(row, col)"
                        [attr.aria-expanded]="isExpanded(row, col)"
                        [title]="'Voir le détail par critère · ' + col.nom"
                        class="inline-flex items-center gap-1 px-2 py-0.5 rounded hover:bg-brand-50 transition-colors"
                        [class.bg-brand-50]="isExpanded(row, col)"
                      >
                        <span [class.text-status-danger]="isStationFail(row, col)">
                          {{ row.scoreByStation.get(col.stationId) | number: '1.0-1' }}
                        </span>
                        @if (!row.lockedByStation.get(col.stationId)) {
                          <span class="w-1.5 h-1.5 rounded-full bg-amber-400" title="Note non verrouillée"></span>
                        }
                        <svg class="w-3 h-3 text-gray-300 transition-transform" [class.rotate-180]="isExpanded(row, col)" fill="none" viewBox="0 0 24 24" stroke-width="2.5" stroke="currentColor" aria-hidden="true">
                          <path stroke-linecap="round" stroke-linejoin="round" d="m19.5 8.25-7.5 7.5-7.5-7.5" />
                        </svg>
                      </button>
                    } @else {
                      <span class="text-gray-300">—</span>
                    }
                  </td>
                }
                <td class="px-3 py-2.5 text-center font-semibold tabular-nums">
                  {{ row.moyenne20 != null ? (row.moyenne20 | number: '1.1-1') : '—' }}
                </td>
                <td class="px-3 py-2.5">
                  <span class="text-xs font-medium px-2 py-0.5 rounded-full" [class]="row.mentionClass">
                    {{ row.mention }}
                  </span>
                </td>
              </tr>
              <!-- Per-critère deep-dive (gap #3): one expansion row under the student,
                   for whichever station cell is open. -->
              @if (expandedColFor(row); as col) {
                <tr class="bg-gray-50/80">
                  <td [attr.colspan]="stationCols().length + 5" class="px-4 py-3 border-t border-gray-100">
                    <div class="flex items-center justify-between mb-2">
                      <h4 class="text-xs font-semibold text-gray-600 uppercase tracking-wide">
                        Détail par critère · {{ col.nom }}
                        <span class="ml-1 font-normal text-gray-400 normal-case">— {{ row.nom }}</span>
                      </h4>
                      <button type="button" (click)="toggleCell(row, col)" class="text-xs text-gray-400 hover:text-gray-600">Fermer</button>
                    </div>
                    @switch (deepDiveFor(row, col)?.state) {
                      @case ('loading') {
                        <div class="h-12 rounded bg-gray-200/70 animate-pulse"></div>
                      }
                      @case ('error') {
                        <p class="text-sm text-status-danger">Impossible de charger le détail des critères.</p>
                      }
                      @case ('ready') {
                        @let dd = deepDiveFor(row, col);
                        @if (dd && dd.state === 'ready') {
                          @if (!dd.hasDetail) {
                            <p class="text-xs text-gray-500 mb-2 italic">
                              Note saisie globalement — pas de détail par critère pour cette station.
                              La grille comporte {{ dd.lignes.length }} critère{{ dd.lignes.length > 1 ? 's' : '' }} :
                            </p>
                          }
                          <table class="w-full text-sm bg-white rounded-lg border border-gray-200 overflow-hidden">
                            <thead>
                              <tr class="text-left text-gray-400 border-b border-gray-100">
                                <th class="px-3 py-2 font-medium">Critère</th>
                                <th class="px-3 py-2 font-medium text-center w-28">Note</th>
                                <th class="px-3 py-2 font-medium">Commentaire</th>
                              </tr>
                            </thead>
                            <tbody class="divide-y divide-gray-50">
                              @for (l of dd.lignes; track l.itemId) {
                                <tr>
                                  <td class="px-3 py-2 text-gray-700">{{ l.libelle }}</td>
                                  <td class="px-3 py-2 text-center tabular-nums">
                                    @if (l.valeur == null) {
                                      <span class="text-gray-300">—</span>
                                    } @else if (l.binaire) {
                                      @if (l.valeur >= 1) {
                                        <span class="text-emerald-700 font-medium">Acquis</span>
                                        @if (l.bareme != null) { <span class="text-gray-400"> (+{{ l.bareme }})</span> }
                                      } @else {
                                        <span class="text-gray-500">Non acquis</span>
                                        <span class="text-gray-400"> (0)</span>
                                      }
                                    } @else {
                                      <span class="font-medium text-gray-800">{{ l.valeur | number: '1.0-1' }}</span>
                                      @if (l.bareme != null) { <span class="text-gray-400"> / {{ l.bareme }}</span> }
                                    }
                                  </td>
                                  <td class="px-3 py-2 text-gray-500">{{ l.commentaire || '—' }}</td>
                                </tr>
                              }
                            </tbody>
                          </table>
                          @if (isLocked(row, col)) {
                            @let notationId = notationIdOf(row, col);
                            <div class="mt-3 rounded-lg border border-amber-200 bg-amber-50/60 p-3">
                              <div class="flex items-center justify-between">
                                <h5 class="text-xs font-semibold text-amber-800 uppercase tracking-wide">
                                  Note verrouillée — réajustement sur réclamation
                                </h5>
                                @if (reajustOpen() !== notationId) {
                                  <button type="button"
                                    (click)="openReajust(notationId!, row.scoreByStation.get(col.stationId) ?? null)"
                                    class="text-xs font-medium text-amber-800 underline hover:text-amber-900">
                                    Réajuster (réclamation)
                                  </button>
                                }
                              </div>
                              @if (notationId != null && adjustmentsFor(notationId).length > 0) {
                                <ul class="mt-2 space-y-1">
                                  @for (a of adjustmentsFor(notationId); track a.id) {
                                    <li class="text-xs text-gray-600">
                                      <span class="font-medium tabular-nums text-gray-800">{{ a.ancienScore | number: '1.0-2' }} → {{ a.nouveauScore | number: '1.0-2' }}</span>
                                      <span class="text-gray-400"> · {{ a.adjustedAt | date: 'dd/MM/yyyy HH:mm' }}</span>
                                      <span class="block italic text-gray-500">« {{ a.motif }} »</span>
                                    </li>
                                  }
                                </ul>
                              } @else if (reajustOpen() !== notationId) {
                                <p class="mt-1 text-xs italic text-gray-400">Aucun réajustement pour cette note.</p>
                              }
                              @if (reajustOpen() === notationId) {
                                <div class="mt-2 space-y-2">
                                  <label class="block text-xs text-gray-600">
                                    Nouvelle note (total station)
                                    <input type="number" step="0.5" [value]="reajustValeur() ?? ''"
                                      (input)="reajustValeur.set($any($event.target).valueAsNumber)"
                                      class="mt-1 block w-32 rounded border border-gray-300 px-2 py-1 text-sm tabular-nums" />
                                  </label>
                                  <label class="block text-xs text-gray-600">
                                    Motif de la réclamation <span class="text-status-danger">*</span>
                                    <textarea rows="2" (input)="reajustMotif.set($any($event.target).value)"
                                      class="mt-1 block w-full rounded border border-gray-300 px-2 py-1 text-sm"
                                      placeholder="Ex. : recomptage suite à la réclamation de l'étudiant"></textarea>
                                  </label>
                                  @if (reajustError()) {
                                    <p class="text-xs text-status-danger">{{ reajustError() }}</p>
                                  }
                                  <div class="flex gap-2">
                                    <button type="button" (click)="submitReajust(notationId!)" [disabled]="reajustBusy()"
                                      class="rounded bg-amber-600 px-3 py-1 text-xs font-medium text-white hover:bg-amber-700 disabled:opacity-50">
                                      {{ reajustBusy() ? 'Enregistrement…' : 'Enregistrer le réajustement' }}
                                    </button>
                                    <button type="button" (click)="cancelReajust()" [disabled]="reajustBusy()"
                                      class="rounded px-3 py-1 text-xs text-gray-500 hover:text-gray-700">Annuler</button>
                                  </div>
                                  <p class="text-[11px] text-gray-400">
                                    La note reste verrouillée. Le changement est tracé (qui, quand, ancienne → nouvelle valeur, motif).
                                  </p>
                                </div>
                              }
                            </div>
                          }
                        }
                      }
                    }
                  </td>
                </tr>
              }
            }
          </tbody>
        </table>
      </div>
      }
    }
  `,
})
export class ResultatsComponent {
  private readonly examApi = inject(ExamApiService);
  private readonly scoring = inject(ScoringApiService);
  private readonly store = inject(ExamenWorkspaceStore);

  /** Inherited from the parent examens/:id route via withComponentInputBinding(). */
  readonly id = input.required<string>();

  readonly loading = signal(true);
  readonly error = signal(false);
  readonly rows = signal<ResultRow[]>([]);
  readonly stationCols = signal<StationCol[]>([]);

  /** Exam-level scoring-completeness summary for the warning banner. */
  readonly completeness = signal<CompletenessSummary | null>(null);

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
    // The roster (participations) gives the present-student denominator the
    // results endpoint can't — it only returns students who have ≥1 notation.
    forkJoin({
      results: this.scoring.getExamenResults(examId),
      stations: this.examApi.listStations(examId),
      participations: this.scoring.listParticipations(examId),
    }).subscribe({
      next: ({ results, stations, participations }) => {
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
        const scored = stations.filter((s) => scoredStationIds.has(s.id));
        const grilleCalls = scored.map((s) =>
          s.hasGrille ? this.examApi.getStationGrille(s.id) : of(null),
        );
        forkJoin(grilleCalls.length ? grilleCalls : [of(null)]).subscribe({
          next: (grilles) => {
            const noteMaxByStation = new Map<number, number>();
            scored.forEach((s, i) => {
              const g = grilles[i];
              noteMaxByStation.set(s.id, g?.noteMax ?? DEFAULT_NOTE_MAX);
              if (g) this.grilleByStation.set(s.id, g);
            });
            this.buildTable(results, stations, noteMaxByStation);
            this.completeness.set(this.computeCompleteness(results, stations, participations));
            this.loading.set(false);
          },
          error: () => {
            // Grilles failed — still render with the default /20 denominator.
            const noteMaxByStation = new Map<number, number>();
            scored.forEach((s) => noteMaxByStation.set(s.id, DEFAULT_NOTE_MAX));
            this.buildTable(results, stations, noteMaxByStation);
            this.completeness.set(this.computeCompleteness(results, stations, participations));
            this.loading.set(false);
          },
        });
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
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
    const noted = new Map(results.map((r) => [r.participationId, r]));
    let nonNotes = 0;
    let partiels = 0;
    for (const p of present) {
      const r = noted.get(p.id);
      if (!r || r.stationsNotees === 0) nonNotes++;
      else if (totalStations > 0 && r.stationsNotees < totalStations) partiels++;
    }
    let nonVerrouillees = 0;
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
    // Column set = scored stations, ordered by their exam ordre.
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
      for (const s of r.stations) {
        if (s.stationId == null) continue;
        scoreByStation.set(s.stationId, s.score);
        lockedByStation.set(s.stationId, s.verrouillee === true);
        if (s.notationId != null) notationIdByStation.set(s.stationId, s.notationId);
        if (s.score != null) totalMax += noteMaxByStation.get(s.stationId) ?? DEFAULT_NOTE_MAX;
      }
      const moyenne20 = totalMax > 0 ? (r.totalScore / totalMax) * 20 : null;
      const { mention, mentionClass } = this.mentionFor(moyenne20);
      return {
        rang: 0, // assigned after sort
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
      };
    });

    // Rank on the normalised /20 average (null averages sink to the bottom).
    rows.sort((a, b) => (b.moyenne20 ?? -1) - (a.moyenne20 ?? -1));
    rows.forEach((row, i) => (row.rang = i + 1));
    this.rows.set(rows);
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
    const grilleItems: GrilleItem[] = (grille?.items ?? [])
      .slice()
      .sort((a, b) => (a.ordre ?? 0) - (b.ordre ?? 0));
    if (grilleItems.length === 0) {
      // Grille unavailable — surface the raw recorded values rather than nothing.
      return items.map((it) => ({
        itemId: it.item_id ?? 0,
        libelle: it.item_id != null ? `Critère #${it.item_id}` : 'Critère',
        binaire: false,
        bareme: null,
        valeur: it.valeur,
        commentaire: it.commentaire,
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
      };
    });
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

  private mentionFor(moyenne20: number | null): { mention: string; mentionClass: string } {
    if (moyenne20 == null) return { mention: 'Non noté', mentionClass: 'bg-gray-100 text-gray-500' };
    if (moyenne20 >= 16) return { mention: 'Très bien', mentionClass: 'bg-green-100 text-green-700' };
    if (moyenne20 >= 14) return { mention: 'Bien', mentionClass: 'bg-emerald-50 text-emerald-700' };
    if (moyenne20 >= 12) return { mention: 'Assez bien', mentionClass: 'bg-brand-50 text-brand-dark' };
    if (moyenne20 >= 10) return { mention: 'Passable', mentionClass: 'bg-amber-50 text-amber-700' };
    return { mention: 'Insuffisant', mentionClass: 'bg-red-100 text-red-700' };
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
    ];
    const lines = this.rows().map((row) => {
      const cells = [
        row.rang,
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
