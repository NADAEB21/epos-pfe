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
  templateUrl: './accueil.component.html',
})
export class AccueilComponent {
  private readonly home = inject(HomeService);
  private readonly authStore = inject(AuthStore);

  readonly lifecycle = LIFECYCLE;

  readonly data = signal<AccueilData | null>(null);
  readonly loading = signal(true);
  readonly error = signal(false);

  /** #389 (R4) — le prénom servi par /auth/me ; repli : partie locale de l'e-mail. */
  readonly firstName = computed(() => {
    const u = this.authStore.currentUser();
    if (!u) return '';
    if (u.prenom) return u.prenom;
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
