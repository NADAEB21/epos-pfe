import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ScoringApiService } from '../../core/api/scoring-api.service';
import { ExamenResponse, ExamenResult, StationSummary } from '../../core/api/models';
import { statutDisplayLabel } from '../../core/api/exam-status';

/** A section that either loaded, or says why it did not — never a blank. */
type Etat<T> = { statut: 'ok'; valeur: T } | { statut: 'indisponible' } | { statut: 'chargement' };

/**
 * #390 — Détail d'un examen en LECTURE SEULE pour le SUPER_ADMIN.
 *
 * <p>ADR-0018 D5 : lire partout, n'écrire rien de pédagogique. Cet écran ne
 * porte AUCUN contrôle d'écriture (pas de lancement, pas de réajustement, pas
 * de barème de délibération) et ne renvoie jamais vers le workspace du
 * responsable. Trois lectures indépendantes — définition (exam-service),
 * stations (exam-service), résultats consolidés (scoring) — chacune dégrade
 * séparément et le DIT : une panne de scoring ne cache pas la définition.
 * Les endpoints autorisent déjà le SUPER_ADMIN (`MatiereAccessChecker.canAccess`,
 * `matiereAccessGuard`) : zéro changement backend.
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
  readonly matiereLabels = signal<Record<number, string>>({});

  readonly titre = computed(() => {
    const e = this.examen();
    return e.statut === 'ok' ? e.valeur.nom : `Examen ${this.examenId}`;
  });

  /** Résumé honnête des résultats : effectif noté et moyenne des totaux BRUTS (pas de /20 inventé). */
  readonly resume = computed(() => {
    const r = this.resultats();
    if (r.statut !== 'ok' || r.valeur.length === 0) return null;
    const totaux = r.valeur.map((x) => x.totalScore);
    const moyenne = totaux.reduce((a, b) => a + b, 0) / totaux.length;
    return { n: totaux.length, moyenne, max: Math.max(...totaux), min: Math.min(...totaux) };
  });

  constructor() {
    this.load();
  }

  load(): void {
    this.examen.set({ statut: 'chargement' });
    this.stations.set({ statut: 'chargement' });
    this.resultats.set({ statut: 'chargement' });
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
      matieres: this.directoryApi.listMatieres().pipe(catchError(() => of([]))),
    }).subscribe((r) => {
      this.examen.set(r.examen);
      this.stations.set(r.stations);
      this.resultats.set(r.resultats);
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
}
