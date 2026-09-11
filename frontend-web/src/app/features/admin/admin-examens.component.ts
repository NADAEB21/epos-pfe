import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ExamenResponse } from '../../core/api/models';
import { isAVenir, isFutureDate, statutDisplayLabel } from '../../core/api/exam-status';

/** One lifecycle section — same buckets as « Mes examens », same order. */
interface ExamBucket {
  key: string;
  label: string;
  match: (e: ExamenResponse) => boolean;
}

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

/** Accumulating page size — loops until the API reports `last` (never a silent ceiling). */
const PAGE_SIZE = 100;

interface RenderedGroup {
  key: string;
  label: string;
  exams: ExamenResponse[];
}

/**
 * #390 — Supervision des examens de la faculté (SUPER_ADMIN), LECTURE SEULE.
 *
 * <p>ADR-0018 D5 : l'administrateur LIT partout et n'écrit rien de pédagogique.
 * Cet écran est donc DISTINCT de « Mes examens » du responsable : mêmes données
 * (le backend rend déjà TOUS les examens à un super-admin —
 * `ExamenServiceImpl.listerTous`, `isUnrestricted()`), mais aucune ligne ne
 * mène au workspace et à ses onglets d'édition — chaque ligne ouvre le détail
 * en lecture seule `/admin/examens/:id`. Le libellé de matière vient du
 * catalogue (dégradé en « Matière n » si le catalogue est en panne — jamais un
 * écran vide).
 */
@Component({
  selector: 'app-admin-examens',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './admin-examens.component.html',
})
export class AdminExamensComponent {
  private readonly examApi = inject(ExamApiService);
  private readonly directoryApi = inject(DirectoryApiService);

  readonly allExams = signal<ExamenResponse[]>([]);
  readonly loading = signal(true);
  readonly error = signal(false);
  readonly search = signal('');
  readonly filter = signal<string>('TOUS');
  readonly matiereFilter = signal<number | null>(null);

  /** matiereId → libellé ; vide si le catalogue est en panne (repli numérique). */
  readonly matiereLabels = signal<Record<number, string>>({});
  readonly catalogueEnPanne = signal(false);

  /** Matières présentes dans la liste (pour le filtre), libellé résolu. */
  readonly matieresPresentes = computed(() => {
    const ids = [...new Set(this.allExams().map((e) => e.matiereId))];
    return ids
      .map((id) => ({ id, libelle: this.matiereLabel(id) }))
      .sort((a, b) => a.libelle.localeCompare(b.libelle, 'fr'));
  });

  readonly filteredExams = computed(() => {
    const q = this.search().trim().toLowerCase();
    const mid = this.matiereFilter();
    return this.allExams().filter((e) => {
      if (mid !== null && e.matiereId !== mid) return false;
      if (!q) return true;
      return (
        e.nom.toLowerCase().includes(q) ||
        (e.dateExamen ?? '').includes(q) ||
        this.matiereLabel(e.matiereId).toLowerCase().includes(q)
      );
    });
  });

  readonly availableBuckets = computed<RenderedGroup[]>(() => {
    const exams = this.filteredExams();
    return BUCKETS.map((b) => ({ key: b.key, label: b.label, exams: exams.filter(b.match) })).filter(
      (g) => g.exams.length > 0,
    );
  });

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
    this.chargerCatalogue();
    this.fetchPage(0, []);
  }

  /** Catalogue chargé UNE fois par load ; en panne → repli « Matière n », dit. */
  private chargerCatalogue(): void {
    this.directoryApi
      .listMatieres()
      .pipe(catchError(() => of(null)))
      .subscribe((matieres) => {
        if (matieres === null) {
          this.catalogueEnPanne.set(true);
          return;
        }
        const labels: Record<number, string> = {};
        for (const m of matieres) labels[m.id] = m.libelle;
        this.matiereLabels.set(labels);
        this.catalogueEnPanne.set(false);
      });
  }

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

  matiereLabel(matiereId: number): string {
    return this.matiereLabels()[matiereId] ?? `Matière ${matiereId}`;
  }

  onSearch(event: Event): void {
    this.search.set((event.target as HTMLInputElement).value);
  }

  onMatiereChange(value: string): void {
    this.matiereFilter.set(value ? Number(value) : null);
  }

  chipClass(key: string): string {
    const active = this.filter() === key;
    return [
      'px-2.5 py-1 rounded-full text-xs font-medium border transition-colors',
      active ? 'bg-brand text-white border-brand' : 'bg-white text-gray-600 border-gray-300 hover:bg-gray-50',
    ].join(' ');
  }

  displayStatut(e: ExamenResponse): string {
    return statutDisplayLabel(e.statut, e.dateExamen);
  }
}
