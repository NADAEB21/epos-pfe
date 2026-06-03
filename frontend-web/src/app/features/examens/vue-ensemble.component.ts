import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ExamenResponse, StationSummary, TypeStation } from '../../core/api/models';

type ReadinessState = 'ok' | 'todo' | 'unknown';

interface ReadinessItem {
  label: string;
  state: ReadinessState;
  hint?: string;
}

const TYPE_LABELS: Record<TypeStation, string> = {
  PRATIQUE: 'Pratique',
  THEORIQUE: 'Théorique',
};

/**
 * Read-only per-exam dashboard. BROUILLON/CONFIGURE landing tab and the target of
 * the Accueil "Continuer la configuration" CTA, so it closes the click-through.
 *
 * Station data comes from the dedicated GET /examens/{id}/stations endpoint, not
 * the stations embedded in getExamen(id): only the dedicated endpoint populates
 * evaluateurIds + hasGrille, which the per-station coverage + readiness summary
 * need. The exam call still supplies meta (dates, durée, sujet PDF, statut).
 */
@Component({
  selector: 'app-vue-ensemble',
  standalone: true,
  template: `
    @if (loading()) {
      <div class="space-y-6 animate-pulse">
        <div class="h-32 rounded-xl bg-gray-200"></div>
        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          <div class="h-20 rounded-xl bg-gray-200"></div>
          <div class="h-20 rounded-xl bg-gray-200"></div>
          <div class="h-20 rounded-xl bg-gray-200"></div>
          <div class="h-20 rounded-xl bg-gray-200"></div>
        </div>
        <div class="h-40 rounded-xl bg-gray-200"></div>
      </div>
    } @else if (error()) {
      <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card">
        <p class="text-gray-700 mb-1">Impossible de charger la vue d'ensemble.</p>
        <p class="text-sm text-gray-500 mb-4">Verifiez votre connexion puis reessayez.</p>
        <button
          type="button"
          (click)="reload()"
          class="inline-flex items-center px-4 py-2 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark transition-colors"
        >
          Reessayer
        </button>
      </div>
    } @else {
      @if (exam(); as e) {
      <!-- Readiness summary -->
      <section class="rounded-xl bg-white border border-gray-200 shadow-card p-5 mb-6">
        <div class="flex items-center justify-between gap-4 flex-wrap mb-4">
          <h2 class="font-semibold text-gray-900">Etat de preparation</h2>
          <span class="text-xs text-gray-500">{{ readinessSummary() }}</span>
        </div>
        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
          @for (item of readiness(); track item.label) {
            <div class="flex items-start gap-2 text-sm">
              <span
                class="w-2.5 h-2.5 rounded-full shrink-0 mt-1.5"
                [class.bg-status-success]="item.state === 'ok'"
                [class.bg-status-warning]="item.state === 'todo'"
                [class.bg-gray-300]="item.state === 'unknown'"
              ></span>
              <span>
                <span
                  [class.text-gray-700]="item.state !== 'unknown'"
                  [class.text-gray-400]="item.state === 'unknown'"
                  >{{ item.label }}</span
                >
                @if (item.hint) {
                  <span class="block text-xs text-gray-400">{{ item.hint }}</span>
                }
              </span>
            </div>
          }
        </div>
      </section>

      <!-- Key facts -->
      <section class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
        <div class="rounded-xl bg-white border border-gray-200 shadow-card p-4">
          <div class="text-2xl font-semibold text-gray-900">{{ e.dateExamen }}</div>
          <div class="text-sm text-gray-500">date de l'examen</div>
        </div>
        <div class="rounded-xl bg-white border border-gray-200 shadow-card p-4">
          <div class="text-2xl font-semibold text-gray-900">{{ value(e.dureeStationMin, 'min') }}</div>
          <div class="text-sm text-gray-500">duree / station</div>
        </div>
        <div class="rounded-xl bg-white border border-gray-200 shadow-card p-4">
          <div class="text-2xl font-semibold text-gray-900">{{ value(e.nbEtudiantsParStation) }}</div>
          <div class="text-sm text-gray-500">etudiants / station</div>
        </div>
        <div class="rounded-xl bg-white border border-gray-200 shadow-card p-4">
          <div class="text-2xl font-semibold text-gray-900">{{ stations().length }}</div>
          <div class="text-sm text-gray-500">stations</div>
        </div>
      </section>

      <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <!-- Description + sujet -->
        <section class="rounded-xl bg-white border border-gray-200 shadow-card p-5 lg:col-span-1">
          <h3 class="font-semibold text-gray-900 mb-3">Informations</h3>
          <dl class="space-y-3 text-sm">
            <div>
              <dt class="text-gray-400">Description</dt>
              <dd class="text-gray-700">{{ e.description || '—' }}</dd>
            </div>
            <div>
              <dt class="text-gray-400">Sujet PDF</dt>
              <dd>
                @if (e.hasPdfSujet) {
                  <span class="inline-flex items-center gap-1.5 text-gray-700">
                    <span class="w-2 h-2 rounded-full bg-status-success"></span>
                    {{ e.pdfSujetNom || 'Document joint' }}
                  </span>
                } @else {
                  <span class="inline-flex items-center gap-1.5 text-gray-400">
                    <span class="w-2 h-2 rounded-full bg-status-warning"></span>
                    Aucun sujet
                  </span>
                }
              </dd>
            </div>
          </dl>
        </section>

        <!-- Stations + coverage -->
        <section class="rounded-xl bg-white border border-gray-200 shadow-card p-5 lg:col-span-2">
          <div class="flex items-center justify-between mb-3">
            <h3 class="font-semibold text-gray-900">Stations &amp; couverture</h3>
            <a
              [routerLink]="['/examens', e.id, 'stations-grilles']"
              class="text-xs text-brand hover:underline"
              >Gerer</a
            >
          </div>
          @if (stations().length === 0) {
            <p class="text-sm text-gray-400">
              Aucune station definie. Commencez par la configuration des stations.
            </p>
          } @else {
            <ul class="divide-y divide-gray-100">
              @for (s of stations(); track s.id) {
                <li class="flex items-center justify-between gap-3 py-2.5">
                  <div class="min-w-0">
                    <div class="text-sm text-gray-800 truncate">
                      <span class="text-gray-400">{{ s.ordre ?? '·' }}.</span>
                      {{ s.nom || 'Station ' + (s.ordre ?? '') }}
                      @if (s.type) {
                        <span class="ml-1 text-xs text-gray-400">{{ typeLabel(s.type) }}</span>
                      }
                    </div>
                  </div>
                  <div class="flex items-center gap-2 shrink-0">
                    <!-- grille -->
                    <span
                      class="text-xs px-2 py-0.5 rounded-full"
                      [class.bg-brand-50]="s.hasGrille"
                      [class.text-brand-dark]="s.hasGrille"
                      [class.bg-gray-100]="!s.hasGrille"
                      [class.text-gray-400]="!s.hasGrille"
                      >{{ s.hasGrille ? 'Grille' : 'Sans grille' }}</span
                    >
                    <!-- evaluateur coverage -->
                    <span
                      class="text-xs px-2 py-0.5 rounded-full"
                      [class.bg-status-success]="evalCount(s) > 0"
                      [class.text-white]="evalCount(s) > 0"
                      [class.bg-gray-100]="evalCount(s) === 0"
                      [class.text-gray-400]="evalCount(s) === 0"
                      >{{ evalLabel(s) }}</span
                    >
                  </div>
                </li>
              }
            </ul>
          }
        </section>
      </div>
      }
    }
  `,
  imports: [RouterLink],
})
export class VueEnsembleComponent {
  private readonly examApi = inject(ExamApiService);

