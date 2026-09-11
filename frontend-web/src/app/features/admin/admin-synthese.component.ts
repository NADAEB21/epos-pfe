import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { AiApiService } from '../../core/api/ai-api.service';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import { SyntheseFaculte } from '../../core/api/models';
import {
  BarreConcentrationLigne,
  BarresConcentrationComponent,
} from '../../shared/graphes/barres-concentration.component';
import { fmtDate, fmtNum, fmtTaux } from '../../shared/ia/lecture-bi';

type EtatAi = 'chargement' | 'absents' | 'refus' | 'prets';

/**
 * #365 (N10) — Synthèse de la faculté (SUPER_ADMIN) : agrégats par matière,
 * AGRÉGÉ D'ABORD (ADR-0021 D5 — « un responsable de la matière 1 ne doit pas
 * gagner une vue par étudiant de la matière 2 via un écran d'analyse » ; ici
 * PERSONNE n'en gagne : le backend ne sert aucun identifiant d'étudiant, et
 * l'écran n'en affiche aucun). Chaque matière ouvre ses tendances en lecture
 * seule (ADR-0018 D5 : la faculté lit partout, ne corrige aucun barème).
 *
 * <p>Sous l'effectif minimal le backend refuse de conclure : la barre reste
 * grise et porte le refus VERBATIM (F2, contrat de refus en amont).
 */
@Component({
  selector: 'app-admin-synthese',
  standalone: true,
  imports: [RouterLink, BarresConcentrationComponent],
  templateUrl: './admin-synthese.component.html',
})
export class AdminSyntheseComponent {
  private readonly ai = inject(AiApiService);
  private readonly directory = inject(DirectoryApiService);

  readonly etat = signal<EtatAi>('chargement');
  readonly message = signal<string | null>(null);
  readonly data = signal<SyntheseFaculte | null>(null);
  readonly matiereLabels = signal<Record<number, string>>({});
  readonly catalogueEnPanne = signal(false);

  readonly lignesMatieres = computed<BarreConcentrationLigne[]>(() =>
    (this.data()?.matieres ?? []).map((m) => ({
      id: m.matiere_id,
      label: this.matiereLabel(m.matiere_id),
      valeurPct: m.taux_reussite === null ? null : m.taux_reussite * 100,
      n: m.n_etudiants,
      detail:
        m.taux_reussite === null
          ? (m.raison ?? undefined)
          : `médiane ${fmtNum(m.mediane_sur_20)} /20 · ${m.nb_examens_clos} session(s) close(s)`,
    })),
  );

  constructor() {
    this.load();
    this.directory
      .listMatieres()
      .pipe(
        catchError(() => {
          this.catalogueEnPanne.set(true);
          return of([]);
        }),
      )
      .subscribe((ms) => {
        const labels: Record<number, string> = {};
        for (const m of ms) labels[m.id] = m.libelle;
        this.matiereLabels.set(labels);
      });
  }

  load(): void {
    this.etat.set('chargement');
    this.message.set(null);
    this.ai.getSynthese().subscribe({
      next: (d) => {
        this.data.set(d);
        this.etat.set('prets');
      },
      error: (err: { status?: number; error?: { message?: string } }) => {
        const message = err?.error?.message;
        if (err?.status === 403 && message) {
          this.etat.set('refus');
          this.message.set(message);
        } else {
          this.etat.set('absents');
        }
      },
    });
  }

  matiereLabel(id: number): string {
    return this.matiereLabels()[id] ?? `Matière ${id}`;
  }

  readonly fmtDate = fmtDate;
  readonly fmtNum = fmtNum;
  readonly fmtTaux = fmtTaux;
}
