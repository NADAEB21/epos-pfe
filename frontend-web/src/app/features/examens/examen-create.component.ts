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

  /**
   * #303 — le message NOMINATIF du backend (« la matière « X » a été retirée… »), affiché
   * tel quel quand il existe : il dit POURQUOI et QUOI FAIRE, là où le générique
   * « certains champs sont invalides » enverrait le responsable vérifier ses champs.
   */
  readonly serverMessage = signal<string | null>(null);

  /** All matières resolved from the catalogue, intersected with the JWT scope. */
  private readonly scopedMatieres = signal<MatiereResponse[]>([]);

  /**
   * #304 — vrai une fois le catalogue chargé : on ne peut affirmer « votre matière
   * est retirée » qu'après avoir VU le catalogue. Avant (ou sur échec de chargement),
   * la branche dégradée garde le libellé neutre historique.
   */
  readonly catalogueLoaded = signal(false);

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

  /**
   * #304 — les matières de la portée que l'administration a RETIRÉES : c'est ce que la
   * branche zéro-option doit NOMMER (« Pharmacognosie a été retirée »), au lieu d'afficher
   * le libellé de la matière fermée comme si l'examen allait y être créé.
   */
  readonly matieresRetirees = computed(() => this.scopedMatieres().filter((m) => !m.active));

  /**
   * Libellé de la branche DÉGRADÉE (catalogue non chargé) uniquement. #304 — l'ancienne
   * version lisait la liste non filtrée et nommait la matière retirée en tête de portée.
   */
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
        this.catalogueLoaded.set(true);

        // #303/#304 — corriger le pré-remplissage à la lumière du catalogue :
        // - la matière pré-remplie est RETIRÉE → on la retire du formulaire (le
        //   responsable mono-matière ne doit plus créer « normalement » dans une
        //   matière fermée — le backend refuserait désormais, mais l'écran ne doit
        //   pas l'y envoyer) ;
        // - exactement UNE option active et rien de choisi → on la fixe, le
        //   sélecteur à une seule entrée reste affiché (l'écran dit où l'examen ira).
        const actives = this.matiereOptions();
        const current = this.form.controls.matiereId.value;
        const currentEstRetiree = current > 0 && !actives.some((m) => m.id === current);
        if (currentEstRetiree) {
          this.form.controls.matiereId.setValue(0);
        }
        if (actives.length === 1 && this.form.controls.matiereId.value === 0) {
          this.form.controls.matiereId.setValue(actives[0].id);
        }
      },
      // Catalogue is for labels/picker only — a single-matière responsable can
      // still submit with the scoped id even if this fails. catalogueLoaded reste
      // false : la branche dégradée garde le libellé neutre, jamais l'affirmation
      // « matière retirée » sans avoir vu le catalogue.
      error: () => this.scopedMatieres.set([]),
    });
  }

  onSubmit(): void {
    if (this.form.invalid || this.submitting()) return;

    const raw = this.form.getRawValue();
    this.submitError.set(null);
    this.serverMessage.set(null);
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
          else if (err.status === 400) {
            this.submitError.set('validation');
            // #303 — le refus nominatif du backend prime sur le générique.
            const message = err.error?.message;
            this.serverMessage.set(typeof message === 'string' && message ? message : null);
          } else this.submitError.set('network');
        },
      });
  }
}
