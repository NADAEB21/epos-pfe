import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { AiApiService } from '../../core/api/ai-api.service';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ExamenTendanceAi, StationSummary, TendancesMatiere } from '../../core/api/models';
import { AuthStore } from '../../core/auth/auth.store';
import {
  BarreConcentrationLigne,
  BarresConcentrationComponent,
} from '../../shared/graphes/barres-concentration.component';
import { HistogrammeStationComponent } from '../../shared/graphes/histogramme-station.component';
import { fmtDate, fmtNum, fmtTaux, lectureBiSure, sur20 } from '../../shared/ia/lecture-bi';

/** Une lecture rendue : libellé (gabarit) + raison backend VERBATIM. */
export interface LectureRendue {
  libelle: string;
  raison: string;
  degrade: boolean;
}

type EtatAi = 'chargement' | 'absents' | 'refus' | 'prets';

/**
 * #365 (N10) — Tendances d'une matière : les sessions CLOSES dans l'ordre des
 * dates (taux de réussite, médiane /20), puis la session choisie (distribution
 * des totaux, échec par station, barème délibéré éventuel). Première consommation
 * en production des composants F2 (#356).
 *
 * <p>Périmètre : le responsable voit SA matière (la première de son périmètre ;
 * un sélecteur apparaît s'il en a plusieurs) ; le SUPER_ADMIN y accède en
 * lecture depuis la synthèse (`/admin/tendances/:matiereId`, ADR-0018 D5) —
 * c'est ai-service qui tient le périmètre (403 nominatif), l'écran le montre
 * tel quel. Le module ne lit que les examens terminés/archivés (ADR-0029 D2).
 *
 * <p>Dégradation (ADR-0029 D7) : module IA injoignable → bandeau ambre nominatif,
 * jamais un écran vide ni des zéros ; un refus (403) s'affiche VERBATIM ; une
 * lecture au code inconnu est dite « indisponible » sans casser l'écran.
 * Aucune arithmétique ici : les taux, médianes et classes viennent du backend
 * (F2 règle : les composants de graphe ne recalculent jamais).
 */
@Component({
  selector: 'app-tendances',
  standalone: true,
  imports: [RouterLink, HistogrammeStationComponent, BarresConcentrationComponent],
  templateUrl: './tendances.component.html',
})
export class TendancesComponent {
  private readonly ai = inject(AiApiService);
  private readonly directory = inject(DirectoryApiService);
  private readonly examApi = inject(ExamApiService);
  private readonly authStore = inject(AuthStore);

  /** Renseigné par la route admin `/admin/tendances/:matiereId` ; absent côté responsable. */
  readonly matiereId = input<string>();

  readonly estAdmin = computed(() => this.matiereId() !== undefined);

  /** Les matières lisibles depuis cet écran : celle de la route (admin) ou le périmètre du responsable. */
  readonly scopeIds = computed<number[]>(() => {
    const fromRoute = this.matiereId();
    if (fromRoute !== undefined) {
      const id = Number(fromRoute);
      return Number.isFinite(id) && id > 0 ? [id] : [];
    }
    return this.authStore.responsableMatiereIds();
  });

  readonly selectedMatiere = signal<number | null>(null);
  readonly matiereLabels = signal<Record<number, string>>({});
  readonly catalogueEnPanne = signal(false);

  readonly etat = signal<EtatAi>('chargement');
  readonly message = signal<string | null>(null);
  readonly data = signal<TendancesMatiere | null>(null);
  readonly selectedExamenId = signal<number | null>(null);
  readonly stationLabels = signal<Record<number, string>>({});

  readonly matiereLabel = computed(() => {
    const id = this.selectedMatiere();
    return id === null ? 'matière' : this.labelPour(id);
  });

  labelPour(id: number): string {
    const label: string | undefined = this.matiereLabels()[id];
    return label ?? `Matière ${id}`;
  }

  readonly selected = computed<ExamenTendanceAi | null>(() => {
    const d = this.data();
    const id = this.selectedExamenId();
    if (!d) return null;
    return d.examens.find((e) => e.examen_id === id) ?? null;
  });

