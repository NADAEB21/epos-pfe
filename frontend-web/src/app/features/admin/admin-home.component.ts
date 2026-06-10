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
  template: `
    <header class="mb-6">
      <h1 class="text-2xl font-semibold text-gray-900">Console d'administration</h1>
      <p class="text-sm text-gray-500">Vue d'ensemble de la plateforme — toutes matières confondues.</p>
    </header>

    @if (loading()) {
      <div class="space-y-6 animate-pulse">
        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          <div class="h-24 rounded-xl bg-gray-200"></div>
          <div class="h-24 rounded-xl bg-gray-200"></div>
          <div class="h-24 rounded-xl bg-gray-200"></div>
          <div class="h-24 rounded-xl bg-gray-200"></div>
        </div>
        <div class="h-40 rounded-xl bg-gray-200"></div>
      </div>
    } @else {
      @if (data(); as d) {
      <!-- Stat tiles -->
      <section class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
        <div class="rounded-xl bg-white border border-gray-200 shadow-card p-4">
          <div class="text-2xl font-semibold text-gray-900">{{ d.userCount }}</div>
          <div class="text-sm text-gray-500">utilisateurs</div>
        </div>
        <div class="rounded-xl bg-white border border-gray-200 shadow-card p-4">
          <div class="text-2xl font-semibold text-gray-900">{{ d.matiereCount }}</div>
          <div class="text-sm text-gray-500">matières au catalogue</div>
        </div>
        <div class="rounded-xl bg-white border border-gray-200 shadow-card p-4">
          <div class="text-2xl font-semibold text-gray-900">{{ d.examTotal }}</div>
          <div class="text-sm text-gray-500">examens (toutes matières)</div>
        </div>
        <div class="rounded-xl bg-white border border-gray-200 shadow-card p-4">
          <div class="text-2xl font-semibold text-gray-900">{{ d.examActive }}</div>
          <div class="text-sm text-gray-500">examens actifs</div>
        </div>
      </section>

      <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <!-- Admin quick links -->
        <section class="rounded-xl bg-white border border-gray-200 shadow-card p-5">
          <h3 class="font-semibold text-gray-900 mb-3">Administration</h3>
          <ul class="divide-y divide-gray-100">
            @for (l of adminLinks; track l.link) {
              <li>
                <a
                  [routerLink]="l.link"
                  class="flex items-center justify-between py-3 group"
                >
                  <span>
                    <span class="block text-sm font-medium text-gray-800 group-hover:text-brand">{{ l.label }}</span>
                    <span class="block text-xs text-gray-400">{{ l.desc }}</span>
                  </span>
                  <span class="text-gray-300 group-hover:text-brand">&rsaquo;</span>
                </a>
              </li>
            }
          </ul>
        </section>

        <!-- Recent exams oversight -->
        <section class="rounded-xl bg-white border border-gray-200 shadow-card p-5">
          <h3 class="font-semibold text-gray-900 mb-3">Examens récents</h3>
          @if (d.recentExams.length === 0) {
            <p class="text-sm text-gray-400">Aucun examen.</p>
          } @else {
            <ul class="divide-y divide-gray-100">
              @for (e of d.recentExams; track e.id) {
                <li class="flex items-center justify-between py-2 text-sm">
                  <span class="text-gray-700">{{ e.nom }}</span>
                  <span class="text-xs text-gray-400">{{ statutLabel(e.statut) }}</span>
                </li>
              }
            </ul>
          }
        </section>
      </div>
      }
    }
  `,
})
export class AdminHomeComponent {
  private readonly directoryApi = inject(DirectoryApiService);
  private readonly examApi = inject(ExamApiService);
  private readonly authStore = inject(AuthStore);

  readonly data = signal<AdminOverview | null>(null);
  readonly loading = signal(true);

  readonly adminLinks: AdminLink[] = [
    { label: 'Utilisateurs', desc: 'Comptes & rôles (tous)', link: '/admin/utilisateurs' },
    { label: 'Matières', desc: 'Catalogue des matières', link: '/admin/matieres' },
    { label: 'Templates globaux', desc: 'Modèles de grilles partagés', link: '/admin/templates' },
    { label: 'Examens', desc: 'Supervision toutes matières', link: '/admin/examens' },
  ];

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    forkJoin({
      users: this.directoryApi.listUsers().pipe(catchError(() => of([]))),
      matieres: this.directoryApi.listMatieres().pipe(catchError(() => of([]))),
      exams: this.examApi
        .listExamens({ size: 100, sort: 'dateExamen,desc' })
        .pipe(
          map((p) => p.content ?? []),
          catchError(() => of([] as ExamenResponse[])),
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
