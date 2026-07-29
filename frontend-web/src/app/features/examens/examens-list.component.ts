import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ExamenResponse } from '../../core/api/models';
import { isAVenir, isFutureDate, statutDisplayLabel } from '../../core/api/exam-status';

/** One lifecycle section of the list. `match` derives the bucket from status + date. */
interface ExamBucket {
  key: string;
  label: string;
  match: (e: ExamenResponse) => boolean;
}

/**
 * Display buckets in lifecycle order. "À venir" and "Configurés" both come from
 * the CONFIGURE status but split on whether the date is still in the future
 * (see exam-status.ts) — a future-dated configured exam is locked/ready, a
 * past-or-today one is awaiting its jour J. Only non-empty sections render.
 */
const BUCKETS: ExamBucket[] = [
  { key: 'EN_COURS', label: 'En cours', match: (e) => e.statut === 'EN_COURS' },
  { key: 'A_VENIR', label: 'À venir', match: (e) => isAVenir(e.statut, e.dateExamen) },
  {
    key: 'CONFIGURE',
    label: 'Configurés',
    match: (e) => e.statut === 'CONFIGURE' && !isFutureDate(e.dateExamen),
  },
  { key: 'BROUILLON', label: 'Brouillons', match: (e) => e.statut === 'BROUILLON' },
  { key: 'TERMINE', label: 'Terminés', match: (e) => e.statut === 'TERMINE' },
  { key: 'ARCHIVE', label: 'Archivés', match: (e) => e.statut === 'ARCHIVE' },
];

/** Page size for the accumulating fetch — loops until the API reports `last`. */
const PAGE_SIZE = 100;

interface RenderedGroup {
  key: string;
  label: string;
  exams: ExamenResponse[];
}

@Component({
  selector: 'app-examens-list',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './examens-list.component.html',
})
export class ExamensListComponent {
  private readonly examApi = inject(ExamApiService);

  readonly allExams = signal<ExamenResponse[]>([]);
  readonly loading = signal(true);
  readonly error = signal(false);
  readonly search = signal('');
  readonly filter = signal<string>('TOUS');

  /** Exams matching the free-text search (name or date), full set otherwise. */
  readonly filteredExams = computed(() => {
    const q = this.search().trim().toLowerCase();
    if (!q) return this.allExams();
    return this.allExams().filter(
      (e) => e.nom.toLowerCase().includes(q) || (e.dateExamen ?? '').includes(q),
    );
  });

  /** Every non-empty bucket over the search-filtered set, in lifecycle order. */
  readonly availableBuckets = computed<RenderedGroup[]>(() => {
    const exams = this.filteredExams();
    return BUCKETS.map((b) => ({
      key: b.key,
      label: b.label,
      exams: exams.filter(b.match),
    })).filter((g) => g.exams.length > 0);
  });

  /** Buckets actually rendered — all of them, or just the selected status filter. */
  readonly visibleGroups = computed<RenderedGroup[]>(() => {
    const selected = this.filter();
    const groups = this.availableBuckets();
    return selected === 'TOUS' ? groups : groups.filter((g) => g.key === selected);
  });

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(false);
    this.allExams.set([]);
    this.fetchPage(0, []);
  }

  /**
   * Accumulate every page so the list is never silently truncated (the old
   * hardcoded `size: 50` hid exam #51+). Loops until the API reports `last`.
   */
  private fetchPage(page: number, acc: ExamenResponse[]): void {
    this.examApi.listExamens({ page, size: PAGE_SIZE, sort: 'dateExamen,desc' }).subscribe({
      next: (res) => {
        const next = acc.concat(res.content ?? []);
        if (res.last || (res.content?.length ?? 0) === 0) {
          this.allExams.set(next);
          this.loading.set(false);
        } else {
          this.fetchPage(page + 1, next);
        }
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  onSearch(event: Event): void {
    this.search.set((event.target as HTMLInputElement).value);
  }

  chipClass(key: string): string {
    const active = this.filter() === key;
    return [
      'px-2.5 py-1 rounded-full text-xs font-medium border transition-colors',
      active
        ? 'bg-brand text-white border-brand'
        : 'bg-white text-gray-600 border-gray-300 hover:bg-gray-50',
    ].join(' ');
  }

  /** Date-aware status — CONFIGURE + future date → "À venir". */
  displayStatut(e: ExamenResponse): string {
    return statutDisplayLabel(e.statut, e.dateExamen);
  }
}
