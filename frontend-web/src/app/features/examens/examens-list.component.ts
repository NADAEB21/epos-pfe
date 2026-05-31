import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ExamenResponse, StatutExamen } from '../../core/api/models';

const STATUT_LABELS: Record<StatutExamen, string> = {
  BROUILLON: 'Brouillon',
  CONFIGURE: 'Configuré',
  EN_COURS: 'En cours',
  TERMINE: 'Terminé',
  ARCHIVE: 'Archivé',
};

@Component({
  selector: 'app-examens-list',
  standalone: true,
  imports: [RouterLink],
  template: `
    <header class="mb-6 flex items-center justify-between">
      <h1 class="text-2xl font-semibold text-gray-900">Mes examens</h1>
    </header>

    @if (loading()) {
      <div class="space-y-2 animate-pulse">
        <div class="h-14 rounded-lg bg-gray-200"></div>
        <div class="h-14 rounded-lg bg-gray-200"></div>
        <div class="h-14 rounded-lg bg-gray-200"></div>
      </div>
    } @else if (error()) {
      <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card">
        <p class="text-gray-700 mb-3">Impossible de charger les examens.</p>
        <button type="button" (click)="load()" class="text-sm text-brand hover:underline">Reessayer</button>
      </div>
    } @else if (exams().length === 0) {
      <div class="rounded-xl bg-white border border-gray-200 p-8 text-center text-gray-400 shadow-card">
        Aucun examen pour le moment.
      </div>
    } @else {
      <ul class="rounded-xl bg-white border border-gray-200 shadow-card divide-y divide-gray-100">
        @for (e of exams(); track e.id) {
          <li>
            <a [routerLink]="['/examens', e.id]" class="flex items-center justify-between px-4 py-3 hover:bg-gray-50">
              <div>
                <div class="text-sm font-medium text-gray-900">{{ e.nom }}</div>
                <div class="text-xs text-gray-500">{{ e.dateExamen }}</div>
              </div>
              <span class="text-xs font-medium px-2 py-0.5 rounded-full bg-brand-50 text-brand-dark">
                {{ statutLabel(e.statut) }}
              </span>
            </a>
          </li>
        }
      </ul>
    }
  `,
})
export class ExamensListComponent {
  private readonly examApi = inject(ExamApiService);

  readonly exams = signal<ExamenResponse[]>([]);
  readonly loading = signal(true);
  readonly error = signal(false);

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(false);
    this.examApi.listExamens({ size: 50, sort: 'dateExamen,desc' }).subscribe({
      next: (page) => {
        this.exams.set(page.content ?? []);
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
}
