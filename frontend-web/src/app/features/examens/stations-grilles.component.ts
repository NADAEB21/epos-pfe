import { Component, computed, effect, inject, signal } from '@angular/core';
import { input } from '@angular/core';
import { forkJoin } from 'rxjs';
import { ExamApiService } from '../../core/api/exam-api.service';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import { GrilleDetail, StationSummary, TypeStation, UserResponse } from '../../core/api/models';

type GrilleState = { loading: boolean; error: boolean; grille: GrilleDetail | null };

const TYPE_LABELS: Record<TypeStation, string> = {
  PRATIQUE: 'Pratique',
  THEORIQUE: 'Théorique',
};

/**
 * Stations & Grilles tab — the pre-exam binding workflow (ADR-0007: binding is
 * pre-exam, in the workspace). Lists the exam's stations, lets the responsable
 * attach/detach évaluateurs (PATCH replaces the whole list), and shows each
 * station's grille read-only on demand.
 *
 * Two-call initial load: listStations() for the rows (the only source carrying
 * evaluateurIds + hasGrille) and the évaluateur directory to resolve ids → names.
 * Grille items are fetched lazily per station (GET /stations/{id}) when expanded,
 * to avoid an N+1 on first paint.
 */
@Component({
  selector: 'app-stations-grilles',
  standalone: true,
  template: `
    @if (loading()) {
      <div class="space-y-4 animate-pulse">
        <div class="h-28 rounded-xl bg-gray-200"></div>
        <div class="h-28 rounded-xl bg-gray-200"></div>
        <div class="h-28 rounded-xl bg-gray-200"></div>
      </div>
    } @else if (error()) {
      <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card">
        <p class="text-gray-700 mb-1">Impossible de charger les stations.</p>
        <p class="text-sm text-gray-500 mb-4">Verifiez votre connexion puis reessayez.</p>
        <button
          type="button"
          (click)="reload()"
          class="inline-flex items-center px-4 py-2 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark transition-colors"
        >
          Reessayer
        </button>
      </div>
    } @else if (stations().length === 0) {
      <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card">
        <p class="text-gray-700 mb-1">Aucune station definie.</p>
        <p class="text-sm text-gray-500">
          La creation de stations arrivera dans un prochain ecran. Pour l'instant,
          cet onglet affecte les evaluateurs et consulte les grilles existantes.
        </p>
      </div>
    } @else {
      <ul class="space-y-4">
        @for (s of stations(); track s.id) {
          <li class="rounded-xl bg-white border border-gray-200 shadow-card p-5">
            <!-- header -->
            <div class="flex items-start justify-between gap-3 mb-4">
              <div class="min-w-0">
                <div class="text-sm font-semibold text-gray-900 truncate">
                  <span class="text-gray-400">{{ s.ordre ?? '·' }}.</span>
                  {{ s.nom || 'Station ' + (s.ordre ?? '') }}
                  @if (s.type) {
                    <span class="ml-1 text-xs font-normal text-gray-400">{{ typeLabel(s.type) }}</span>
                  }
                </div>
                @if (s.description) {
                  <p class="text-xs text-gray-500 mt-0.5">{{ s.description }}</p>
                }
              </div>
              <span
                class="text-xs px-2 py-0.5 rounded-full shrink-0"
                [class.bg-status-success]="evalCount(s) > 0"
                [class.text-white]="evalCount(s) > 0"
                [class.bg-gray-100]="evalCount(s) === 0"
                [class.text-gray-400]="evalCount(s) === 0"
                >{{ evalLabel(s) }}</span
              >
            </div>

            <!-- évaluateurs -->
            <div class="mb-4">
              <div class="text-xs text-gray-400 mb-2">Evaluateurs</div>
              <div class="flex flex-wrap items-center gap-2">
                @for (id of s.evaluateurIds ?? []; track id) {
                  <span
                    class="inline-flex items-center gap-1.5 text-xs bg-brand-50 text-brand-dark px-2.5 py-1 rounded-full"
                  >
                    {{ evalName(id) }}
                    <button
                      type="button"
                      [disabled]="savingId() === s.id"
                      (click)="removeEvaluateur(s, id)"
                      class="text-brand-dark/60 hover:text-brand-dark disabled:opacity-40"
                      [attr.aria-label]="'Retirer ' + evalName(id)"
                    >
                      &times;
                    </button>
                  </span>
                } @empty {
                  <span class="text-xs text-gray-400">Aucun evaluateur affecte</span>
                }

                <!-- picker -->
                @if (available(s); as opts) {
                  @if (opts.length > 0) {
                    <select
                      #pick
                      [disabled]="savingId() === s.id"
                      (change)="addEvaluateur(s, pick.value); pick.value = ''"
                      class="text-xs border border-gray-200 rounded-full px-2.5 py-1 text-gray-600 bg-white disabled:opacity-40"
                    >
                      <option value="">+ ajouter</option>
                      @for (u of opts; track u.id) {
                        <option [value]="u.id">{{ u.prenom }} {{ u.nom }}</option>
                      }
                    </select>
                  }
                }
                @if (savingId() === s.id) {
                  <span class="text-xs text-gray-400">Enregistrement…</span>
                }
              </div>
              @if (saveErrorId() === s.id) {
                <p class="text-xs text-status-danger mt-1.5">
                  Echec de l'enregistrement. Reessayez.
                </p>
              }
            </div>

            <!-- grille -->
            <div class="border-t border-gray-100 pt-3">
              @if (s.hasGrille) {
                <button
                  type="button"
                  (click)="toggleGrille(s)"
                  class="flex items-center gap-1.5 text-sm text-brand hover:underline"
                >
                  <span>{{ expandedId() === s.id ? '▾' : '▸' }}</span>
                  Grille d'evaluation
                </button>
                @if (expandedId() === s.id) {
                  @let g = grilleStates()[s.id];
                  <div class="mt-3">
                    @if (g?.loading) {
                      <div class="h-16 rounded-lg bg-gray-100 animate-pulse"></div>
                    } @else if (g?.error) {
                      <div class="text-sm text-gray-500 flex items-center gap-3">
                        <span>Impossible de charger la grille.</span>
                        <button
                          type="button"
                          (click)="loadGrille(s.id)"
                          class="text-brand hover:underline"
                        >
                          Reessayer
                        </button>
                      </div>
                    } @else {
                      <!-- the as alias does not bind on else-if (NG5002): nest if in else. -->
                      @if (g?.grille; as grille) {
                      <div class="rounded-lg bg-surface border border-gray-100 p-4">
                        <div class="flex items-center justify-between gap-3 mb-3">
                          <div class="text-sm font-medium text-gray-800">{{ grille.nom }}</div>
                          <div class="text-xs text-gray-500">
                            {{ grille.nombreItems ?? grille.items?.length ?? 0 }} item(s) ·
                            note max {{ grille.noteMax ?? '—' }}
                            @if (grille.ponderationValide === false) {
                              <span class="text-status-warning"> · ponderation incomplete</span>
                            }
                          </div>
                        </div>
                        @if (grille.items?.length) {
                          <ul class="divide-y divide-gray-100">
                            @for (it of grille.items; track it.id) {
                              <li class="flex items-start justify-between gap-3 py-2 text-sm">
                                <span class="text-gray-700">
                                  <span class="text-gray-400">{{ it.ordre ?? '·' }}.</span>
                                  {{ it.libelle }}
                                  @if (it.categorie) {
                                    <span class="ml-1 text-xs text-gray-400">{{ it.categorie }}</span>
                                  }
                                </span>
                                <span class="text-xs text-gray-500 shrink-0 text-right">
                                  {{ it.type === 'BINAIRE' ? 'Binaire' : 'Numerique' }}
                                  @if (it.type !== 'BINAIRE' && it.valeurMax != null) {
                                    · /{{ it.valeurMax }}
                                  }
                                  · pond. {{ it.ponderation ?? '—' }}
                                </span>
                              </li>
                            }
                          </ul>
                        } @else {
                          <p class="text-sm text-gray-400">Grille sans item.</p>
                        }
                        <p class="text-xs text-gray-400 mt-3">
                          Lecture seule — l'edition des grilles arrivera dans un prochain ecran.
                        </p>
                      </div>
                      }
                    }
                  </div>
                }
              } @else {
                <div class="flex items-center gap-2 text-sm text-gray-400">
                  <span class="w-2 h-2 rounded-full bg-status-warning"></span>
                  Aucune grille attachee a cette station.
                </div>
              }
            </div>
          </li>
        }
      </ul>
    }
  `,
})
export class StationsGrillesComponent {
  private readonly examApi = inject(ExamApiService);
  private readonly directory = inject(DirectoryApiService);

