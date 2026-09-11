import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ExamenResponse, StatutExamen, SyntheseFaculte } from '../../core/api/models';
import { AiApiService } from '../../core/api/ai-api.service';
import { BarreConcentrationLigne, BarresConcentrationComponent } from '../../shared/graphes/barres-concentration.component';
import { fmtNum, fmtTaux } from '../../shared/ia/lecture-bi';
import { IconComponent } from '../../shared/ui/icon.component';
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
  imports: [RouterLink, BarresConcentrationComponent, IconComponent],
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

  private readonly ai = inject(AiApiService);

  /**
   * #407 — « Réussite par matière » : la synthèse facultaire (agrégée d'abord,
   * ADR-0021 D5), chargée À PART du forkJoin des compteurs : le module IA absent
   * ne blanchit pas la console, la carte le dit.
   */
  readonly synthese = signal<SyntheseFaculte | null>(null);
  readonly syntheseEtat = signal<'chargement' | 'absents' | 'prets'>('chargement');
  readonly matiereLabels = signal<Record<number, string>>({});

  readonly lignesMatieres = computed<BarreConcentrationLigne[]>(() =>
    (this.synthese()?.matieres ?? []).map((m) => ({
      id: m.matiere_id,
      label: this.matiereLabels()[m.matiere_id] ?? `Matière ${m.matiere_id}`,
      valeurPct: m.taux_reussite === null ? null : m.taux_reussite * 100,
      n: m.n_etudiants,
      detail: m.taux_reussite === null ? (m.raison ?? undefined) : `médiane ${fmtNum(m.mediane_sur_20)} /20 · ${m.nb_examens_clos} session(s) close(s)`,
    })),
  );

  constructor() {
    this.load();
    this.loadSynthese();
  }

  readonly fmtTaux = fmtTaux;

  private loadSynthese(): void {
    this.syntheseEtat.set('chargement');
    this.ai.getSynthese().subscribe({
      next: (s) => {
        this.synthese.set(s);
        this.syntheseEtat.set('prets');
      },
      error: () => this.syntheseEtat.set('absents'),
    });
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
      const labels: Record<number, string> = {};
      for (const m of r.matieres) labels[m.id] = m.libelle;
      this.matiereLabels.set(labels);
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
