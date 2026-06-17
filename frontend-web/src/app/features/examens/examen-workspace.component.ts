import { Component, computed, effect, inject, input } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ExamenResponse, StatutExamen } from '../../core/api/models';
import { LIFECYCLE, statutDisplayLabel } from '../../core/api/exam-status';
import { ExamenWorkspaceStore } from './examen-workspace.store';

interface WorkspaceTab {
  label: string;
  segment: string;
}

// Status-aware tab sets (Phase B per-exam workspace contract).
const TABS_SETUP: WorkspaceTab[] = [
  { label: "Vue d'ensemble", segment: 'vue-ensemble' },
  { label: 'Stations & Grilles', segment: 'stations-grilles' },
  { label: 'Étudiants', segment: 'etudiants' },
  { label: 'Lots', segment: 'lots' },
  { label: 'Convocations', segment: 'convocations' },
  { label: 'Planning', segment: 'planning' },
  { label: 'Lancement', segment: 'lancement' },
];
const TABS_LIVE: WorkspaceTab[] = [
  { label: 'Suivi en direct', segment: 'suivi' },
  { label: 'Lots & présence', segment: 'lots' },
  { label: 'Étudiants', segment: 'etudiants' },
  { label: 'Convocations', segment: 'convocations' },
  { label: 'Planning', segment: 'planning' },
  { label: 'Stations', segment: 'stations-grilles' },
];
const TABS_DONE: WorkspaceTab[] = [
  { label: 'Résultats', segment: 'resultats' },
  { label: 'Analyses IA', segment: 'analyses-ia' },
  { label: 'Stations & Grilles', segment: 'stations-grilles' },
  { label: 'Étudiants', segment: 'etudiants' },
];

@Component({
  selector: 'app-examen-workspace',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    @if (loading()) {
      <div class="h-24 rounded-xl bg-gray-200 animate-pulse mb-4"></div>
    } @else if (error()) {
      <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card">
        <p class="text-gray-700 mb-3">Examen introuvable.</p>
        <a [routerLink]="['/examens']" class="text-sm text-brand hover:underline">Retour a la liste</a>
      </div>
    } @else {
      @if (exam(); as e) {
      <header class="mb-4">
        <a [routerLink]="['/examens']" class="text-xs text-gray-400 hover:text-brand">&larr; Mes examens</a>
        <div class="flex items-center gap-3 mt-1">
          <h1 class="text-2xl font-semibold text-gray-900">{{ e.nom }}</h1>
          <span class="text-xs font-medium px-2 py-0.5 rounded-full bg-brand-50 text-brand-dark">
            {{ displayStatut(e) }}
          </span>
        </div>
        <p class="text-sm text-gray-500 mt-1">Matiere {{ e.matiereId }} &middot; {{ e.dateExamen }}</p>

        <!-- Lifecycle bar -->
        <div class="flex items-center gap-1 mt-4 max-w-xl">
          @for (step of lifecycle; track step) {
            <div
              class="h-1.5 flex-1 rounded-full"
              [class.bg-brand]="isReached(e.statut, step)"
              [class.bg-gray-200]="!isReached(e.statut, step)"
            ></div>
          }
        </div>
      </header>

      <!-- Status-aware tabs -->
      <nav class="flex flex-wrap gap-1 border-b border-gray-200 mb-6">
        @for (tab of tabs(); track tab.segment) {
          <a
            [routerLink]="[tab.segment]"
            routerLinkActive="border-brand text-brand"
            class="px-3 py-2 text-sm font-medium text-gray-500 border-b-2 border-transparent hover:text-gray-800"
          >
            {{ tab.label }}
          </a>
        }
      </nav>

      <router-outlet />
      }
    }
  `,
})
export class ExamenWorkspaceComponent {
  private readonly store = inject(ExamenWorkspaceStore);

  /** Bound from the :id route param via withComponentInputBinding(). */
  readonly id = input.required<string>();

  readonly lifecycle = LIFECYCLE;

  // Mirror the route-scoped store so the header, lifecycle bar and tab list all
  // react to a child mutating the exam (e.g. Lancement → changerStatut → reload).
  readonly exam = this.store.exam;
  readonly loading = this.store.loading;
  readonly error = this.store.error;

  readonly tabs = computed<WorkspaceTab[]>(() => {
    const e = this.exam();
    return e ? this.tabsFor(e.statut) : [];
  });

  constructor() {
    effect(() => {
      const examId = Number(this.id());
      if (!Number.isFinite(examId)) {
        this.store.error.set(true);
        this.store.loading.set(false);
        return;
      }
      this.store.load(examId);
    }, { allowSignalWrites: true });
  }

  private tabsFor(statut: StatutExamen): WorkspaceTab[] {
    switch (statut) {
      case 'EN_COURS':
        return TABS_LIVE;
      case 'TERMINE':
      case 'ARCHIVE':
        return TABS_DONE;
      default:
        return TABS_SETUP;
    }
  }

  /** Date-aware status for the header chip — CONFIGURE + future date → "À venir". */
  displayStatut(e: ExamenResponse): string {
    return statutDisplayLabel(e.statut, e.dateExamen);
  }

  isReached(current: StatutExamen, step: StatutExamen): boolean {
    return LIFECYCLE.indexOf(step) <= LIFECYCLE.indexOf(current);
  }
}
