import { Component, computed, effect, inject, input } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ExamenResponse, StatutExamen } from '../../../core/api/models';
import { LIFECYCLE, statutDisplayLabel } from '../../../core/api/exam-status';
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
  templateUrl: './examen-workspace.component.html',
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

  // #185 — preparation stepper state, derived in the store (same source as the
  // Lancement pre-flight, so the two surfaces cannot drift).
  readonly isSetup = this.store.isSetup;
  readonly steps = this.store.prepSteps;
  readonly nextStep = this.store.nextStep;
  readonly nextStepSkippable = this.store.nextStepSkippable;
  readonly prepLoading = this.store.prepLoading;
  readonly prepError = this.store.prepError;

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

  reloadPrep(): void {
    this.store.reloadPrep();
  }

  /** Skip the optional Convocations step — the suggestion moves to the launch. */
  passerConvocations(): void {
    this.store.marquerConvocationsFaites();
  }
}