  /** Les sessions closes, dans l'ordre des dates (celui du backend). */
  readonly lignesSessions = computed<BarreConcentrationLigne[]>(() =>
    (this.data()?.examens ?? []).map((e) => {
      const bloquee = e.lectures[0];
      const med = sur20(e.origine.mediane, e.origine.denominateur);
      return {
        id: e.examen_id,
        label: `${fmtDate(e.date_examen)} · ${e.nom ?? `Examen ${e.examen_id}`}`,
        valeurPct: e.origine.taux_reussite === null ? null : e.origine.taux_reussite * 100,
        n: e.origine.n_etudiants,
        detail:
          e.origine.taux_reussite === null
            ? bloquee?.raison
            : `médiane ${fmtNum(med)} /20${e.bareme_version !== null ? ` · barème v${e.bareme_version}` : ''}`,
      };
    }),
  );

  readonly lignesStations = computed<BarreConcentrationLigne[]>(() => {
    const e = this.selected();
    if (!e) return [];
    const labels = this.stationLabels();
    return e.par_station.map((s) => ({
      id: s.station_id,
      label: labels[s.station_id] ?? `Station ${s.station_id}`,
      valeurPct: s.taux_echec * 100,
      n: s.n,
      detail: `médiane ${fmtNum(s.mediane)} / ${fmtNum(s.note_max)} · ${s.echecs} échec(s)`,
    }));
  });

  /** Les sessions écartées, COMPTÉES et dites — jamais tues (#269). Vide → rien à dire. */
  readonly nonLuesTexte = computed<string>(() => {
    const x = this.data()?.exclusions;
    if (!x) return '';
    const parts: string[] = [];
    if (x.non_clos > 0) parts.push(`${x.non_clos} session(s) non close(s)`);
    if (x.sans_snapshot > 0) parts.push(`${x.sans_snapshot} sans snapshot scoring`);
    if (x.hors_snapshot > 0) parts.push(`${x.hors_snapshot} dont le snapshot pointe une autre matière`);
    return parts.join(' · ');
  });

  readonly lecturesMatiere = computed<LectureRendue[]>(() =>
    (this.data()?.lectures ?? []).map((l) => ({ ...lectureBiSure(l.code), raison: l.raison })),
  );

  readonly lecturesSelection = computed<LectureRendue[]>(() =>
    (this.selected()?.lectures ?? []).map((l) => ({ ...lectureBiSure(l.code), raison: l.raison })),
  );

  constructor() {
    // La matière initiale : la route (admin) ou la première du périmètre.
    effect(
      () => {
        const ids = this.scopeIds();
        if (untracked(this.selectedMatiere) === null && ids.length > 0) {
          this.selectedMatiere.set(ids[0]);
        }
      },
      { allowSignalWrites: true },
    );
    effect(
      () => {
        const id = this.selectedMatiere();
        if (id !== null) this.load(id);
      },
      { allowSignalWrites: true },
    );
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

  onMatiereChange(value: string): void {
    const id = Number(value);
    if (Number.isFinite(id) && id > 0) this.selectedMatiere.set(id);
  }

  reload(): void {
    const id = this.selectedMatiere();
    if (id !== null) this.load(id);
  }

  selectExamen(examenId: number): void {
    this.selectedExamenId.set(examenId);
    this.loadStations(examenId);
  }

  /** AUCUNE lecture de signal ici (leçon : un garde-signal dans une méthode appelée par un effect boucle). */
  private load(matiereId: number): void {
    this.etat.set('chargement');
    this.message.set(null);
    this.data.set(null);
    this.selectedExamenId.set(null);
    this.ai.getTendances(matiereId).subscribe({
      next: (d) => {
        this.data.set(d);
        this.etat.set('prets');
        const derniere = d.examens[d.examens.length - 1];
        if (derniere) this.selectExamen(derniere.examen_id);
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

  /** Les noms de station vivent dans exam-service : résolus à part, repli « Station n ». */
  private loadStations(examenId: number): void {
    this.stationLabels.set({});
    this.examApi
      .listStations(examenId)
      .pipe(catchError(() => of([] as StationSummary[])))
      .subscribe((stations) => {
        if (this.selectedExamenId() !== examenId) return;
        const labels: Record<number, string> = {};
        for (const s of stations) if (s.nom) labels[s.id] = s.nom;
        this.stationLabels.set(labels);
      });
  }

  // Helpers de gabarit (purs, testés dans lecture-bi.spec.ts).
  readonly fmtDate = fmtDate;
  readonly fmtNum = fmtNum;
  readonly fmtTaux = fmtTaux;
  readonly sur20 = sur20;
}