  /** Inherited from the parent examens/:id route via withComponentInputBinding(). */
  readonly id = input.required<string>();

  readonly exam = signal<ExamenResponse | null>(null);
  readonly stations = signal<StationSummary[]>([]);
  readonly loading = signal(true);
  readonly error = signal(false);

  constructor() {
    effect(() => {
      const examId = Number(this.id());
      if (!Number.isFinite(examId)) {
        this.error.set(true);
        this.loading.set(false);
        return;
      }
      this.load(examId);
    });
  }

  reload(): void {
    this.load(Number(this.id()));
  }

  private load(examId: number): void {
    this.loading.set(true);
    this.error.set(false);
    forkJoin({
      exam: this.examApi.getExamen(examId),
      stations: this.examApi.listStations(examId),
    }).subscribe({
      next: ({ exam, stations }) => {
        this.exam.set(exam);
        this.stations.set(stations);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  /** Stations with no évaluateur assigned (coverage gap). */
  private readonly stationsSansEvaluateur = computed(
    () => this.stations().filter((s) => this.evalCount(s) === 0).length,
  );

  /** Stations with no grille attached. */
  private readonly stationsSansGrille = computed(
    () => this.stations().filter((s) => !s.hasGrille).length,
  );

  readonly readinessSummary = computed(() => {
    const total = this.stations().length;
    if (total === 0) return 'Aucune station';
    const parts = [`${total} station(s)`];
    if (this.stationsSansEvaluateur() > 0) parts.push(`${this.stationsSansEvaluateur()} sans evaluateur`);
    if (this.stationsSansGrille() > 0) parts.push(`${this.stationsSansGrille()} sans grille`);
    if (this.stationsSansEvaluateur() === 0 && this.stationsSansGrille() === 0) parts.push('couverture complete');
    return parts.join(' · ');
  });

  readonly readiness = computed<ReadinessItem[]>(() => {
    const e = this.exam();
    const stations = this.stations();
    const hasStations = stations.length > 0;

    // ok if all stations covered, todo if any gap, unknown until stations exist.
    const coverState = (gap: number): ReadinessState =>
      !hasStations ? 'unknown' : gap === 0 ? 'ok' : 'todo';

    return [
      {
        label: 'Stations definies',
        state: hasStations ? 'ok' : 'todo',
        hint: hasStations ? `${stations.length} station(s)` : undefined,
      },
      {
        label: 'Evaluateurs affectes',
        state: coverState(this.stationsSansEvaluateur()),
        hint:
          hasStations && this.stationsSansEvaluateur() > 0
            ? `${this.stationsSansEvaluateur()} station(s) sans evaluateur`
            : undefined,
      },
      {
        label: 'Grilles completes',
        state: coverState(this.stationsSansGrille()),
        hint:
          hasStations && this.stationsSansGrille() > 0
            ? `${this.stationsSansGrille()} station(s) sans grille`
            : undefined,
      },
      { label: 'Sujet PDF', state: e?.hasPdfSujet ? 'ok' : 'todo' },
      // Roster / planning / launch readiness need the backend pre-launch
      // validation endpoint (Task B backlog) — neutral until then.
      { label: 'Roster charge', state: 'unknown' },
      { label: 'Pret au lancement', state: 'unknown' },
    ];
  });

  evalCount(s: StationSummary): number {
    return s.evaluateurIds?.length ?? 0;
  }

  evalLabel(s: StationSummary): string {
    const n = this.evalCount(s);
    return n === 0 ? 'Aucun evaluateur' : `${n} evaluateur(s)`;
  }

  typeLabel(t: TypeStation): string {
    return TYPE_LABELS[t];
  }

  value(n: number | null, suffix = ''): string {
    if (n == null) return '—';
    return suffix ? `${n} ${suffix}` : `${n}`;
  }
}
