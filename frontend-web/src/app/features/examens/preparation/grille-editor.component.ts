import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ExamApiService } from '../../../core/api/exam-api.service';
import { GrilleDetail, GrilleItem, GrilleTemplate, TypeItem } from '../../../core/api/models';

const TYPE_ITEM_LABELS: Record<TypeItem, string> = {
  BINAIRE: 'Binaire',
  NUMERIQUE: 'Numérique',
};

const TYPE_ITEMS: TypeItem[] = ['BINAIRE', 'NUMERIQUE'];

/**
 * Grille editor for one station — the heaviest authoring surface. Hosted by
 * StationsGrillesComponent inside each station card, it owns the full lifecycle
 * of a station's single grille: create it (meta only), edit nom/noteMax, delete
 * it, and add / edit / delete its critères (items) with the BINAIRE vs NUMERIQUE
 * conditional valeurMax field.
 *
 * Server-truth choices (verified against GrilleServiceImpl, 2026-06-09):
 *  - A station holds at most ONE grille (unique constraint) → create is a single
 *    affordance that disappears once a grille exists.
 *  - Grilles are created meta-only. Grouped item creation (creerPourStation)
 *    bypasses both the NUMERIQUE valeurMax check and the pondération-sum check;
 *    only POST /items validates. So items are always added through the validated
 *    endpoint, one at a time.
 *  - `ordre` is server-assigned and re-sequenced on delete — never sent.
 *  - After any item mutation we re-GET the grille: ItemResponse carries no grille
 *    totals, and the server owns the recomputed ordre + ponderationValide flag.
 *  - The live "pondérations = noteMax" indicator mirrors the server
 *    `ponderationValide` flag (sum == noteMax within 0.001); the server itself
 *    only hard-blocks sum > noteMax, surfacing under-sum as the flag.
 *
 * All mutations are gated on [editable] (BROUILLON/CONFIGURE upstream); once
 * EN_COURS+ the editor renders read-only, matching the backend 403.
 */
@Component({
  selector: 'app-grille-editor',
  standalone: true,
  templateUrl: './grille-editor.component.html',
  imports: [ReactiveFormsModule, NgTemplateOutlet],
})
export class GrilleEditorComponent {
  private readonly examApi = inject(ExamApiService);
  private readonly fb = inject(FormBuilder);

  readonly stationId = input.required<number>();
  readonly editable = input.required<boolean>();
  /** Initial hint from the station row, so we only GET when a grille exists. */
  readonly hasGrille = input.required<boolean>();

  /** Fires when a grille is created (true) or deleted (false) so the parent can
   *  flip the station's hasGrille without a full reload. */
  readonly existenceChanged = output<boolean>();

  readonly typeItems = TYPE_ITEMS;

  readonly grille = signal<GrilleDetail | null>(null);
  readonly loading = signal(false);
  readonly loadError = signal(false);

  // create grille
  readonly creatingGrille = signal(false);
  readonly creating = signal(false);
  readonly createError = signal<string | null>(null);
  readonly createForm = this.fb.nonNullable.group({
    nom: ['', [Validators.required, Validators.maxLength(150)]],
    noteMax: [20, [Validators.required, Validators.min(1), Validators.max(100)]],
    description: ['', [Validators.maxLength(300)]],
  });

  // edit grille meta
  readonly editingMeta = signal(false);
  readonly savingMeta = signal(false);
  readonly metaError = signal<string | null>(null);
  readonly metaForm = this.fb.nonNullable.group({
    nom: ['', [Validators.required, Validators.maxLength(150)]],
    noteMax: [20, [Validators.required, Validators.min(1), Validators.max(100)]],
    description: ['', [Validators.maxLength(300)]],
  });

