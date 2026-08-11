import { Component, computed, inject, signal } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';
import { ExamApiService } from '../../core/api/exam-api.service';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import { AuthStore } from '../../core/auth/auth.store';
import { MatiereResponse } from '../../core/api/models';

type SubmitError = 'scope' | 'validation' | 'network' | null;

/**
 * Create an exam from scratch. The matière is constrained to the responsable's
 * JWT scope (AuthStore.responsableMatiereIds): a single-matière responsable
 * gets it auto-set with a read-only label, a multi-matière one picks from a
 * select. The route is responsableGuard-gated, so the scope here is always ≥1
 * — there is no super-admin / global branch to handle.
 *
 * On success the exam lands in BROUILLON and we navigate straight into its
 * workspace (/examens/{id}) so the responsable continues configuring.
 */
@Component({
  selector: 'app-examen-create',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './examen-create.component.html',
})
export class ExamenCreateComponent {
  private readonly fb = inject(FormBuilder);
  private readonly examApi = inject(ExamApiService);
  private readonly directoryApi = inject(DirectoryApiService);
  private readonly authStore = inject(AuthStore);
  private readonly router = inject(Router);

  readonly submitting = signal(false);
  readonly submitError = signal<SubmitError>(null);

  /** All matières resolved from the catalogue, intersected with the JWT scope. */
  private readonly scopedMatieres = signal<MatiereResponse[]>([]);

  readonly form = this.fb.nonNullable.group({
    nom: ['', [Validators.required, Validators.minLength(3), Validators.maxLength(150)]],
    matiereId: [0, [Validators.required, Validators.min(1)]],
    dateExamen: ['', [Validators.required]],
    heureDebut: ['09:00', [Validators.required]],
    dureeStationMin: this.fb.control<number | null>(null, [Validators.min(1), Validators.max(180)]),
    nbEtudiantsParStation: this.fb.control<number | null>(null, [Validators.min(1), Validators.max(10)]),
    description: ['', [Validators.maxLength(500)]],
  });

  /**
   * Picker options; empty until the catalogue loads. #134 — une matière
   * RETIRÉE n'est plus proposée pour un nouvel examen (scopedMatieres reste
   * complète : soleMatiereLabel doit garder le libellé même retirée).
   */
  readonly matiereOptions = computed(() => this.scopedMatieres().filter((m) => m.active));

  /** Label shown when the responsable owns a single matière (no picker). */
  readonly soleMatiereLabel = computed(() => {
    const ids = this.authStore.responsableMatiereIds();
    const only = ids[0];
    const match = this.scopedMatieres().find((m) => m.id === only);
    return match ? match.libelle : `Matière #${only ?? '—'}`;
  });

  constructor() {
    const scopeIds = this.authStore.responsableMatiereIds();

    // Single-matière responsable: lock matiereId now so the form is valid even
    // before the catalogue (label only) loads. Multi-matière: leave at 0 so the
    // picker forces an explicit choice.
    if (scopeIds.length === 1) {
      this.form.controls.matiereId.setValue(scopeIds[0]);
    }

    this.directoryApi.listMatieres().subscribe({
      next: (all) => {
        const scope = new Set(scopeIds);
        this.scopedMatieres.set(all.filter((m) => scope.has(m.id)));
      },
      // Catalogue is for labels/picker only — a single-matière responsable can
      // still submit with the scoped id even if this fails.
      error: () => this.scopedMatieres.set([]),
    });
  }

  onSubmit(): void {
    if (this.form.invalid || this.submitting()) return;

    const raw = this.form.getRawValue();
    this.submitError.set(null);
    this.submitting.set(true);

    this.examApi
      .createExamen({
        nom: raw.nom.trim(),
        matiereId: raw.matiereId,
        dateExamen: raw.dateExamen,
        heureDebut: raw.heureDebut || undefined,
        dureeStationMin: raw.dureeStationMin ?? undefined,
        nbEtudiantsParStation: raw.nbEtudiantsParStation ?? undefined,
        description: raw.description.trim() || undefined,
      })
      .subscribe({
        next: (exam) => {
          this.submitting.set(false);
          this.router.navigate(['/examens', exam.id]);
        },
        error: (err: HttpErrorResponse) => {
          this.submitting.set(false);
          if (err.status === 403) this.submitError.set('scope');
          else if (err.status === 400) this.submitError.set('validation');
          else this.submitError.set('network');
        },
      });
  }
}
