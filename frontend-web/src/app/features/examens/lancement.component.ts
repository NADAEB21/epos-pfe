import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ScoringApiService } from '../../core/api/scoring-api.service';
import { StationSummary, StatutExamen } from '../../core/api/models';
import { ExamenWorkspaceStore } from './examen-workspace.store';

interface PreflightCheck {
  label: string;
  ok: boolean;
  /** A blocking check must be green before the lifecycle action is allowed. */
  blocking: boolean;
  hint?: string;
}

/**
 * Lancement tab — the responsable's pre-flight + launch control. Only reachable
 * while the exam is in the setup phase (BROUILLON / CONFIGURE); the parent's
 * status-aware tab list drops it once the exam is EN_COURS.
 *
 * Drives the lifecycle one legal edge at a time:
 *   BROUILLON  → "Finaliser la configuration" → CONFIGURE  (locks the setup)
 *   CONFIGURE  → "Lancer l'examen"            → EN_COURS   (day-of trigger)
 *
 * Why the readiness gate lives here, not on the server: the backend
 * changerStatut only validates the state-machine edge — it will flip a CONFIGURE
 * exam to EN_COURS with zero évaluateurs, no grilles and an empty roster. Until a
 * backend pre-launch validation endpoint exists, this client-side gate is the
 * only thing stopping a misconfigured launch that would strand the mobile
 * évaluateurs on exam day. Blocking checks disable the action; the soft check
 * (sujet PDF) only warns.
 *
 * Reads exam status from the route-scoped store so the launch reactively updates
 * the parent's tabs + lifecycle bar via store.reload(); stations + roster are
 * fetched here since they're specific to this gate.
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
  private readonly scoring = inject(ScoringApiService);
  private readonly store = inject(ExamenWorkspaceStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  /** Inherited from the parent examens/:id route via withComponentInputBinding(). */
  readonly id = input.required<string>();

  // Exam status comes from the shared store (the parent already loaded it);
  // stations + roster are this gate's own concern.
  readonly statut = computed<StatutExamen | null>(() => this.store.exam()?.statut ?? null);
  private readonly hasPdf = computed(() => this.store.exam()?.hasPdfSujet ?? false);

  private readonly stations = signal<StationSummary[]>([]);
  private readonly rosterCount = signal(0);
  readonly lotsCount = signal(0);
  private readonly localLoading = signal(true);
  private readonly localError = signal(false);

  readonly loading = computed(() => this.store.loading() || this.localLoading());
  readonly error = computed(() => this.store.error() || this.localError());

  readonly confirming = signal(false);
  readonly submitting = signal(false);
  readonly submitError = signal<string | null>(null);

  constructor() {
    effect(() => {
      const examId = Number(this.id());
      if (!Number.isFinite(examId)) {
        this.localError.set(true);
        this.localLoading.set(false);
        return;
      }
      this.load(examId);
    }, { allowSignalWrites: true });
  }

  reload(): void {
    this.store.reload();
    this.load(Number(this.id()));
  }

  private load(examId: number): void {
    this.localLoading.set(true);
    this.localError.set(false);
    this.confirming.set(false);
    forkJoin({
      stations: this.examApi.listStations(examId),
      participations: this.scoring.listParticipations(examId),
      lots: this.scoring.listLots(examId),
    }).subscribe({
      next: ({ stations, participations, lots }) => {
        this.stations.set(stations);
        this.rosterCount.set(participations.length);
        this.lotsCount.set(lots.length);
        this.localLoading.set(false);
      },
      error: () => {
        this.localError.set(true);
        this.localLoading.set(false);
      },
    });
  }

  // ---- pre-flight ---------------------------------------------------------

  private readonly sansEvaluateur = computed(
    () => this.stations().filter((s) => (s.evaluateurIds?.length ?? 0) === 0).length,
  );
  private readonly sansGrille = computed(
    () => this.stations().filter((s) => !s.hasGrille).length,
  );

  /** Roster is a hard requirement only for the launch edge, not for finalising. */
  private readonly rosterBlocks = computed(() => this.statut() === 'CONFIGURE');

  readonly checks = computed<PreflightCheck[]>(() => {
    const n = this.stations().length;
    const hasStations = n > 0;
    return [
      {
        label: 'Stations definies',
        ok: hasStations,
        blocking: true,
        hint: hasStations ? `${n} station(s)` : 'Aucune station definie',
      },
      {
        label: 'Un evaluateur par station',
        ok: hasStations && this.sansEvaluateur() === 0,
        blocking: true,
        hint:
          hasStations && this.sansEvaluateur() > 0
            ? `${this.sansEvaluateur()} station(s) sans evaluateur`
            : undefined,
      },
      {
        label: 'Une grille par station',
        ok: hasStations && this.sansGrille() === 0,
        blocking: true,
        hint:
          hasStations && this.sansGrille() > 0
            ? `${this.sansGrille()} station(s) sans grille`
            : undefined,
      },
      {
        label: 'Etudiants inscrits',
        ok: this.rosterCount() > 0,
        blocking: this.rosterBlocks(),
        hint:
          this.rosterCount() > 0
            ? `${this.rosterCount()} etudiant(s)`
            : this.rosterBlocks()
              ? 'Aucun etudiant inscrit — requis pour lancer'
              : 'A inscrire avant le jour J',
      },
      {
        // The pre-flight that replaces #130's "rotations generated": rotations
        // are now built per-lot on exam day, so the launch gate is that the
        // waves are partitioned, not that the circuit exists.
        label: 'Lots repartis',
        ok: this.lotsCount() > 0,
        blocking: this.rosterBlocks(),
        hint:
          this.lotsCount() > 0
            ? `${this.lotsCount()} lot(s)`
            : this.rosterBlocks()
              ? 'Repartissez les etudiants en lots (onglet Lots) — requis pour lancer'
              : 'A repartir avant le jour J',
      },
      {
        label: 'Sujet PDF importe',
        ok: this.hasPdf(),
        blocking: false,
      },
    ];
  });

  readonly blockersRemaining = computed(
    () => this.checks().filter((c) => c.blocking && !c.ok).length,
  );

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
        // Refresh the shared exam so the parent's tabs + lifecycle bar react.
        this.store.reload();
        if (wasLaunch) {
          // Exam is live now — the Lancement tab is gone; send the responsable to suivi.
          this.router.navigate(['../suivi'], { relativeTo: this.route });
        } else {
          // Now CONFIGURE — refresh local gate (roster becomes blocking).
          this.load(examId);
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
