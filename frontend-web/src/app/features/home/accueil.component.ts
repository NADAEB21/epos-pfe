import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthStore } from '../../core/auth/auth.store';
import { ExamenResponse, StatutExamen } from '../../core/api/models';
import { statutDisplayLabel } from '../../core/api/exam-status';
import { AccueilData, HomeService } from './home.service';

interface Cta {
  label: string;
  link: unknown[];
}

const LIFECYCLE: StatutExamen[] = ['BROUILLON', 'CONFIGURE', 'EN_COURS', 'TERMINE', 'ARCHIVE'];

const STATUT_LABELS: Record<StatutExamen, string> = {
  BROUILLON: 'Brouillon',
  CONFIGURE: 'Configuré',
  EN_COURS: 'En cours',
  TERMINE: 'Terminé',
  ARCHIVE: 'Archivé',
};

@Component({
  selector: 'app-accueil',
  standalone: true,
  imports: [RouterLink],
  template: `
    <header class="mb-6">
      <h1 class="text-2xl font-semibold text-gray-900">Bonjour {{ firstName() }}</h1>
      <p class="text-sm text-gray-500">{{ matiereLine() }}</p>
    </header>

    @if (loading()) {
      <div class="space-y-6 animate-pulse">
        <div class="h-44 rounded-xl bg-gray-200"></div>
        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          <div class="h-24 rounded-xl bg-gray-200"></div>
          <div class="h-24 rounded-xl bg-gray-200"></div>
          <div class="h-24 rounded-xl bg-gray-200"></div>
          <div class="h-24 rounded-xl bg-gray-200"></div>
        </div>
        <div class="h-40 rounded-xl bg-gray-200"></div>
      </div>
    } @else if (error()) {
      <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card">
        <p class="text-gray-700 mb-1">Impossible de charger l'accueil.</p>
        <p class="text-sm text-gray-500 mb-4">Verifiez votre connexion puis reessayez.</p>
        <button
          type="button"
          (click)="load()"
          class="inline-flex items-center px-4 py-2 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark transition-colors"
        >
          Reessayer
        </button>
      </div>
    } @else {
      @if (data(); as d) {
      <!-- Big card: closest exam, or empty-state CTA -->
      @if (d.closestExam; as exam) {
        <section class="rounded-xl bg-white border border-gray-200 shadow-card p-6 mb-6">
          <div class="flex items-start justify-between gap-4 flex-wrap">
            <div>
              <div class="flex items-center gap-3">
                <h2 class="text-lg font-semibold text-gray-900">{{ exam.nom }}</h2>
                <span class="text-xs font-medium px-2 py-0.5 rounded-full bg-brand-50 text-brand-dark">
                  {{ displayStatut(exam) }}
                </span>
              </div>
              <p class="text-sm text-gray-500 mt-1">
                {{ matiereLabel(d, exam.matiereId) }} &middot; {{ exam.dateExamen }}
                <span class="ml-2 font-medium text-gray-700">{{ countdown(exam) }}</span>
              </p>
            </div>
            <a
              [routerLink]="cta(exam).link"
              class="inline-flex items-center px-4 py-2 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark transition-colors"
            >
              {{ cta(exam).label }}
            </a>
          </div>

          <!-- Lifecycle bar -->
          <div class="flex items-center gap-1 mt-5">
            @for (step of lifecycle; track step) {
              <div class="flex-1 flex items-center gap-1">
                <div
                  class="h-1.5 flex-1 rounded-full"
                  [class.bg-brand]="isReached(exam.statut, step)"
                  [class.bg-gray-200]="!isReached(exam.statut, step)"
                ></div>
              </div>
            }
          </div>
          <div class="flex justify-between mt-1">
            @for (step of lifecycle; track step) {
              <span
                class="text-[10px] uppercase tracking-wide"
                [class.text-brand-dark]="isReached(exam.statut, step)"
                [class.text-gray-400]="!isReached(exam.statut, step)"
                >{{ statutLabel(step) }}</span
              >
            }
          </div>

          <!-- Readiness checklist -->
          <div class="grid grid-cols-2 sm:grid-cols-3 gap-2 mt-5">
            @for (item of d.closestExamChecklist; track item.label) {
              <div class="flex items-center gap-2 text-sm">
                <span
                  class="w-2.5 h-2.5 rounded-full shrink-0"
                  [class.bg-status-success]="item.state === 'ok'"
                  [class.bg-status-warning]="item.state === 'todo'"
                  [class.bg-gray-300]="item.state === 'unknown'"
                ></span>
                <span
                  [class.text-gray-700]="item.state !== 'unknown'"
                  [class.text-gray-400]="item.state === 'unknown'"
                  >{{ item.label }}</span
                >
              </div>
            }
          </div>
        </section>
      } @else {
        <section class="rounded-xl bg-white border border-gray-200 shadow-card p-8 mb-6 text-center">
          <h2 class="text-lg font-semibold text-gray-900 mb-1">Aucun examen a venir</h2>
          <p class="text-sm text-gray-500 mb-5">Creez un nouvel examen pour demarrer.</p>
          <div class="flex flex-wrap justify-center gap-3">
            <a
              [routerLink]="['/examens', 'nouveau']"
              class="px-4 py-2 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark transition-colors"
              >Partir de zero</a
            >
            <a
              [routerLink]="['/bibliotheque']"
              class="px-4 py-2 rounded-lg border border-gray-300 text-gray-700 text-sm font-medium hover:bg-gray-50 transition-colors"
              >Depuis un modele</a
            >
            <a
              [routerLink]="['/examens']"
              class="px-4 py-2 rounded-lg border border-gray-300 text-gray-700 text-sm font-medium hover:bg-gray-50 transition-colors"
              >Dupliquer le semestre dernier</a
            >
          </div>
        </section>
      }

      <!-- Stat tiles -->
      <section class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
        <div class="rounded-xl bg-white border border-gray-200 shadow-card p-4">
          <div class="text-2xl font-semibold text-gray-900">{{ d.upcomingCount }}</div>
          <div class="text-sm text-gray-500">examens a venir</div>
        </div>
        <div class="rounded-xl bg-white border border-gray-200 shadow-card p-4">
          <div class="text-2xl font-semibold text-gray-900">{{ d.studentCount }}</div>
          <div class="text-sm text-gray-500">etudiants (roster)</div>
        </div>
        <div class="rounded-xl bg-white border border-gray-200 shadow-card p-4">
          <div class="text-2xl font-semibold text-gray-900">{{ d.evaluateurCount }}</div>
          <div class="text-sm text-gray-500">evaluateurs</div>
        </div>
        <div class="rounded-xl bg-white border border-gray-200 shadow-card p-4">
          <div class="text-2xl font-semibold text-gray-900">{{ d.pendingLockCount }}</div>
          <div class="text-sm text-gray-500">notations a verrouiller</div>
        </div>
      </section>

      <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <!-- Actions requises -->
        <section class="rounded-xl bg-white border border-gray-200 shadow-card p-5">
          <h3 class="font-semibold text-gray-900 mb-3">Actions requises</h3>
          @if (d.actionsRequises.length === 0) {
            <p class="text-sm text-gray-400">Rien a signaler.</p>
          } @else {
            <ul class="space-y-2">
              @for (a of d.actionsRequises; track $index) {
                <li>
                  @if (a.examenId !== null) {
                    <a
                      [routerLink]="['/examens', a.examenId, a.tab]"
                      class="flex items-center gap-2 text-sm text-gray-700 hover:text-brand"
                    >
                      <span class="w-2 h-2 rounded-full bg-status-warning shrink-0"></span>
                      {{ a.label }}
                    </a>
                  } @else {
                    <span class="flex items-center gap-2 text-sm text-gray-700">
                      <span class="w-2 h-2 rounded-full bg-status-warning shrink-0"></span>
                      {{ a.label }}
                    </span>
                  }
                </li>
              }
            </ul>
          }
        </section>

        <!-- Recent exams -->
        <section class="rounded-xl bg-white border border-gray-200 shadow-card p-5">
          <h3 class="font-semibold text-gray-900 mb-3">Mes examens recents</h3>
          @if (d.recentExams.length === 0) {
            <p class="text-sm text-gray-400">Aucun examen.</p>
          } @else {
            <ul class="divide-y divide-gray-100">
              @for (e of d.recentExams; track e.id) {
                <li>
                  <a
                    [routerLink]="['/examens', e.id]"
                    class="flex items-center justify-between py-2 text-sm hover:text-brand"
                  >
                    <span class="text-gray-700">{{ e.nom }}</span>
                    <span class="text-xs text-gray-400">{{ displayStatut(e) }}</span>
                  </a>
                </li>
              }
            </ul>
          }
        </section>
      </div>
      }
    }
  `,
})
export class AccueilComponent {
  private readonly home = inject(HomeService);
  private readonly authStore = inject(AuthStore);

