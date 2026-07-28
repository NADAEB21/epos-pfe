import { Component, computed, inject, input, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ExamApiService } from '../../../core/api/exam-api.service';
import { StatutExamen } from '../../../core/api/models';
import { ExamenWorkspaceStore } from '../workspace/examen-workspace.store';

/**
 * Lancement tab — the responsable's pre-flight + launch control. Only reachable
 * while the exam is in the setup phase (BROUILLON / CONFIGURE); the parent's
 * status-aware tab list drops it once the exam is EN_COURS.
 *
 * ONE action: « Lancer l'examen » (#185 rework — « Finaliser la configuration »
 * confused the person this screen is for and no longer exists as a user act;
 * the BROUILLON→CONFIGURE edge happens silently in the Lots tab, or is chained
 * defensively inside the launch click, see submit()).
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
 * source, no drift. This component only renders it and drives the launch.
 */
@Component({
  selector: 'app-lancement',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './lancement.component.html',
})
export class LancementComponent {
  private readonly examApi = inject(ExamApiService);
  private readonly store = inject(ExamenWorkspaceStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  /** Inherited from the parent examens/:id route via withComponentInputBinding(). */
  readonly id = input.required<string>();

  // Everything the gate reads is store-derived (#185): exam status and the
  // pre-flight checklist come from the shared source feeding the workspace
  // stepper.
  readonly statut = computed<StatutExamen | null>(() => this.store.exam()?.statut ?? null);
  readonly checks = this.store.checks;
  readonly blockersRemaining = this.store.blockersRemaining;

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

  // ---- transition ---------------------------------------------------------

  /**
   * The ONLY action this tab offers is launching (#185 rework, Nada 2026-07-25):
   * « Finaliser la configuration » no longer exists as a user act anywhere. The
   * state machine still requires BROUILLON → CONFIGURE → EN_COURS, so when the
   * exam somehow reaches launch day still BROUILLON (lots normally finalise it
   * silently), both edges are chained inside this one click.
   */
  submit(): void {
    const examId = Number(this.id());
    this.submitting.set(true);
    this.submitError.set(null);

    const lancer = () =>
      this.examApi.changerStatut(examId, 'EN_COURS').subscribe({
        next: () => {
          this.submitting.set(false);
          this.confirming.set(false);
          // Refresh the shared exam (+ prep data) so the parent's tabs,
          // lifecycle bar and stepper all react, then hand over to the live board.
          this.store.reload();
          this.router.navigate(['../suivi'], { relativeTo: this.route });
        },
        error: (err) => this.echec(err),
      });

    if (this.statut() === 'BROUILLON') {
      this.examApi.changerStatut(examId, 'CONFIGURE').subscribe({
        next: () => lancer(),
        error: (err) => this.echec(err),
      });
      return;
    }
    lancer();
  }

  /**
   * Backend refusals (#265 évaluateurs déjà engagés, pause, transition…) are
   * explanatory and name what to do — show them verbatim (Robustesse), and only
   * fall back to the generic network line when there is no message.
   */
  private echec(err: { error?: { message?: unknown } }): void {
    this.submitting.set(false);
    // Refresh the pre-flight: the refusal may reflect a state change (e.g. a
    // conflicting exam launched since the page loaded).
    this.store.reloadPrep();
    this.submitError.set(
      typeof err?.error?.message === 'string' && err.error.message
        ? err.error.message
        : 'Echec du lancement. Verifiez votre connexion puis reessayez.',
    );
  }
}
