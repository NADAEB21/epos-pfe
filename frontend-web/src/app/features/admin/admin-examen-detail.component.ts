import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ScoringApiService } from '../../core/api/scoring-api.service';
import { BaremeDeliberation, ExamenResponse, ExamenResult, StationSummary } from '../../core/api/models';
import { statutDisplayLabel } from '../../core/api/exam-status';
import { fmtNum, libelleOperation, sur20 } from '../../shared/ia/lecture-deliberation';

/** A section that either loaded, or says why it did not — never a blank. */
type Etat<T> = { statut: 'ok'; valeur: T } | { statut: 'indisponible' } | { statut: 'chargement' };

/**
 * Le résumé des résultats consolidés : effectif, puis les totaux EFFECTIFS lus
 * avec leur dénominateur — jamais un nombre nu. `denominateur` est le
 * dénominateur commun quand tous les étudiants en partagent un, sinon null
 * (ADR-0030 D4 révisé : le dénominateur effectif est PAR étudiant) ; les
 * lectures /20 sont alors la moyenne / le min / le max des /20 individuels.
 */
export interface ResumeResultats {
  n: number;
  moyenne: number;
  min: number;
  max: number;
  denominateur: number | null;
  /** Nombre d'étudiants dont le dénominateur est servi (les seuls lisibles /20). */
  n20: number;
  moyenne20: number | null;
  min20: number | null;
  max20: number | null;
}

/**
 * #390 — Détail d'un examen en LECTURE SEULE pour le SUPER_ADMIN.
 *
 * <p>ADR-0018 D5 : lire partout, n'écrire rien de pédagogique. Cet écran ne
 * porte AUCUN contrôle d'écriture (pas de lancement, pas de réajustement, pas
 * de barème de délibération) et ne renvoie jamais vers le workspace du
 * responsable. Quatre lectures indépendantes — définition (exam-service),
 * stations (exam-service), résultats consolidés (scoring), décision de
 * délibération (scoring) — chacune dégrade séparément et le DIT : une panne
 * de scoring ne cache pas la définition.
 * Les endpoints autorisent déjà le SUPER_ADMIN (`MatiereAccessChecker.canAccess`,
 * `matiereAccessGuard`, `BaremeDeliberationController.historique`) : zéro
 * changement backend.
 *
 * <p>Lecture des totaux (ADR-0030 D4 révisé, #401) : quand scoring sert une
 * version de barème, le total délibéré EST le résultat ; le total brut reste
 * la trace. Un total ne s'affiche jamais sans son dénominateur ni son
 * équivalent /20 : « 22,9 » tout court se lit comme une note sur 20 alors
 * qu'il s'agissait de 22,9 / 40. Et la décision qui a produit ce dénominateur
 * (station exclue, critère retiré, repondération) est dite en toutes lettres,
 * avec son motif, son auteur et sa date — l'administration voit CE QUI a été
 * décidé, sans pouvoir le modifier.
 */
