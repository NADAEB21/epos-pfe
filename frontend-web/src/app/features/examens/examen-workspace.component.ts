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

      <!-- #185 — guided preparation stepper (setup phase only). Every step is a
           plain link (Contrôle: navigate back and forth freely); done/current
           are guidance, never gates. -->
      @if (isSetup()) {
        <section class="rounded-xl bg-white border border-gray-200 shadow-card px-5 py-4 mb-5">
          <div class="flex flex-wrap items-center justify-between gap-2">
            <h2 class="text-sm font-semibold text-gray-900">Préparation de l'examen</h2>
            @if (prepLoading()) {
              <span class="text-xs text-gray-400">Vérification…</span>
            } @else {
              @if (nextStep(); as n) {
                <span class="flex items-center gap-3">
                  <a [routerLink]="[n.segment]" class="text-xs font-medium text-brand hover:underline">
                    Prochaine étape : {{ n.label }}@if (n.optional) { (optionnelle)} &rarr;
                  </a>
                  @if (nextStepSkippable()) {
                    <button
                      type="button"
                      (click)="passerConvocations()"
                      class="text-xs text-gray-500 hover:text-gray-800 underline"
                    >
                      Passer au lancement
                    </button>
                  }
                </span>
              } @else {
                <span class="text-xs font-medium text-status-success">Préparation complète</span>
              }
            }
          </div>

          @if (!prepLoading()) {
            <ol class="flex flex-wrap items-center gap-y-2 mt-3">
              @for (s of steps(); track s.key; let i = $index; let last = $last) {
                <li class="flex items-center">
                  <a
                    [routerLink]="[s.segment]"
                    [title]="s.hint"
                    class="flex items-center gap-1.5 rounded-lg px-1.5 py-1 hover:bg-gray-50"
                  >
                    <span
                      class="w-5 h-5 rounded-full shrink-0 flex items-center justify-center text-[10px] font-bold"
                      [class.bg-status-success]="s.done"
                      [class.text-white]="s.done || s.current"
                      [class.bg-brand]="!s.done && s.current"
                      [class.bg-gray-200]="!s.done && !s.current"
                      [class.text-gray-500]="!s.done && !s.current"
                    >{{ s.done ? '✓' : i + 1 }}</span>
                    <span
                      class="text-xs whitespace-nowrap"
                      [class.text-brand]="s.current"
                      [class.font-semibold]="s.current"
                      [class.text-gray-700]="s.done && !s.current"
                      [class.text-gray-500]="!s.done && !s.current"
                    >{{ s.label }}@if (s.optional) {<span class="text-gray-400 font-normal"> (optionnelle)</span>}</span>
                  </a>
                  @if (!last) {
                    <span class="w-3 h-px bg-gray-200 mx-0.5 shrink-0"></span>
                  }
                </li>
              }
            </ol>
            @if (nextStep(); as n) {
              <p class="text-xs text-gray-500 mt-2">{{ n.hint }}</p>
            }
            @if (prepError()) {
              <p class="text-xs text-status-danger mt-2">
                Impossible de vérifier l'état de préparation.
                <button type="button" (click)="reloadPrep()" class="underline hover:text-status-danger">Réessayer</button>
              </p>
            }
          }
        </section>
      }

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
