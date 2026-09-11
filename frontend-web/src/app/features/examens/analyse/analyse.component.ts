import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, of } from 'rxjs';
import { AiApiService } from '../../../core/api/ai-api.service';
import { DirectoryApiService } from '../../../core/api/directory-api.service';
import { ExamApiService } from '../../../core/api/exam-api.service';
import {
  EvaluateursExamen,
  IndiceAi,
  IndiceCritereAi,
  IndicesExamen,
  StationSummary,
  UserResponse,
} from '../../../core/api/models';
import { LectureIndiceComponent } from '../../../shared/ia/lecture-indice.component';
import { Indice, lireIndice } from '../../../shared/ia/lecture-indices';
import { IconComponent } from '../../../shared/ui/icon.component';
import { ExamenWorkspaceStore } from '../workspace/examen-workspace.store';

type EtatAi = 'chargement' | 'absents' | 'refus' | 'prets';

/** Une station telle que l'onglet la lit : ses trois indices de grille et ses critères. */
export interface StationAnalyse {
  stationId: number;
  nom: string;
  ordre: number;
  alpha: Indice | null;
  concentration: Indice | null;
  criteres: { libelle: string; type: string; difficulte: Indice; discrimination: Indice }[];
  /** Combien de lectures de cette station signalent un problème (défauts « allumés »). */
  alertes: number;
}

export interface EvaluateurAnalyse {
  nom: string;
  n: number;
  severite: Indice;
}

/**
 * #407 — l'onglet « Analyse » d'un examen clos : ce que l'analyse a lu dans les
 * notes, en français, station par station — puis les évaluateurs.
 *
 * <p>Rien n'est calculé ici : les indices viennent de `/indices` (cache ai_db)
 * et `/evaluateurs`, chaque valeur avec son statut et son refus ; les phrases
 * viennent de `lecture-indices.ts` (F4), rendues par `app-lecture-indice`. Les
 * deux appels sont indépendants : l'un peut manquer sans emporter l'autre.
 * Les noms d'évaluateurs viennent de l'annuaire (le module ne sert que des
 * ids, ADR-0021 D2 : jamais un palmarès).
 */
@Component({
  selector: 'app-analyse',
  standalone: true,
  imports: [RouterLink, LectureIndiceComponent, IconComponent],
  templateUrl: './analyse.component.html',
})
export class AnalyseComponent {
  private readonly ai = inject(AiApiService);
  private readonly examApi = inject(ExamApiService);
  private readonly directory = inject(DirectoryApiService);
  private readonly store = inject(ExamenWorkspaceStore);

  readonly id = input.required<string>();
  readonly examenIdNum = computed(() => Number(this.id()));
  readonly exam = this.store.exam;

  readonly indices = signal<IndicesExamen | null>(null);
  readonly indicesEtat = signal<EtatAi>('chargement');
  readonly indicesMessage = signal<string | null>(null);

  readonly evaluateurs = signal<EvaluateursExamen | null>(null);
  readonly evaluateursEtat = signal<EtatAi>('chargement');

  readonly stations = signal<StationSummary[]>([]);
  readonly annuaire = signal<Map<number, string>>(new Map());

  /** L'examen est-il une cohorte de référence à défauts plantés (la démo) ? Lu dans le nom, jamais deviné ailleurs. */
  readonly cohorteDeReference = computed(() => /IA-F1/i.test(this.exam()?.nom ?? ''));

  readonly estClos = computed(() => {
    const s = this.exam()?.statut;
    return s === 'TERMINE' || s === 'ARCHIVE';
  });

  readonly parStation = computed<StationAnalyse[]>(() => {
    const ind = this.indices();
    if (!ind) return [];
    const noms = new Map(this.stations().map((s) => [s.id, { nom: s.nom ?? `Station ${s.id}`, ordre: s.ordre ?? 0 }]));
    const ids = new Set<number>();
    for (const g of ind.par_grille) ids.add(g.station_id);
    for (const s of ind.par_station) ids.add(s.station_id);
    for (const c of ind.par_critere) ids.add(c.station_id);
    return [...ids]
      .map((sid) => {
        const alpha = ind.par_grille.find((g) => g.station_id === sid)?.alpha_cronbach ?? null;
        const conc = ind.par_station.find((s) => s.station_id === sid)?.concentration_echec ?? null;
        const criteres = ind.par_critere
          .filter((c) => c.station_id === sid)
          .map((c: IndiceCritereAi) => ({
            libelle: c.libelle,
            type: c.type,
            difficulte: this.asIndice(c.difficulte),
            discrimination: this.asIndice(c.discrimination),
          }));
        const alertes = criteres.filter((c) => this.alerte(c.difficulte) || this.alerte(c.discrimination)).length
          + (this.alerte(alpha) ? 1 : 0) + (this.alerte(conc) ? 1 : 0);
        return {
          stationId: sid,
          nom: noms.get(sid)?.nom ?? `Station ${sid}`,
          ordre: noms.get(sid)?.ordre ?? 0,
          alpha: alpha ? this.asIndice(alpha) : null,
          concentration: conc ? this.asIndice(conc) : null,
          criteres,
          alertes,
        };
      })
      .sort((a, b) => a.ordre - b.ordre || a.stationId - b.stationId);
  });

