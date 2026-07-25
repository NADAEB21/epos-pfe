import { Component, computed, inject, input, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ExamApiService } from '../../core/api/exam-api.service';
import { StatutExamen } from '../../core/api/models';
import { ExamenWorkspaceStore } from './examen-workspace.store';

/**
 * Lancement tab — the responsable's pre-flight + launch control. Only reachable
 * while the exam is in the setup phase (BROUILLON / CONFIGURE); the parent's
 * status-aware tab list drops it once the exam is EN_COURS.
 *
 * Drives the lifecycle one legal edge at a time:
 *   BROUILLON  → "Finaliser la configuration" → CONFIGURE  (locks the setup)
 *   CONFIGURE  → "Lancer l'examen"            → EN_COURS   (day-of trigger)
 *
 * Why the readiness gate lives client-side: the backend changerStatut only
 * validates the state-machine edge — it will flip a CONFIGURE exam to EN_COURS
 * with zero évaluateurs, no grilles and an empty roster. Until a backend
 * pre-launch validation endpoint exists, this client-side gate is the only thing
 * stopping a misconfigured launch that would strand the mobile évaluateurs on
 * exam day. Blocking checks disable the action; the soft check (sujet PDF) only
 * warns.
 *
 * #185: the checklist itself (stations / roster / lots / day-gate / PDF) is
 * DERIVED IN THE STORE, shared with the workspace's preparation stepper — one
 * source, no drift. This component only renders it and drives the transition.
 */