  // replace grille from scratch (#161, single-gesture create-or-replace via PUT)
  readonly replacingGrille = signal(false);
  readonly replacingBusy = signal(false);
  readonly replaceError = signal<string | null>(null);
  readonly replaceForm = this.fb.nonNullable.group({
    nom: ['', [Validators.required, Validators.maxLength(150)]],
    noteMax: [20, [Validators.required, Validators.min(1), Validators.max(100)]],
    description: ['', [Validators.maxLength(300)]],
  });

  // delete grille
  readonly confirmDeleteGrille = signal(false);
  readonly deletingGrille = signal(false);
  readonly grilleDeleteError = signal<string | null>(null);

  // item add / edit (one shared form)
  readonly addingItem = signal(false);
  readonly editingItemId = signal<number | null>(null);
  /** #160 — when set, the shared item form is adding a SUB-criterion under this
   *  parent item id (POST /items/{id}/sous-criteres) instead of a top-level one.
   *  Mutually exclusive with addingItem / editingItemId (the open* helpers below
   *  clear the others), so the single shared form only ever renders in one place. */
  readonly addingSubForItemId = signal<number | null>(null);
  readonly savingItem = signal(false);
  readonly itemError = signal<string | null>(null);
  readonly itemFormGroup = this.fb.nonNullable.group({
    libelle: ['', [Validators.required, Validators.maxLength(300)]],
    type: ['BINAIRE' as TypeItem, [Validators.required]],
    // #418 — quart de point autorisé (aligné sur ItemRequest côté exam-service).
    ponderation: [1, [Validators.required, Validators.min(0.25), Validators.max(20)]],
    valeurMax: [null as number | null],
    categorie: ['', [Validators.maxLength(100)]],
    // Réponse attendue / corrigé (#162) — champ libre optionnel, tous types.
    conditionsAttendues: ['', [Validators.maxLength(1000)]],
  });

  // item delete
  readonly confirmDeleteItemId = signal<number | null>(null);
  readonly deletingItemId = signal<number | null>(null);
  readonly itemDeleteError = signal<string | null>(null);

  // save current grille as a template
  readonly savingTemplate = signal(false);
  readonly savingTemplateBusy = signal(false);
  readonly templateError = signal<string | null>(null);
  readonly templateSaved = signal<string | null>(null);
  readonly templateNameForm = this.fb.nonNullable.group({
    nom: ['', [Validators.required, Validators.maxLength(150)]],
  });

  // apply a template onto this station
  readonly applyOpen = signal(false);
  readonly templates = signal<GrilleTemplate[] | null>(null);
  readonly templatesLoading = signal(false);
  readonly templatesLoadError = signal(false);
  readonly selectedTemplate = signal<GrilleTemplate | null>(null);
  readonly applying = signal(false);
  readonly applyError = signal<string | null>(null);

  /** Live pondération sum, mirrored client-side from the committed items. */
  readonly sum = computed(() => {
    const items = this.grille()?.items ?? [];
    const total = items.reduce((acc, it) => acc + (it.ponderation ?? 0), 0);
    // Trim float noise (0.5 steps) for display.
    return Math.round(total * 100) / 100;
  });

  /** Mirror the server ponderationValide flag; fall back to a local compute. */
  readonly valid = computed(() => {
    const g = this.grille();
    if (!g) return false;
    if (g.ponderationValide != null) return g.ponderationValide;
    return Math.abs(this.sum() - (g.noteMax ?? 0)) < 0.001;
  });

  /** Warn when the meta form's noteMax drops below the existing pondération sum
   *  — the backend won't reject it (modifier skips the sum re-check). */
  readonly metaNoteMaxBelowSum = computed(() => {
    if (!this.editingMeta()) return false;
    const nm = Number(this.metaForm.controls.noteMax.value);
    return Number.isFinite(nm) && this.sum() > nm;
  });

  /** Cross-field: for NUMERIQUE, valeurMax must be ≤ ponderation (server rule). */
  readonly valeurMaxExceedsPonderation = computed(() => {
    const v = this.itemFormSignal();
    if (v.type !== 'NUMERIQUE') return false;
    const vm = Number(v.valeurMax);
    const p = Number(v.ponderation);
    return Number.isFinite(vm) && Number.isFinite(p) && vm > p;
  });