  readonly lifecycle = LIFECYCLE;

  readonly data = signal<AccueilData | null>(null);
  readonly loading = signal(true);
  readonly error = signal(false);

  readonly firstName = computed(() => {
    const u = this.authStore.currentUser();
    if (!u) return '';
    const local = u.email.split('@')[0].split(/[._-]/)[0];
    return local ? local[0].toUpperCase() + local.slice(1) : '';
  });

  readonly matiereLine = computed(() => {
    const ids = this.authStore.responsableMatiereIds();
    const labels = this.data()?.matiereLabels ?? {};
    if (ids.length === 0) return 'Espace de travail';
    const named = ids.map((id) => labels[id]).filter(Boolean);
    return named.length ? `Matiere : ${named.join(', ')}` : 'Espace de travail';
  });

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(false);
    this.home.loadAccueil().subscribe({
      next: (d) => {
        this.data.set(d);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  statutLabel(s: StatutExamen): string {
    return STATUT_LABELS[s];
  }

  /** Date-aware status for an exam chip — CONFIGURE + future date → "À venir". */
  displayStatut(e: ExamenResponse): string {
    return statutDisplayLabel(e.statut, e.dateExamen);
  }

  matiereLabel(d: AccueilData, matiereId: number): string {
    return d.matiereLabels[matiereId] ?? `Matiere ${matiereId}`;
  }

  isReached(current: StatutExamen, step: StatutExamen): boolean {
    return LIFECYCLE.indexOf(step) <= LIFECYCLE.indexOf(current);
  }

  countdown(exam: ExamenResponse): string {
    const target = Date.parse(exam.dateExamen);
    if (Number.isNaN(target)) return '';
    const today = Date.parse(new Date().toISOString().slice(0, 10));
    const days = Math.round((target - today) / 86_400_000);
    if (days === 0) return "Aujourd'hui";
    if (days > 0) return `J-${days}`;
    return `J+${Math.abs(days)}`;
  }

  cta(exam: ExamenResponse): Cta {
    switch (exam.statut) {
      case 'EN_COURS':
        return { label: 'Suivi en direct', link: ['/examens', exam.id, 'suivi'] };
      case 'TERMINE':
      case 'ARCHIVE':
        return { label: 'Voir les resultats', link: ['/examens', exam.id, 'resultats'] };
      default:
        return { label: 'Continuer la configuration', link: ['/examens', exam.id, 'vue-ensemble'] };
    }
  }
}