  readonly evaluateursParStation = computed<{ stationId: number; nom: string; evaluateurs: EvaluateurAnalyse[] }[]>(() => {
    const ev = this.evaluateurs();
    if (!ev) return [];
    const noms = new Map(this.stations().map((s) => [s.id, s.nom ?? `Station ${s.id}`]));
    const annuaire = this.annuaire();
    return ev.par_station.map((st) => ({
      stationId: st.station_id,
      nom: noms.get(st.station_id) ?? `Station ${st.station_id}`,
      evaluateurs: st.evaluateurs.map((e) => ({
        nom: annuaire.get(e.evaluateur_id) ?? `Évaluateur n° ${e.evaluateur_id}`,
        n: e.n,
        severite: this.asIndice(e.severite),
      })),
    }));
  });

  readonly nbAlertes = computed(() => this.parStation().reduce((s, st) => s + st.alertes, 0));

  constructor() {
    effect(
      () => {
        const examId = Number(this.id());
        if (!Number.isFinite(examId)) return;
        this.load(examId);
      },
      { allowSignalWrites: true },
    );
  }

  reload(): void {
    this.load(this.examenIdNum());
  }

  private load(examId: number): void {
    this.loadIndices(examId);
    this.loadEvaluateurs(examId);
    forkJoin({
      stations: this.examApi.listStations(examId).pipe(catchError(() => of([] as StationSummary[]))),
      users: this.directory.listUsers('EVALUATEUR').pipe(catchError(() => of([] as UserResponse[]))),
    }).subscribe(({ stations, users }) => {
      this.stations.set(stations);
      this.annuaire.set(new Map(users.map((u) => [u.id, `${u.prenom ?? ''} ${u.nom ?? ''}`.trim() || u.email])));
    });
  }

  /** Chargé à part : le refus (409 non clos, 403) s'affiche verbatim ; une panne se dit. */
  private loadIndices(examId: number): void {
    this.indicesEtat.set('chargement');
    this.indicesMessage.set(null);
    this.ai.getIndices(examId).subscribe({
      next: (d) => {
        this.indices.set(d);
        this.indicesEtat.set('prets');
      },
      error: (err: { status?: number; error?: { message?: string } }) => {
        const message = err?.error?.message;
        if ((err?.status === 409 || err?.status === 403) && message) {
          this.indicesEtat.set('refus');
          this.indicesMessage.set(message);
        } else {
          this.indicesEtat.set('absents');
        }
      },
    });
  }

  private loadEvaluateurs(examId: number): void {
    this.evaluateursEtat.set('chargement');
    this.ai.getEvaluateurs(examId).subscribe({
      next: (d) => {
        this.evaluateurs.set(d);
        this.evaluateursEtat.set('prets');
      },
      error: () => this.evaluateursEtat.set('absents'),
    });
  }

  /** La phrase d'un indice pour une cellule compacte ; un code inconnu dégrade sans casser. */
  phrase(i: Indice): { texte: string; refus: boolean } {
    try {
      return { texte: lireIndice(i), refus: i.statut === 'NON_CONCLUANT' };
    } catch {
      return { texte: 'lecture indisponible — indice non reconnu par cette version du site', refus: true };
    }
  }

  private asIndice(i: IndiceAi): Indice {
    return i as Indice;
  }

  /** Une lecture « à regarder » : concluante ET hors des bandes saines (les bandes vivent dans lecture-indices.ts). */
  private alerte(i: IndiceAi | null): boolean {
    if (!i || i.statut !== 'CONCLUANT' || i.valeur == null) return false;
    switch (i.code) {
      case 'DIFFICULTE':
        return i.valeur < 0.2 || i.valeur > 0.85;
      case 'DISCRIMINATION':
        return i.valeur < 0.1;
      case 'ALPHA_CRONBACH':
        return i.valeur < 0.5;
      case 'CONCENTRATION_ECHEC': {
        const p = i.details?.['p_value'];
        return typeof p === 'number' && p < 0.05;
      }
      default:
        return false;
    }
  }
}