@Component({
  selector: 'app-lancement',
  standalone: true,
  imports: [RouterLink],
  template: `
    @if (loading()) {
      <div class="space-y-6 animate-pulse">
        <div class="h-24 rounded-xl bg-gray-200"></div>
        <div class="h-40 rounded-xl bg-gray-200"></div>
      </div>
    } @else if (error()) {
      <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card">
        <p class="text-gray-700 mb-1">Impossible de charger le lancement.</p>
        <p class="text-sm text-gray-500 mb-4">Verifiez votre connexion puis reessayez.</p>
        <button
          type="button"
          (click)="reload()"
          class="inline-flex items-center px-4 py-2 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark transition-colors"
        >
          Reessayer
        </button>
      </div>
    } @else if (statut() === 'EN_COURS' || statut() === 'TERMINE' || statut() === 'ARCHIVE') {
      <!-- Defensive: the tab is normally hidden once launched. -->
      <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card">
        <p class="text-gray-800 font-medium mb-1">Examen deja lance.</p>
        <p class="text-sm text-gray-500 mb-4">Le suivi se fait desormais en direct.</p>
        <a [routerLink]="['../suivi']" class="text-sm text-brand hover:underline">Aller au suivi en direct</a>
      </div>
    } @else {
      <!-- Intro -->
      <section class="rounded-xl bg-white border border-gray-200 shadow-card p-5 mb-6">
        <h2 class="font-semibold text-gray-900 mb-1">{{ action().title }}</h2>
        <p class="text-sm text-gray-500">{{ action().intro }}</p>
      </section>

      <!-- Pre-flight checklist -->
      <section class="rounded-xl bg-white border border-gray-200 shadow-card p-5 mb-6">
        <div class="flex items-center justify-between gap-4 mb-4">
          <h3 class="font-semibold text-gray-900">Verifications avant lancement</h3>
          <span
            class="text-xs font-medium px-2 py-0.5 rounded-full text-white"
            [class.bg-status-success]="blockersRemaining() === 0"
            [class.bg-status-warning]="blockersRemaining() > 0"
          >
            {{ blockersRemaining() === 0 ? 'Pret' : blockersRemaining() + ' a corriger' }}
          </span>
        </div>
        <ul class="space-y-2.5">
          @for (c of checks(); track c.label) {
            <li class="flex items-start gap-2.5 text-sm">
              <span
                class="w-4 h-4 rounded-full shrink-0 mt-0.5 flex items-center justify-center text-[10px] font-bold text-white"
                [class.bg-status-success]="c.ok"
                [class.bg-status-danger]="!c.ok && c.blocking"
                [class.bg-status-warning]="!c.ok && !c.blocking"
              >{{ c.ok ? '✓' : '!' }}</span>
              <span>
                <span class="text-gray-700">{{ c.label }}</span>
                @if (!c.blocking) {
                  <span class="text-xs text-gray-400"> (optionnel)</span>
                }
                @if (c.hint) {
                  <span class="block text-xs" [class.text-status-danger]="!c.ok && c.blocking" [class.text-gray-400]="c.ok || !c.blocking">{{ c.hint }}</span>
                }
              </span>
            </li>
          }
        </ul>
      </section>

      <!-- Lots: répartis at CONFIGURE, generated per-lot on exam day -->
      @if (isLaunch()) {
        <section class="rounded-xl bg-white border border-gray-200 shadow-card p-5 mb-6">
          <h3 class="font-semibold text-gray-900 mb-2">Lots</h3>
          <p class="text-sm text-gray-500 mb-3">
            Les étudiants sont répartis en lots (vagues) avant le lancement, pour qu'ils connaissent
            leur horaire d'arrivée. Les rotations de chaque lot se génèrent le jour J, à l'arrivée
            de la vague, depuis l'onglet Lots.
          </p>
          <a
            [routerLink]="['../lots']"
            class="inline-flex items-center px-4 py-2 rounded-lg border border-brand text-brand text-sm font-medium hover:bg-brand-50 transition-colors"
          >
            {{ lotsCount() > 0 ? 'Voir les lots' : 'Répartir en lots' }}
          </a>
        </section>
      }

      <!-- Action -->
      <section
        class="rounded-xl border p-5"
        [class.bg-white]="!isLaunch()"
        [class.border-gray-200]="!isLaunch()"
        [class.shadow-card]="!isLaunch()"
        [class.bg-brand-50]="isLaunch()"
        [class.border-brand]="isLaunch()"
      >
        @if (blockersRemaining() > 0) {
          <p class="text-sm text-gray-600 mb-3">
            Completez les {{ blockersRemaining() }} verification(s) bloquante(s) ci-dessus avant de continuer.
          </p>
        } @else if (isLaunch()) {
          <p class="text-sm text-gray-700 mb-3">
            Tout est pret. Le lancement notifie les evaluateurs : ils pourront noter les
            etudiants depuis l'application mobile. <span class="font-medium">Cette action est irreversible.</span>
          </p>
        } @else {
          <p class="text-sm text-gray-700 mb-3">
            La configuration est complete. Vous pourrez lancer l'examen le jour J depuis cet onglet.
          </p>
        }

        @if (submitError()) {
          <p class="text-sm text-status-danger mb-3">{{ submitError() }}</p>
        }

        @if (!confirming()) {
          <button
            type="button"
            [disabled]="blockersRemaining() > 0 || submitting()"
            (click)="confirming.set(true); submitError.set(null)"
            class="inline-flex items-center px-4 py-2 rounded-lg text-sm font-medium text-white transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
            [class.bg-brand]="!isLaunch()"
            [class.bg-status-success]="isLaunch()"
          >
            {{ action().cta }}
          </button>
        } @else {
          <div class="flex flex-wrap items-center gap-3">
            <span class="text-sm font-medium text-gray-800">{{ action().confirm }}</span>
            <button
              type="button"
              [disabled]="submitting()"
              (click)="submit()"
              class="inline-flex items-center px-4 py-2 rounded-lg text-sm font-medium text-white transition-colors disabled:opacity-50"
              [class.bg-brand]="!isLaunch()"
              [class.bg-status-success]="isLaunch()"
            >
              {{ submitting() ? 'En cours…' : 'Confirmer' }}
            </button>
            <button
              type="button"
              [disabled]="submitting()"
              (click)="confirming.set(false)"
              class="text-sm text-gray-500 hover:text-gray-800 disabled:opacity-50"
            >
              Annuler
            </button>
          </div>
        }
      </section>
    }
  `,
})
export class LancementComponent {
  private readonly examApi = inject(ExamApiService);
  private readonly store = inject(ExamenWorkspaceStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  /** Inherited from the parent examens/:id route via withComponentInputBinding(). */
  readonly id = input.required<string>();

  // Everything the gate reads is store-derived (#185): exam status, the
  // pre-flight checklist and the lot count all come from the shared source
  // feeding the workspace stepper.
  readonly statut = computed<StatutExamen | null>(() => this.store.exam()?.statut ?? null);
  readonly checks = this.store.checks;
  readonly blockersRemaining = this.store.blockersRemaining;
  readonly lotsCount = this.store.lotsCount;

  readonly loading = computed(() => this.store.loading() || this.store.prepLoading());
  readonly error = computed(() => this.store.error() || this.store.prepError());

  readonly confirming = signal(false);
  readonly submitting = signal(false);
  readonly submitError = signal<string | null>(null);

  constructor() {
    // Fresh gate on tab entry — a mutation elsewhere may have been missed.
    this.store.reloadPrep();
  }

  reload(): void {
    this.store.reload();
  }

  /** True when the next edge is the irreversible CONFIGURE → EN_COURS launch. */
  readonly isLaunch = computed(() => this.statut() === 'CONFIGURE');

  readonly action = computed(() => {
    if (this.isLaunch()) {
      return {
        title: "Lancer l'examen",
        intro:
          "Demarrez l'examen le jour J. Une fois lance, les evaluateurs notent les etudiants en direct depuis l'application mobile.",
        cta: "Lancer l'examen",
        confirm: "Confirmer le lancement de l'examen ?",
      };
    }
    return {
      title: 'Finaliser la configuration',
      intro:
        'Verrouillez la configuration des stations, grilles et evaluateurs. Vous pourrez ensuite lancer l\'examen le jour J.',
      cta: 'Finaliser la configuration',
      confirm: 'Finaliser la configuration ?',
    };
  });

  // ---- transition ---------------------------------------------------------

  submit(): void {
    const examId = Number(this.id());
    const target: StatutExamen = this.isLaunch() ? 'EN_COURS' : 'CONFIGURE';
    const wasLaunch = this.isLaunch();
    this.submitting.set(true);
    this.submitError.set(null);
    this.examApi.changerStatut(examId, target).subscribe({
      next: () => {
        this.submitting.set(false);
        this.confirming.set(false);
        // Refresh the shared exam (+ prep data via the store) so the parent's
        // tabs, lifecycle bar and stepper all react. On the CONFIGURE edge the
        // roster/lots checks turn blocking simply because statut changed.
        this.store.reload();
        if (wasLaunch) {
          // Exam is live now — the Lancement tab is gone; send the responsable to suivi.
          this.router.navigate(['../suivi'], { relativeTo: this.route });
        }
      },
      error: () => {
        this.submitting.set(false);
        this.submitError.set(
          "Echec du changement de statut. Verifiez votre connexion puis reessayez.",
        );
      },
    });
  }
}
