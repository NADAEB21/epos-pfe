import { Injectable, inject, signal } from '@angular/core';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ExamenResponse } from '../../core/api/models';

/**
 * Per-workspace exam state, shared by the ExamenWorkspaceComponent and every tab
 * under examens/:id. Provided at the examens/:id route (route-scoped, not root),
 * so one instance is created per open workspace and torn down on leave.
 *
 * Why a store rather than each component fetching independently: a tab that
 * mutates the exam's lifecycle (Lancement → changerStatut) must make the parent's
 * status-aware tab list + lifecycle bar update without a manual page refresh.
 * With the exam in a shared signal, the parent's derived tabs recompute the
 * moment a child calls reload().
 */
@Injectable()
export class ExamenWorkspaceStore {
  private readonly examApi = inject(ExamApiService);

  readonly exam = signal<ExamenResponse | null>(null);
  readonly loading = signal(true);
  readonly error = signal(false);

  /** Tracks the exam currently loaded, so reload() needs no argument. */
  private currentId: number | null = null;

  load(id: number): void {
    this.currentId = id;
    this.loading.set(true);
    this.error.set(false);
    this.examApi.getExamen(id).subscribe({
      next: (e) => {
        this.exam.set(e);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  /** Re-fetch the current exam — used after a child mutates its lifecycle. */
  reload(): void {
    if (this.currentId != null) this.load(this.currentId);
  }
}
