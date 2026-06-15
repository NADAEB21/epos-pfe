import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { forkJoin, of } from 'rxjs';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ScoringApiService } from '../../core/api/scoring-api.service';
import { ExamenResult, StationSummary } from '../../core/api/models';
import { ExamenWorkspaceStore } from './examen-workspace.store';

/** A station column header — name + ordre + the grille's noteMax denominator. */
interface StationCol {
  stationId: number;
  nom: string;
  ordre: number;
  noteMax: number;
}

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
  imports: [DecimalPipe],
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
                  <td class="px-3 py-2.5 text-center tabular-nums">
                    @if (row.scoreByStation.get(col.stationId) != null) {
                      <span [class.text-status-danger]="isStationFail(row, col)">
                        {{ row.scoreByStation.get(col.stationId) | number: '1.0-1' }}
                      </span>
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
    forkJoin({
      results: this.scoring.getExamenResults(examId),
      stations: this.examApi.listStations(examId),
    }).subscribe({
      next: ({ results, stations }) => {
        if (results.length === 0) {
          this.rows.set([]);
          this.stationCols.set([]);
          this.loading.set(false);
          return;
        }
        // Resolve each scored station's grille noteMax (once, for the headers).
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
            });
            this.buildTable(results, stations, noteMaxByStation);
            this.loading.set(false);
          },
          error: () => {
            // Grilles failed — still render with the default /20 denominator.
            const noteMaxByStation = new Map<number, number>();
            scored.forEach((s) => noteMaxByStation.set(s.id, DEFAULT_NOTE_MAX));
            this.buildTable(results, stations, noteMaxByStation);
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
      let totalMax = 0;
      for (const s of r.stations) {
        if (s.stationId == null) continue;
        scoreByStation.set(s.stationId, s.score);
        lockedByStation.set(s.stationId, s.verrouillee === true);
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
