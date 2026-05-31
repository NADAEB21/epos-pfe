import { Component, effect, inject, input, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ExamenResponse, StatutExamen } from '../../core/api/models';

interface WorkspaceTab {
  label: string;
  segment: string;
}

const LIFECYCLE: StatutExamen[] = ['BROUILLON', 'CONFIGURE', 'EN_COURS', 'TERMINE', 'ARCHIVE'];

const STATUT_LABELS: Record<StatutExamen, string> = {
  BROUILLON: 'Brouillon',
  CONFIGURE: 'Configuré',
  EN_COURS: 'En cours',
  TERMINE: 'Terminé',
  ARCHIVE: 'Archivé',
};

// Status-aware tab sets (Phase B per-exam workspace contract).
const TABS_SETUP: WorkspaceTab[] = [
  { label: "Vue d'ensemble", segment: 'vue-ensemble' },
  { label: 'Stations & Grilles', segment: 'stations-grilles' },
  { label: 'Étudiants', segment: 'etudiants' },
  { label: 'Planning', segment: 'planning' },
  { label: 'Lancement', segment: 'lancement' },
];
const TABS_LIVE: WorkspaceTab[] = [
  { label: 'Suivi en direct', segment: 'suivi' },
  { label: 'Étudiants', segment: 'etudiants' },
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
            {{ statutLabel(e.statut) }}
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
  private readonly examApi = inject(ExamApiService);

  /** Bound from the :id route param via withComponentInputBinding(). */
  readonly id = input.required<string>();

  readonly lifecycle = LIFECYCLE;
  readonly exam = signal<ExamenResponse | null>(null);
  readonly loading = signal(true);
  readonly error = signal(false);

  readonly tabs = signal<WorkspaceTab[]>([]);

  constructor() {
    effect(() => {
      const examId = Number(this.id());
      if (!Number.isFinite(examId)) {
        this.error.set(true);
        this.loading.set(false);
        return;
      }
      this.loadExam(examId);
    });
  }

  private loadExam(examId: number): void {
    this.loading.set(true);
    this.error.set(false);
    this.examApi.getExamen(examId).subscribe({
      next: (e) => {
        this.exam.set(e);
        this.tabs.set(this.tabsFor(e.statut));
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
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

  statutLabel(s: StatutExamen): string {
    return STATUT_LABELS[s];
  }

  isReached(current: StatutExamen, step: StatutExamen): boolean {
    return LIFECYCLE.indexOf(step) <= LIFECYCLE.indexOf(current);
  }
}
