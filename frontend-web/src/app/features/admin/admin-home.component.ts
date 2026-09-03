import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ExamenResponse, StatutExamen } from '../../core/api/models';
import { AuthStore } from '../../core/auth/auth.store';

const ACTIVE: StatutExamen[] = ['BROUILLON', 'CONFIGURE', 'EN_COURS'];

const STATUT_LABELS: Record<StatutExamen, string> = {
  BROUILLON: 'Brouillon',
  CONFIGURE: 'Configuré',
  EN_COURS: 'En cours',
  TERMINE: 'Terminé',
  ARCHIVE: 'Archivé',
};

interface AdminOverview {
  userCount: number;
  matiereCount: number;
  examTotal: number;
  examActive: number;
  recentExams: ExamenResponse[];
}

interface AdminLink {
  label: string;
  desc: string;
  link: string;
}

/**
 * Super-admin landing — platform oversight, NOT the Responsable's matiere
 * triage. Pulls real global counts (all users, the matiere catalogue, all
 * exams across matieres) that a SUPER_ADMIN is authorized to read, and links
 * into the Administration sections. Each stream degrades to a safe default so
 * one outage cannot blank the page.
 */
@Component({
  selector: 'app-admin-home',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './admin-home.component.html',
})
export class AdminHomeComponent {
  private readonly directoryApi = inject(DirectoryApiService);
  private readonly examApi = inject(ExamApiService);
  private readonly authStore = inject(AuthStore);

  readonly data = signal<AdminOverview | null>(null);
  readonly loading = signal(true);
  /** #390 — une panne de exam-service ne se lit plus « 0 examen » : elle se dit. */
  readonly examsError = signal(false);

  readonly adminLinks: AdminLink[] = [
    { label: 'Utilisateurs', desc: 'Comptes & rôles (tous)', link: '/admin/utilisateurs' },
    { label: 'Matières', desc: 'Catalogue des matières', link: '/admin/matieres' },
    // « Templates globaux » supprimé (W2/ADR-0027) : rédiger un modèle est une
    // autorité pédagogique (ADR-0018 D5) — l'écran promettait un acte interdit.
    { label: 'Examens', desc: 'Supervision toutes matières', link: '/admin/examens' },
    // #365 (N10) — BI facultaire : agrégé d'abord, jamais par étudiant (ADR-0021 D5).
    { label: 'Synthèse', desc: 'Tendances agrégées par matière', link: '/admin/synthese' },
  ];

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.examsError.set(false);
    forkJoin({
      users: this.directoryApi.listUsers().pipe(catchError(() => of([]))),
      matieres: this.directoryApi.listMatieres().pipe(catchError(() => of([]))),
      exams: this.examApi
        .listExamens({ size: 100, sort: 'dateExamen,desc' })
        .pipe(
          map((p) => p.content ?? []),
          catchError(() => {
            this.examsError.set(true);
            return of([] as ExamenResponse[]);
          }),
        ),
    }).subscribe((r) => {
      const exams: ExamenResponse[] = r.exams;
      this.data.set({
        userCount: r.users.length,
        matiereCount: r.matieres.length,
        examTotal: exams.length,
        examActive: exams.filter((e) => ACTIVE.includes(e.statut)).length,
        recentExams: exams.slice(0, 5),
      });
      this.loading.set(false);
    });
  }

  statutLabel(s: StatutExamen): string {
    return STATUT_LABELS[s];
  }
}
