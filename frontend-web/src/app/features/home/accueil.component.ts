import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthStore } from '../../core/auth/auth.store';
import { AiApiService } from '../../core/api/ai-api.service';
import { ExamenResponse, StatutExamen, TendancesMatiere } from '../../core/api/models';
import { statutDisplayLabel } from '../../core/api/exam-status';
import { BarreConcentrationLigne, BarresConcentrationComponent } from '../../shared/graphes/barres-concentration.component';
import { fmtDate, fmtNum, sur20 } from '../../shared/ia/lecture-bi';
import { IconComponent } from '../../shared/ui/icon.component';
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
  imports: [RouterLink, BarresConcentrationComponent, IconComponent],
  templateUrl: './accueil.component.html',
})
export class AccueilComponent {
  private readonly home = inject(HomeService);
  private readonly authStore = inject(AuthStore);
  private readonly ai = inject(AiApiService);

  /**
   * #407 — « Ma matière d'une session à l'autre » : les dernières sessions closes
   * (taux de réussite sous le barème effectif), servies par l'analyse. Chargé À
   * PART de l'accueil (jamais dans son forkJoin critique) : le module IA absent
   * ne blanchit pas la page, la carte le dit.
   */
  readonly tendances = signal<TendancesMatiere | null>(null);
  readonly tendancesEtat = signal<'chargement' | 'absents' | 'prets'>('chargement');

  /** Les sessions closes qui ne portent pas de lecture (effectif insuffisant, couverture
   *  incomplète, sans notation) : COMPTÉES sous la carte, jamais tracées — l'accueil est
   *  un coup d'œil, l'écran Tendances les liste toutes avec leur raison. */
  readonly tendancesNonLues = computed<number>(() =>
    (this.tendances()?.examens ?? []).filter((e) => (e.lecture ?? e.origine).taux_reussite === null).length,
  );

  readonly lignesTendances = computed<BarreConcentrationLigne[]>(() => {
    const t = this.tendances();
    if (!t) return [];
    return t.examens.filter((e) => (e.lecture ?? e.origine).taux_reussite !== null).slice(-6).map((e) => {
      const l = e.lecture ?? e.origine;
      return {
        id: e.examen_id,
        label: `${fmtDate(e.date_examen)} · ${e.nom ?? `Examen ${e.examen_id}`}`,
        valeurPct: l.taux_reussite === null ? null : l.taux_reussite * 100,
        n: l.n_etudiants,
        detail: l.taux_reussite === null ? e.lectures[0]?.raison : `médiane ${fmtNum(sur20(l.mediane, l.denominateur))} /20${e.lecture_officielle === 'DELIBERE' ? ` · barème v${e.bareme_version}` : ''}`,
      };
    });
  });

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
    return named.length ? `Matière : ${named.join(', ')}` : 'Espace de travail';
  });

  constructor() {
    this.load();
    effect(
      () => {
        const id = this.authStore.responsableMatiereIds()[0];
        if (id !== undefined) untracked(() => this.loadTendances(id));
      },
      { allowSignalWrites: true },
    );
  }

  private loadTendances(matiereId: number): void {
    this.tendancesEtat.set('chargement');
    this.ai.getTendances(matiereId).subscribe({
      next: (t) => {
        this.tendances.set(t);
        this.tendancesEtat.set('prets');
      },
      error: () => this.tendancesEtat.set('absents'),
    });
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