@Component({
  selector: 'app-admin-examen-detail',
  standalone: true,
  imports: [RouterLink, DecimalPipe],
  templateUrl: './admin-examen-detail.component.html',
})
export class AdminExamenDetailComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly examApi = inject(ExamApiService);
  private readonly scoringApi = inject(ScoringApiService);
  private readonly directoryApi = inject(DirectoryApiService);

  readonly examenId = Number(this.route.snapshot.paramMap.get('id'));

  readonly examen = signal<Etat<ExamenResponse>>({ statut: 'chargement' });
  readonly stations = signal<Etat<StationSummary[]>>({ statut: 'chargement' });
  readonly resultats = signal<Etat<ExamenResult[]>>({ statut: 'chargement' });
  readonly baremes = signal<Etat<BaremeDeliberation[]>>({ statut: 'chargement' });
  readonly matiereLabels = signal<Record<number, string>>({});

  readonly titre = computed(() => {
    const e = this.examen();
    return e.statut === 'ok' ? e.valeur.nom : `Examen ${this.examenId}`;
  });

  /**
   * #401 (ADR-0030 D4 révisé) — la version de barème servie par scoring pour
   * cet examen, ou null : quand elle existe, le total EFFECTIF est le total
   * délibéré (le résultat), le total brut reste la trace.
   */
  readonly baremeVersion = computed<number | null>(() => {
    const r = this.resultats();
    if (r.statut !== 'ok') return null;
    const servi = r.valeur.some((x) => x.baremeVersion != null && x.totalDelibere != null);
    return servi ? (r.valeur.find((x) => x.baremeVersion != null)?.baremeVersion ?? null) : null;
  });

  /** Le total qui fait le résultat : délibéré quand une version est servie, brut sinon. */
  totalEffectif(x: ExamenResult): number {
    return this.baremeVersion() != null && x.totalDelibere != null ? x.totalDelibere : x.totalScore;
  }

  /** Le dénominateur qui va avec `totalEffectif` — null quand scoring ne le sert pas (pré-V19). */
  denominateurEffectif(x: ExamenResult): number | null {
    const d = this.baremeVersion() != null ? x.denominateurDelibere : x.denominateurOriginal;
    return d != null && d > 0 ? d : null;
  }

  /** L'équivalent /20 du total effectif, ou null sans dénominateur servi. */
  note20(x: ExamenResult): number | null {
    const d = this.denominateurEffectif(x);
    return d == null ? null : (this.totalEffectif(x) / d) * 20;
  }

  /** « 29,5 / 40 (≈ 14,8 /20) » — les deux lectures honnêtes ; le total nu si le dénominateur manque. */
  totalLisible(x: ExamenResult): string {
    const d = this.denominateurEffectif(x);
    return d == null ? fmtNum(this.totalEffectif(x), 2) : sur20(this.totalEffectif(x), d);
  }

  /** Lignes triées sur le total EFFECTIF (scoring les sert triées sur le brut). */
  readonly lignes = computed<ExamenResult[]>(() => {
    const r = this.resultats();
    if (r.statut !== 'ok') return [];
    return [...r.valeur].sort((a, b) => this.totalEffectif(b) - this.totalEffectif(a));
  });

  /** Résumé honnête : effectif, totaux EFFECTIFS avec leur dénominateur, et la lecture /20 qui en découle. */
  readonly resume = computed<ResumeResultats | null>(() => {
    const r = this.resultats();
    if (r.statut !== 'ok' || r.valeur.length === 0) return null;
    const totaux = r.valeur.map((x) => this.totalEffectif(x));
    const moyenne = totaux.reduce((a, b) => a + b, 0) / totaux.length;
    const denoms = r.valeur.map((x) => this.denominateurEffectif(x));
    const commun = denoms.every((d) => d != null && d === denoms[0]) ? (denoms[0] as number) : null;
    const notes20 = r.valeur.map((x) => this.note20(x)).filter((v): v is number => v != null);
    const n20 = notes20.length;
    return {
      n: totaux.length,
      moyenne,
      max: Math.max(...totaux),
      min: Math.min(...totaux),
      denominateur: commun,
      n20,
      moyenne20: n20 > 0 ? notes20.reduce((a, b) => a + b, 0) / n20 : null,
      min20: n20 > 0 ? Math.min(...notes20) : null,
      max20: n20 > 0 ? Math.max(...notes20) : null,
    };
  });

  readonly totalLabel = computed(() => {
    const v = this.baremeVersion();
    return v != null ? `barème de délibération v${v}` : 'brut';
  });

  /**
   * La décision de délibération qui produit la lecture servie : la version
   * courante de l'historique scoring (la dernière fait foi, ADR-0030 D3),
   * ou null quand aucune version n'est servie.
   */
  readonly decision = computed<BaremeDeliberation | null>(() => {
    const v = this.baremeVersion();
    const b = this.baremes();
    if (v == null || b.statut !== 'ok') return null;
    return b.valeur.find((x) => x.version === v) ?? null;
  });

  /** Les opérations de la décision en français, cibles nommées d'après les stations chargées. */
  readonly operationsLisibles = computed<string[]>(() => {
    const d = this.decision();
    if (d == null) return [];
    const s = this.stations();
    const nomStation = (id: number): string => {
      const st = s.statut === 'ok' ? s.valeur.find((x) => x.id === id) : undefined;
      return st?.nom ?? `Station ${id}`;
    };
    // L'écran admin ne charge pas les grilles : un critère est nommé par son numéro.
    const noms = { station: nomStation, critere: (id: number) => `critère n°${id}` };
    return d.operations.map((op) => libelleOperation(op, noms));
  });

  constructor() {
    this.load();
  }

  load(): void {
    this.examen.set({ statut: 'chargement' });
    this.stations.set({ statut: 'chargement' });
    this.resultats.set({ statut: 'chargement' });
    this.baremes.set({ statut: 'chargement' });
    forkJoin({
      examen: this.examApi.getExamen(this.examenId).pipe(
        map((v): Etat<ExamenResponse> => ({ statut: 'ok', valeur: v })),
        catchError(() => of<Etat<ExamenResponse>>({ statut: 'indisponible' })),
      ),
      stations: this.examApi.listStations(this.examenId).pipe(
        map((v): Etat<StationSummary[]> => ({ statut: 'ok', valeur: v })),
        catchError(() => of<Etat<StationSummary[]>>({ statut: 'indisponible' })),
      ),
      resultats: this.scoringApi.getExamenResults(this.examenId).pipe(
        map((v): Etat<ExamenResult[]> => ({ statut: 'ok', valeur: v })),
        catchError(() => of<Etat<ExamenResult[]>>({ statut: 'indisponible' })),
      ),
      baremes: this.scoringApi.listBaremesDeliberation(this.examenId).pipe(
        map((v): Etat<BaremeDeliberation[]> => ({ statut: 'ok', valeur: v })),
        catchError(() => of<Etat<BaremeDeliberation[]>>({ statut: 'indisponible' })),
      ),
      matieres: this.directoryApi.listMatieres().pipe(catchError(() => of([]))),
    }).subscribe((r) => {
      this.examen.set(r.examen);
      this.stations.set(r.stations);
      this.resultats.set(r.resultats);
      this.baremes.set(r.baremes);
      const labels: Record<number, string> = {};
      for (const m of r.matieres) labels[m.id] = m.libelle;
      this.matiereLabels.set(labels);
    });
  }

  matiereLabel(matiereId: number): string {
    return this.matiereLabels()[matiereId] ?? `Matière ${matiereId}`;
  }

  displayStatut(e: ExamenResponse): string {
    return statutDisplayLabel(e.statut, e.dateExamen);
  }

  nomEtudiant(r: ExamenResult): string {
    const nom = [r.prenom, r.nom].filter(Boolean).join(' ');
    return nom || r.numeroInscription || `Participation ${r.participationId}`;
  }

  /** « 5 septembre 2026 » depuis l'horodatage ISO de scoring ; la chaîne brute si illisible. */
  dateDecision(iso: string | null): string {
    if (!iso) return '—';
    const d = new Date(iso);
    return Number.isNaN(d.getTime())
      ? iso
      : d.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' });
  }
}
