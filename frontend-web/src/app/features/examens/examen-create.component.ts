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
  template: `
    <div class="max-w-2xl">
      <header class="mb-6">
        <a [routerLink]="['/examens']" class="text-sm text-gray-500 hover:text-brand">&larr; Mes examens</a>
        <h1 class="text-2xl font-semibold text-gray-900 mt-1">Nouvel examen</h1>
        <p class="text-sm text-gray-500">
          Créez l'examen, puis configurez stations, grilles et étudiants dans l'espace de travail.
        </p>
      </header>

      <form
        [formGroup]="form"
        (ngSubmit)="onSubmit()"
        novalidate
        class="rounded-xl bg-white border border-gray-200 shadow-card p-6 space-y-5"
      >
        <!-- Nom -->
        <div>
          <label for="nom" class="block text-sm font-medium text-gray-700 mb-1">Nom de l'examen</label>
          <input
            id="nom"
            type="text"
            formControlName="nom"
            maxlength="150"
            placeholder="Ex. Examen pratique — Session juin"
            class="w-full rounded-lg border border-gray-300 px-3 py-2 focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
          />
          @if (form.controls.nom.touched && form.controls.nom.invalid) {
            <p class="text-xs text-status-danger mt-1">Le nom est obligatoire (3 à 150 caractères).</p>
          }
        </div>

        <!-- Matière -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Matière</label>
          @if (matiereOptions().length > 1) {
            <select
              formControlName="matiereId"
              class="w-full rounded-lg border border-gray-300 px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
            >
              <option [ngValue]="0" disabled>Choisir une matière…</option>
              @for (m of matiereOptions(); track m.id) {
                <option [ngValue]="m.id">{{ m.libelle }}</option>
              }
            </select>
            @if (form.controls.matiereId.touched && form.controls.matiereId.invalid) {
              <p class="text-xs text-status-danger mt-1">Sélectionnez une matière.</p>
            }
          } @else {
            <p class="rounded-lg border border-gray-200 bg-gray-50 px-3 py-2 text-sm text-gray-700">
              {{ soleMatiereLabel() }}
            </p>
          }
        </div>

        <!-- Date + heure de début -->
        <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label for="dateExamen" class="block text-sm font-medium text-gray-700 mb-1">Date de l'examen</label>
            <input
              id="dateExamen"
              type="date"
              formControlName="dateExamen"
              class="w-full rounded-lg border border-gray-300 px-3 py-2 focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
            />
            @if (form.controls.dateExamen.touched && form.controls.dateExamen.invalid) {
              <p class="text-xs text-status-danger mt-1">La date est obligatoire.</p>
            }
          </div>
          <div>
            <label for="heureDebut" class="block text-sm font-medium text-gray-700 mb-1">Heure de début</label>
            <input
              id="heureDebut"
              type="time"
              formControlName="heureDebut"
              class="w-full rounded-lg border border-gray-300 px-3 py-2 focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
            />
            <p class="text-xs text-gray-400 mt-1">Début du premier créneau de rotation.</p>
          </div>
        </div>

        <!-- Durée / station + étudiants / station -->
        <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label for="duree" class="block text-sm font-medium text-gray-700 mb-1">
              Durée par station <span class="text-gray-400">(min)</span>
            </label>
            <input
              id="duree"
              type="number"
              formControlName="dureeStationMin"
              min="1"
              max="180"
              placeholder="15"
              class="w-full rounded-lg border border-gray-300 px-3 py-2 focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
            />
            @if (form.controls.dureeStationMin.invalid) {
              <p class="text-xs text-status-danger mt-1">Entre 1 et 180 minutes.</p>
            }
          </div>
          <div>
            <label for="nbEtu" class="block text-sm font-medium text-gray-700 mb-1">
              Étudiants par station
            </label>
            <input
              id="nbEtu"
              type="number"
              formControlName="nbEtudiantsParStation"
              min="1"
              max="10"
              placeholder="4"
              class="w-full rounded-lg border border-gray-300 px-3 py-2 focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
            />
            @if (form.controls.nbEtudiantsParStation.invalid) {
              <p class="text-xs text-status-danger mt-1">Entre 1 et 10 étudiants.</p>
            }
          </div>
        </div>
        <p class="text-xs text-gray-400 -mt-2">Laissez vide pour les valeurs par défaut (15 min, 4 étudiants).</p>

        <!-- Description -->
        <div>
          <label for="description" class="block text-sm font-medium text-gray-700 mb-1">
            Description <span class="text-gray-400">(optionnel)</span>
          </label>
          <textarea
            id="description"
            formControlName="description"
            rows="3"
            maxlength="500"
            class="w-full rounded-lg border border-gray-300 px-3 py-2 focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
          ></textarea>
        </div>

        @switch (submitError()) {
          @case ('scope') {
            <div role="alert" class="rounded-lg bg-red-50 border border-red-200 px-3 py-2 text-sm text-status-danger">
              Vous n'êtes pas responsable de la matière sélectionnée.
            </div>
          }
          @case ('validation') {
            <div role="alert" class="rounded-lg bg-red-50 border border-red-200 px-3 py-2 text-sm text-status-danger">
              Certains champs sont invalides. Vérifiez le formulaire.
            </div>
          }
          @case ('network') {
            <div role="alert" class="rounded-lg bg-amber-50 border border-amber-200 px-3 py-2 text-sm text-amber-800">
              Erreur de connexion. Vérifiez votre réseau et réessayez.
            </div>
          }
        }

        <div class="flex items-center justify-end gap-3 pt-2">
          <a
            [routerLink]="['/examens']"
            class="px-4 py-2 rounded-lg border border-gray-300 text-gray-700 text-sm font-medium hover:bg-gray-50 transition-colors"
            >Annuler</a
          >
          <button
            type="submit"
            [disabled]="form.invalid || submitting()"
            class="px-4 py-2 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {{ submitting() ? 'Création…' : "Créer l'examen" }}
          </button>
        </div>
      </form>
    </div>
  `,
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

  /** Picker options; empty until the catalogue loads. */
  readonly matiereOptions = computed(() => this.scopedMatieres());

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