  /** Inherited from the parent examens/:id route via withComponentInputBinding(). */
  readonly id = input.required<string>();

  readonly stations = signal<StationSummary[]>([]);
  readonly evaluateurs = signal<UserResponse[]>([]);
  readonly loading = signal(true);
  readonly error = signal(false);

  readonly savingId = signal<number | null>(null);
  readonly saveErrorId = signal<number | null>(null);

  readonly expandedId = signal<number | null>(null);
  readonly grilleStates = signal<Record<number, GrilleState>>({});

  /** id → directory entry, for resolving évaluateur names on chips. */
  private readonly evalMap = computed(() => {
    const m = new Map<number, UserResponse>();
    for (const u of this.evaluateurs()) m.set(u.id, u);
    return m;
  });

  constructor() {
    effect(() => {
      const examId = Number(this.id());
      if (!Number.isFinite(examId)) {
        this.error.set(true);
        this.loading.set(false);
        return;
      }
      this.load(examId);
    }, { allowSignalWrites: true });
  }

  reload(): void {
    this.load(Number(this.id()));
  }

  private load(examId: number): void {
    this.loading.set(true);
    this.error.set(false);
    this.expandedId.set(null);
    this.grilleStates.set({});
    forkJoin({
      stations: this.examApi.listStations(examId),
      evaluateurs: this.directory.listUsers('EVALUATEUR'),
    }).subscribe({
      next: ({ stations, evaluateurs }) => {
        this.stations.set(stations);
        this.evaluateurs.set(evaluateurs);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  // ---- évaluateur binding -------------------------------------------------

  /** Active évaluateurs not already bound to this station, for the picker. */
  available(s: StationSummary): UserResponse[] {
    const bound = new Set(s.evaluateurIds ?? []);
    return this.evaluateurs()
      .filter((u) => u.isActive && !bound.has(u.id))
      .sort((a, b) => a.nom.localeCompare(b.nom));
  }

  addEvaluateur(s: StationSummary, rawId: string): void {
    const id = Number(rawId);
    if (!Number.isFinite(id) || id === 0) return;
    if ((s.evaluateurIds ?? []).includes(id)) return;
    this.patchEvaluateurs(s, [...(s.evaluateurIds ?? []), id]);
  }

  removeEvaluateur(s: StationSummary, id: number): void {
    this.patchEvaluateurs(s, (s.evaluateurIds ?? []).filter((x) => x !== id));
  }

  private patchEvaluateurs(s: StationSummary, nextIds: number[]): void {
    this.savingId.set(s.id);
    this.saveErrorId.set(null);
    this.examApi.setStationEvaluateurs(s.id, nextIds).subscribe({
      next: (updated) => {
        // Refresh from the response rather than the optimistic guess — the
        // server is the source of truth for the bound list.
        this.replaceStation(s.id, { evaluateurIds: updated.evaluateurIds ?? nextIds });
        this.savingId.set(null);
      },
      error: () => {
        this.saveErrorId.set(s.id);
        this.savingId.set(null);
      },
    });
  }

  private replaceStation(id: number, patch: Partial<StationSummary>): void {
    this.stations.update((list) => list.map((s) => (s.id === id ? { ...s, ...patch } : s)));
  }

  // ---- grille (lazy) ------------------------------------------------------

  toggleGrille(s: StationSummary): void {
    if (this.expandedId() === s.id) {
      this.expandedId.set(null);
      return;
    }
    this.expandedId.set(s.id);
    const existing = this.grilleStates()[s.id];
    if (!existing || existing.error) this.loadGrille(s.id);
  }

  loadGrille(stationId: number): void {
    this.setGrilleState(stationId, { loading: true, error: false, grille: null });
    this.examApi.getStation(stationId).subscribe({
      next: (detail) =>
        this.setGrilleState(stationId, {
          loading: false,
          error: false,
          grille: detail.grille ?? null,
        }),
      error: () => this.setGrilleState(stationId, { loading: false, error: true, grille: null }),
    });
  }

  private setGrilleState(stationId: number, state: GrilleState): void {
    this.grilleStates.update((m) => ({ ...m, [stationId]: state }));
  }

  // ---- labels -------------------------------------------------------------

  evalCount(s: StationSummary): number {
    return s.evaluateurIds?.length ?? 0;
  }

  evalLabel(s: StationSummary): string {
    const n = this.evalCount(s);
    return n === 0 ? 'Aucun evaluateur' : `${n} evaluateur(s)`;
  }

  evalName(id: number): string {
    const u = this.evalMap().get(id);
    return u ? `${u.prenom} ${u.nom}` : `Evaluateur #${id}`;
  }

  typeLabel(t: TypeStation): string {
    return TYPE_LABELS[t];
  }
}
