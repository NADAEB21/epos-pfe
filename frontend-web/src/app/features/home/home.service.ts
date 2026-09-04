import { Injectable, inject } from '@angular/core';
import { Observable, catchError, forkJoin, map, of, switchMap } from 'rxjs';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ScoringApiService } from '../../core/api/scoring-api.service';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import { ExamenResponse, StatutExamen } from '../../core/api/models';

export type ChecklistState = 'ok' | 'todo' | 'unknown';

export interface ChecklistItem {
  label: string;
  state: ChecklistState;
}

export interface ActionRequise {
  label: string;
  examenId: number | null;
  tab: string; // workspace tab segment to deep-link into
  severity: 'warning' | 'info';
}

export interface AccueilData {
  closestExam: ExamenResponse | null;
  closestExamChecklist: ChecklistItem[];
  upcomingCount: number;
  studentCount: number;
  evaluateurCount: number;
  pendingLockCount: number;
  recentExams: ExamenResponse[];
  actionsRequises: ActionRequise[];
  matiereLabels: Record<number, string>;
}

const ACTIVE: StatutExamen[] = ['BROUILLON', 'CONFIGURE', 'EN_COURS'];

/**
 * Aggregates the Accueil (home/triage) screen from auth + exam + scoring via a
 * single forkJoin. The exam list is the critical stream — if it fails the whole
 * call errors and the screen offers a retry. Secondary streams (notations,
 * etudiants, evaluateurs, matieres) degrade to empty so one 403/outage cannot
 * blank the screen. No backend aggregator/BFF (decision 6 of the Phase B plan).
 */
@Injectable({ providedIn: 'root' })
export class HomeService {
  private readonly examApi = inject(ExamApiService);
  private readonly scoringApi = inject(ScoringApiService);
  private readonly directoryApi = inject(DirectoryApiService);

  loadAccueil(): Observable<AccueilData> {
    return forkJoin({
      // Critical: no catchError — a failure surfaces the retry state.
      examPage: this.examApi.listExamens({ size: 50, sort: 'dateExamen,desc' }),
      notations: this.scoringApi.listNotations().pipe(catchError(() => of([]))),
      etudiants: this.scoringApi.listEtudiants().pipe(catchError(() => of([]))),
      evaluateurs: this.directoryApi.listUsers('EVALUATEUR').pipe(catchError(() => of([]))),
      matieres: this.directoryApi.listMatieres().pipe(catchError(() => of([]))),
    }).pipe(
      switchMap((base) => {
        const exams = base.examPage.content ?? [];
        const closest = this.pickClosest(exams);
        // One extra call to hydrate the closest exam's stations for the
        // readiness checklist; falls back to the list row on failure.
        const detail$: Observable<ExamenResponse | null> = closest
          ? this.examApi.getExamen(closest.id).pipe(catchError(() => of(closest)))
          : of(null);
        return detail$.pipe(map((detail) => this.assemble(base, exams, detail)));
      }),
    );
  }

  private assemble(
    base: {
      notations: { verouillee: boolean | null }[];
      etudiants: unknown[];
      evaluateurs: unknown[];
      matieres: { id: number; libelle: string }[];
    },
    exams: ExamenResponse[],
    closest: ExamenResponse | null,
  ): AccueilData {
    const today = this.todayIso();
    const upcoming = exams.filter((e) => ACTIVE.includes(e.statut) && e.dateExamen >= today);
    const pendingLockCount = base.notations.filter((n) => n.verouillee === false).length;

    const matiereLabels: Record<number, string> = {};
    for (const m of base.matieres) matiereLabels[m.id] = m.libelle;

    return {
      closestExam: closest,
      closestExamChecklist: closest ? this.buildChecklist(closest) : [],
      upcomingCount: upcoming.length,
      studentCount: base.etudiants.length,
      evaluateurCount: base.evaluateurs.length,
      pendingLockCount,
      recentExams: exams.slice(0, 5),
      actionsRequises: this.buildActions(exams, pendingLockCount, today),
      matiereLabels,
    };
  }

  /** Closest active exam: nearest future date, else the soonest active one. */
  private pickClosest(exams: ExamenResponse[]): ExamenResponse | null {
    const today = this.todayIso();
    const active = exams
      .filter((e) => ACTIVE.includes(e.statut))
      .sort((a, b) => a.dateExamen.localeCompare(b.dateExamen));
    return active.find((e) => e.dateExamen >= today) ?? active[0] ?? null;
  }

  private buildChecklist(exam: ExamenResponse): ChecklistItem[] {
    const stationsOk = (exam.stations?.length ?? 0) > 0;
    return [
      { label: 'Stations définies', state: stationsOk ? 'ok' : 'todo' },
      // grilles / roster / planning readiness need a backend pre-launch
      // validation endpoint (filed in backlog) — neutral until then.
      { label: 'Grilles complètes', state: 'unknown' },
      { label: 'Roster chargé', state: 'unknown' },
      { label: 'Planning généré', state: 'unknown' },
      { label: 'Sujet PDF', state: exam.hasPdfSujet ? 'ok' : 'todo' },
      { label: 'Prêt au lancement', state: 'unknown' },
    ];
  }

  private buildActions(
    exams: ExamenResponse[],
    pendingLockCount: number,
    today: string,
  ): ActionRequise[] {
    const actions: ActionRequise[] = [];

    if (pendingLockCount > 0) {
      actions.push({
        label: `${pendingLockCount} notation(s) en attente de verrouillage`,
        examenId: null,
        tab: 'resultats',
        severity: 'warning',
      });
    }

    // Active exams whose date is within the next 7 days and still in setup.
    for (const e of exams) {
      if (e.statut !== 'BROUILLON' && e.statut !== 'CONFIGURE') continue;
      const days = this.daysUntil(e.dateExamen, today);
      if (days !== null && days >= 0 && days <= 7) {
        actions.push({
          label: `« ${e.nom} » approche (J-${days}) — configuration à finaliser`,
          examenId: e.id,
          tab: 'vue-ensemble',
          severity: 'warning',
        });
      }
    }

    return actions;
  }

  private todayIso(): string {
    return new Date().toISOString().slice(0, 10);
  }

  private daysUntil(dateIso: string, todayIso: string): number | null {
    const target = Date.parse(dateIso);
    const base = Date.parse(todayIso);
    if (Number.isNaN(target) || Number.isNaN(base)) return null;
    return Math.round((target - base) / 86_400_000);
  }
}