  /** Snapshot of the item form's value as a signal, to drive computed()s that
   *  must react to value changes (valueChanges → signal via a small effect). */
  private readonly itemFormSignal = signal(this.itemFormGroup.getRawValue());

  /** Type-aware hint for the free-text answer key: a numeric criterion usually
   *  expects a value/interval/tolerance, a binary one a compound or observation. */
  readonly answerPlaceholder = computed(() =>
    this.itemFormSignal().type === 'NUMERIQUE'
      ? 'ex. 4,5–5,5 mg/L, ou 300 mg ± 5 %'
      : 'ex. Paracétamol identifié, coloration violette observée',
  );

  constructor() {
    // Load the grille lazily the first time the editor is mounted for a station
    // that already has one. Re-runs if the bound stationId changes.
    effect(
      () => {
        const sid = this.stationId();
        if (this.hasGrille() && this.grille() === null && !this.loadError()) {
          this.loadGrilleFor(sid);
        }
      },
      { allowSignalWrites: true },
    );

    // Keep itemFormSignal in lockstep with the form, and toggle valeurMax's
    // required validator on the BINAIRE/NUMERIQUE switch.
    this.itemFormGroup.valueChanges.subscribe(() => {
      this.itemFormSignal.set(this.itemFormGroup.getRawValue());
    });
    this.itemFormGroup.controls.type.valueChanges.subscribe((type) => {
      const vm = this.itemFormGroup.controls.valeurMax;
      if (type === 'NUMERIQUE') {
        vm.setValidators([Validators.required, Validators.min(0.01)]);
      } else {
        vm.clearValidators();
        vm.setValue(null, { emitEvent: false });
      }
      vm.updateValueAndValidity({ emitEvent: false });
    });
  }

  loadGrille(): void {
    this.loadGrilleFor(this.stationId());
  }

  private loadGrilleFor(stationId: number): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.examApi.getStationGrille(stationId).subscribe({
      next: (grille) => {
        this.grille.set(grille ?? null);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.loadError.set(true);
      },
    });
  }

  /** Quiet refetch after an item mutation, to pick up server ordre + totals. */
  private refetchGrille(): void {
    this.examApi.getStationGrille(this.stationId()).subscribe({
      next: (grille) => this.grille.set(grille ?? null),
    });
  }

  // ---- create grille ------------------------------------------------------

  openCreate(): void {
    this.createForm.reset({ nom: '', noteMax: 20, description: '' });
    this.createError.set(null);
    this.creatingGrille.set(true);
  }

  cancelCreate(): void {
    this.creatingGrille.set(false);
    this.createError.set(null);
  }

  submitCreate(): void {
    if (this.createForm.invalid || this.creating()) return;
    const raw = this.createForm.getRawValue();
    this.creating.set(true);
    this.createError.set(null);
    this.examApi
      .createStationGrille(this.stationId(), {
        nom: raw.nom.trim(),
        noteMax: Number(raw.noteMax),
        description: raw.description.trim() || undefined,
      })
      .subscribe({
        next: (grille) => {
          this.creating.set(false);
          this.creatingGrille.set(false);
          this.grille.set(grille);
          this.existenceChanged.emit(true);
        },
        error: (err: HttpErrorResponse) => {
          this.creating.set(false);
          this.createError.set(this.mutationMessage(err));
        },
      });
  }

  // ---- edit grille meta ---------------------------------------------------

  openMeta(): void {
    const g = this.grille();
    if (!g) return;
    this.metaForm.reset({
      nom: g.nom ?? '',
      noteMax: g.noteMax ?? 20,
      description: g.description ?? '',
    });
    this.metaError.set(null);
    this.editingMeta.set(true);
  }

  cancelMeta(): void {
    this.editingMeta.set(false);
    this.metaError.set(null);
  }

  submitMeta(): void {
    const g = this.grille();
    if (!g || this.metaForm.invalid || this.savingMeta()) return;
    const raw = this.metaForm.getRawValue();
    this.savingMeta.set(true);
    this.metaError.set(null);
    this.examApi
      .updateGrille(g.id, {
        nom: raw.nom.trim(),
        noteMax: Number(raw.noteMax),
        description: raw.description.trim() || undefined,
      })
      .subscribe({
        next: (updated) => {
          this.savingMeta.set(false);
          this.editingMeta.set(false);
          this.grille.set(updated);
        },
        error: (err: HttpErrorResponse) => {
          this.savingMeta.set(false);
          this.metaError.set(this.mutationMessage(err));
        },
      });
  }

  // ---- replace grille from scratch (#161) ---------------------------------

  openReplace(): void {
    // Start blank — a "remplacer" means a fresh grille, not an edit of the old one.
    this.editingMeta.set(false);
    this.confirmDeleteGrille.set(false);
    this.replaceForm.reset({ nom: '', noteMax: 20, description: '' });
    this.replaceError.set(null);
    this.replacingGrille.set(true);
  }

  cancelReplace(): void {
    this.replacingGrille.set(false);
    this.replaceError.set(null);
  }

  submitReplace(): void {
    if (this.replaceForm.invalid || this.replacingBusy()) return;
    const raw = this.replaceForm.getRawValue();
    this.replacingBusy.set(true);
    this.replaceError.set(null);
    // One idempotent PUT: overwrites meta + purges old critères in place. No
    // delete→create, so no unique station_id conflict is possible.
    this.examApi
      .replaceStationGrille(this.stationId(), {
        nom: raw.nom.trim(),
        noteMax: Number(raw.noteMax),
        description: raw.description.trim() || undefined,
      })
      .subscribe({
        next: (grille) => {
          this.replacingBusy.set(false);
          this.replacingGrille.set(false);
          this.grille.set(grille);
          // Still exists (replaced, not deleted) — keep the parent row in sync.
          this.existenceChanged.emit(true);
        },
        error: (err: HttpErrorResponse) => {
          this.replacingBusy.set(false);
          this.replaceError.set(this.mutationMessage(err));
        },
      });
  }

  // ---- delete grille ------------------------------------------------------

  askDeleteGrille(): void {
    this.grilleDeleteError.set(null);
    this.confirmDeleteGrille.set(true);
  }

  deleteGrille(): void {
    const g = this.grille();
    if (!g || this.deletingGrille()) return;
    this.deletingGrille.set(true);
    this.grilleDeleteError.set(null);
    this.examApi.deleteGrille(g.id).subscribe({
      next: () => {
        this.deletingGrille.set(false);
        this.confirmDeleteGrille.set(false);
        this.grille.set(null);
        this.loadError.set(false);
        this.existenceChanged.emit(false);
      },
      error: (err: HttpErrorResponse) => {
        this.deletingGrille.set(false);
        this.grilleDeleteError.set(this.mutationMessage(err));
      },
    });
  }

  // ---- items --------------------------------------------------------------

  openItemAdd(): void {
    this.editingItemId.set(null);
    this.addingSubForItemId.set(null);
    this.itemError.set(null);
    this.itemFormGroup.reset({
      libelle: '',
      type: 'BINAIRE',
      ponderation: 1,
      valeurMax: null,
      categorie: '',
      conditionsAttendues: '',
    });
    this.addingItem.set(true);
  }

  /** #160 — open the shared form to add a sub-criterion under a top-level item.
   *  Defaults the pondération to the parent's remaining (unallocated) points so a
   *  full split is the path of least resistance. */
  openSubAdd(parent: GrilleItem): void {
    this.addingItem.set(false);
    this.editingItemId.set(null);
    this.itemError.set(null);
    const remaining = Math.max(0.5, (parent.ponderation ?? 0) - this.subSum(parent));
    this.itemFormGroup.reset({
      libelle: '',
      type: 'BINAIRE',
      ponderation: remaining,
      valeurMax: null,
      categorie: '',
      conditionsAttendues: '',
    });
    this.addingSubForItemId.set(parent.id);
  }

  openItemEdit(it: GrilleItem): void {
    this.addingItem.set(false);
    this.addingSubForItemId.set(null);
    this.itemError.set(null);
    this.itemFormGroup.reset({
      libelle: it.libelle ?? '',
      type: it.type ?? 'BINAIRE',
      ponderation: it.ponderation ?? 1,
      valeurMax: it.valeurMax ?? null,
      categorie: it.categorie ?? '',
      conditionsAttendues: it.conditionsAttendues ?? '',
    });
    this.editingItemId.set(it.id);
  }

  cancelItem(): void {
    this.addingItem.set(false);
    this.editingItemId.set(null);
    this.addingSubForItemId.set(null);
    this.itemError.set(null);
  }

  submitItem(): void {
    const g = this.grille();
    if (!g || this.itemFormGroup.invalid || this.valeurMaxExceedsPonderation() || this.savingItem()) {
      return;
    }
    const raw = this.itemFormGroup.getRawValue();
    const body = {
      libelle: raw.libelle.trim(),
      type: raw.type,
      ponderation: Number(raw.ponderation),
      valeurMax: raw.type === 'NUMERIQUE' ? Number(raw.valeurMax) : null,
      categorie: raw.categorie.trim() || undefined,
      conditionsAttendues: raw.conditionsAttendues.trim() || null,
    };
    this.savingItem.set(true);
    this.itemError.set(null);
    const editId = this.editingItemId();
    const subParentId = this.addingSubForItemId();
    const req = editId
      ? this.examApi.updateGrilleItem(editId, body)
      : subParentId != null
        ? this.examApi.ajouterSousCritere(subParentId, body)
        : this.examApi.createGrilleItem(g.id, body);
    req.subscribe({
      next: () => {
        this.savingItem.set(false);
        this.addingItem.set(false);
        this.editingItemId.set(null);
        this.addingSubForItemId.set(null);
        this.refetchGrille();
      },
      error: (err: HttpErrorResponse) => {
        this.savingItem.set(false);
        this.itemError.set(this.mutationMessage(err));
      },
    });
  }

  // ---- sub-criteria helpers (#160) ---------------------------------------

  /** Σ pondération of a critère's sub-criteria (0 when it has none), display-rounded. */
  subSum(it: GrilleItem): number {
    const total = (it.sousCriteres ?? []).reduce((acc, c) => acc + (c.ponderation ?? 0), 0);
    return Math.round(total * 100) / 100;
  }

  /** A decomposed critère is "complete" only when its children's pondération sums
   *  exactly to its own — otherwise the unallocated points expose fewer notable
   *  points than the parent is worth (scoring only grades leaves). Mirrors the
   *  server ItemEvaluation.isPonderationEnfantsValide, now folded into the grille
   *  ponderationValide flag. */
  subComplete(it: GrilleItem): boolean {
    if (!it.sousCriteres?.length) return true;
    return Math.abs(this.subSum(it) - (it.ponderation ?? 0)) < 0.001;
  }

  askDeleteItem(it: GrilleItem): void {
    this.itemDeleteError.set(null);
    this.confirmDeleteItemId.set(it.id);
  }

  deleteItem(it: GrilleItem): void {
    if (this.deletingItemId() === it.id) return;
    this.deletingItemId.set(it.id);
    this.itemDeleteError.set(null);
    this.examApi.deleteGrilleItem(it.id).subscribe({
      next: () => {
        this.deletingItemId.set(null);
        this.confirmDeleteItemId.set(null);
        this.refetchGrille();
      },
      error: (err: HttpErrorResponse) => {
        this.deletingItemId.set(null);
        this.itemDeleteError.set(this.mutationMessage(err));
      },
    });
  }

  // ---- save as template ---------------------------------------------------

  openSaveTemplate(): void {
    const g = this.grille();
    this.applyOpen.set(false);
    this.templateSaved.set(null);
    this.templateError.set(null);
    // Seed the name with the grille's own nom — a sensible default the user edits.
    this.templateNameForm.reset({ nom: g?.nom ?? '' });
    this.savingTemplate.set(true);
  }

  cancelSaveTemplate(): void {
    this.savingTemplate.set(false);
    this.templateError.set(null);
  }

  submitSaveTemplate(): void {
    const g = this.grille();
    if (!g || this.templateNameForm.invalid || this.savingTemplateBusy()) return;
    const nom = this.templateNameForm.getRawValue().nom.trim();
    this.savingTemplateBusy.set(true);
    this.templateError.set(null);
    this.examApi.saveGrilleAsTemplate(g.id, nom).subscribe({
      next: (tpl) => {
        this.savingTemplateBusy.set(false);
        this.savingTemplate.set(false);
        this.templateSaved.set(tpl.nom);
        // Invalidate the cached picker list so a later apply sees the new model.
        this.templates.set(null);
      },
      error: (err: HttpErrorResponse) => {
        this.savingTemplateBusy.set(false);
        this.templateError.set(this.mutationMessage(err));
      },
    });
  }

  // ---- apply a template ---------------------------------------------------

  openApply(): void {
    this.savingTemplate.set(false);
    this.templateSaved.set(null);
    this.selectedTemplate.set(null);
    this.applyError.set(null);
    this.applyOpen.set(true);
    if (this.templates() === null) this.loadTemplates();
  }

  cancelApply(): void {
    this.applyOpen.set(false);
    this.selectedTemplate.set(null);
    this.applyError.set(null);
  }

  private loadTemplates(): void {
    this.templatesLoading.set(true);
    this.templatesLoadError.set(false);
    this.examApi.listGrilleTemplates().subscribe({
      next: (list) => {
        this.templates.set(list);
        this.templatesLoading.set(false);
      },
      error: () => {
        this.templatesLoading.set(false);
        this.templatesLoadError.set(true);
      },
    });
  }

  chooseTemplate(rawId: string): void {
    const id = Number(rawId);
    if (!Number.isFinite(id) || id === 0) return;
    const t = (this.templates() ?? []).find((x) => x.id === id) ?? null;
    this.applyError.set(null);
    this.selectedTemplate.set(t);
  }

  confirmApply(): void {
    const t = this.selectedTemplate();
    if (!t || this.applying()) return;
    this.applying.set(true);
    this.applyError.set(null);
    this.examApi.applyTemplateToStation(t.id, this.stationId()).subscribe({
      next: () => {
        this.applying.set(false);
        this.applyOpen.set(false);
        this.selectedTemplate.set(null);
        this.loadError.set(false);
        // The backend deleted + recreated the grille; re-GET it and tell the parent
        // a grille now exists so the station row's hasGrille flips.
        this.refetchGrille();
        this.existenceChanged.emit(true);
      },
      error: (err: HttpErrorResponse) => {
        this.applying.set(false);
        this.applyError.set(this.mutationMessage(err));
      },
    });
  }

  // ---- helpers ------------------------------------------------------------

  /** Surface the backend BusinessException message (sum overflow, valeurMax…). */
  private mutationMessage(err: HttpErrorResponse): string {
    if (err.status === 400 || err.status === 409) {
      return typeof err.error?.message === 'string'
        ? err.error.message
        : 'Requête invalide. Vérifiez les valeurs saisies.';
    }
    if (err.status === 403) return "Vous n'avez pas les droits sur cet examen.";
    if (err.status === 404) return 'Ressource introuvable. Rechargez la page.';
    return "Échec de l'enregistrement. Réessayez.";
  }

  typeItemLabel(t: TypeItem | undefined): string {
    return t ? TYPE_ITEM_LABELS[t] : '';
  }
}
