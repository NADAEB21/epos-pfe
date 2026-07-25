import { Component, computed, effect, inject, signal } from '@angular/core';
import { input } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { forkJoin } from 'rxjs';
import { ExamApiService } from '../../core/api/exam-api.service';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import { StationSummary, TypeStation, UserResponse } from '../../core/api/models';
import { ExamenWorkspaceStore } from './examen-workspace.store';
import { GrilleEditorComponent } from './grille-editor.component';

const TYPE_LABELS: Record<TypeStation, string> = {
  PRATIQUE: 'Pratique',
  THEORIQUE: 'Théorique',
};

const TYPES: TypeStation[] = ['PRATIQUE', 'THEORIQUE'];

/**
 * Stations & Grilles tab — station authoring + the pre-exam binding workflow
 * (ADR-0007: binding is pre-exam, in the workspace). Lists the exam's stations,
 * lets the responsable create / edit / delete stations and attach/detach
 * évaluateurs (PATCH replaces the whole list), and shows each station's grille
 * read-only on demand.
 *
 * CRUD + binding are gated on the exam being modifiable (BROUILLON or CONFIGURE,
 * mirroring Examen.isGrilleModifiable server-side), read from the route-scoped
 * ExamenWorkspaceStore. Once EN_COURS+ the tab is fully read-only — the backend
 * 403s any station mutation, so we don't surface actions it would reject.
 *
 * Évaluateur binding stays the ONLY place évaluateurs are set: the create/edit
 * station forms carry nom/type/description only and omit evaluateurIds, which the
 * backend treats as "leave bindings untouched" (StationServiceImpl.modifier).
 *
 * Two-call initial load: listStations() for the rows (the only source carrying
 * evaluateurIds + hasGrille) and the évaluateur directory to resolve ids → names.
 * Grille items are fetched lazily per station (GET /stations/{id}) when expanded,
 * to avoid an N+1 on first paint.
 */
@Component({
  selector: 'app-stations-grilles',
  standalone: true,
  imports: [ReactiveFormsModule, GrilleEditorComponent],
  template: `
    @if (loading()) {
      <div class="space-y-4 animate-pulse">
        <div class="h-28 rounded-xl bg-gray-200"></div>
        <div class="h-28 rounded-xl bg-gray-200"></div>
        <div class="h-28 rounded-xl bg-gray-200"></div>
      </div>
    } @else if (error()) {
      <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card">
        <p class="text-gray-700 mb-1">Impossible de charger les stations.</p>
        <p class="text-sm text-gray-500 mb-4">Verifiez votre connexion puis reessayez.</p>
        <button
          type="button"
          (click)="reload()"
          class="inline-flex items-center px-4 py-2 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark transition-colors"
        >
          Reessayer
        </button>
      </div>
    } @else {
      <!-- Add station -->
      @if (editable()) {
        <div class="mb-4">
          @if (showCreate()) {
            <form
              [formGroup]="createForm"
              (ngSubmit)="submitCreate()"
              novalidate
              class="rounded-xl bg-white border border-gray-200 shadow-card p-5 space-y-4"
            >
              <div class="text-sm font-semibold text-gray-900">Nouvelle station</div>
              <div class="grid grid-cols-1 sm:grid-cols-3 gap-4">
                <div class="sm:col-span-2">
                  <label class="block text-xs font-medium text-gray-700 mb-1">Nom</label>
                  <input
                    type="text"
                    formControlName="nom"
                    maxlength="150"
                    placeholder="Ex. Station 1 — Préparation magistrale"
                    class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
                  />
                  @if (createForm.controls.nom.touched && createForm.controls.nom.invalid) {
                    <p class="text-xs text-status-danger mt-1">Le nom est obligatoire (max 150).</p>
                  }
                </div>
                <div>
                  <label class="block text-xs font-medium text-gray-700 mb-1">Type</label>
                  <select
                    formControlName="type"
                    class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm bg-white focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
                  >
                    @for (t of types; track t) {
                      <option [value]="t">{{ typeLabel(t) }}</option>
                    }
                  </select>
                </div>
              </div>
              <div>
                <label class="block text-xs font-medium text-gray-700 mb-1">
                  Description <span class="text-gray-400">(optionnel)</span>
                </label>
                <textarea
                  formControlName="description"
                  rows="2"
                  maxlength="300"
                  class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
                ></textarea>
              </div>
              @if (createError()) {
                <p role="alert" class="text-xs text-status-danger">{{ createError() }}</p>
              }
              <div class="flex items-center justify-end gap-2">
                <button
                  type="button"
                  (click)="cancelCreate()"
                  class="px-3 py-1.5 rounded-lg border border-gray-300 text-gray-700 text-sm font-medium hover:bg-gray-50"
                >
                  Annuler
                </button>
                <button
                  type="submit"
                  [disabled]="createForm.invalid || creating()"
                  class="px-3 py-1.5 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  {{ creating() ? 'Ajout…' : 'Ajouter la station' }}
                </button>
              </div>
            </form>
          } @else {
            <button
              type="button"
              (click)="openCreate()"
              class="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark transition-colors"
            >
              + Ajouter une station
            </button>
          }
        </div>
      }

      @if (stations().length === 0) {
        <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card">
          <p class="text-gray-700 mb-1">Aucune station definie.</p>
          <p class="text-sm text-gray-500">
            @if (editable()) {
              Ajoutez votre premiere station pour commencer la configuration.
            } @else {
              Aucune station n'a ete configuree pour cet examen.
            }
          </p>
        </div>
      } @else {
        <ul class="space-y-4">
          @for (s of stations(); track s.id) {
            <li class="rounded-xl bg-white border border-gray-200 shadow-card p-5">
              <!-- header / inline edit -->
              @if (editingId() === s.id) {
                <form
                  [formGroup]="editForm"
                  (ngSubmit)="submitEdit(s)"
                  novalidate
                  class="space-y-4 mb-4"
                >
                  <div class="grid grid-cols-1 sm:grid-cols-3 gap-4">
                    <div class="sm:col-span-2">
                      <label class="block text-xs font-medium text-gray-700 mb-1">Nom</label>
                      <input
                        type="text"
                        formControlName="nom"
                        maxlength="150"
                        class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
                      />
                      @if (editForm.controls.nom.touched && editForm.controls.nom.invalid) {
                        <p class="text-xs text-status-danger mt-1">Le nom est obligatoire (max 150).</p>
                      }
                    </div>
                    <div>
                      <label class="block text-xs font-medium text-gray-700 mb-1">Type</label>
                      <select
                        formControlName="type"
                        class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm bg-white focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
                      >
                        @for (t of types; track t) {
                          <option [value]="t">{{ typeLabel(t) }}</option>
                        }
                      </select>
                    </div>
                  </div>
                  <div>
                    <label class="block text-xs font-medium text-gray-700 mb-1">
                      Description <span class="text-gray-400">(optionnel)</span>
                    </label>
                    <textarea
                      formControlName="description"
                      rows="2"
                      maxlength="300"
                      class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
                    ></textarea>
                  </div>
                  @if (editErrorId() === s.id && editError()) {
                    <p role="alert" class="text-xs text-status-danger">{{ editError() }}</p>
                  }
                  <div class="flex items-center justify-end gap-2">
                    <button
                      type="button"
                      (click)="cancelEdit()"
                      class="px-3 py-1.5 rounded-lg border border-gray-300 text-gray-700 text-sm font-medium hover:bg-gray-50"
                    >
                      Annuler
                    </button>
                    <button
                      type="submit"
                      [disabled]="editForm.invalid || savingStationId() === s.id"
                      class="px-3 py-1.5 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      {{ savingStationId() === s.id ? 'Enregistrement…' : 'Enregistrer' }}
                    </button>
                  </div>
                </form>
              } @else {
                <div class="flex items-start justify-between gap-3 mb-4">
                  <div class="min-w-0">
                    <div class="text-sm font-semibold text-gray-900 truncate">
                      <span class="text-gray-400">{{ s.ordre ?? '·' }}.</span>
                      {{ s.nom || 'Station ' + (s.ordre ?? '') }}
                      @if (s.type) {
                        <span class="ml-1 text-xs font-normal text-gray-400">{{ typeLabel(s.type) }}</span>
                      }
                    </div>
                    @if (s.description) {
                      <p class="text-xs text-gray-500 mt-0.5">{{ s.description }}</p>
                    }
                  </div>
                  <div class="flex items-center gap-2 shrink-0">
                    <span
                      class="text-xs px-2 py-0.5 rounded-full"
                      [class.bg-status-success]="evalCount(s) > 0"
                      [class.text-white]="evalCount(s) > 0"
                      [class.bg-gray-100]="evalCount(s) === 0"
                      [class.text-gray-400]="evalCount(s) === 0"
                      >{{ evalLabel(s) }}</span
                    >
                    @if (editable()) {
                      <button
                        type="button"
                        (click)="openEdit(s)"
                        class="text-xs text-gray-500 hover:text-brand"
                      >
                        Modifier
                      </button>
                      <button
                        type="button"
                        (click)="askDelete(s)"
                        class="text-xs text-gray-500 hover:text-status-danger"
                      >
                        Supprimer
                      </button>
                    }
                  </div>
                </div>

                @if (confirmDeleteId() === s.id) {
                  <div
                    class="flex items-center justify-between gap-3 rounded-lg bg-red-50 border border-red-200 px-3 py-2 mb-4"
                  >
                    <p class="text-sm text-status-danger">
                      Supprimer cette station ? Sa grille sera aussi supprimee.
                    </p>
                    <div class="flex items-center gap-2 shrink-0">
                      <button
                        type="button"
                        (click)="cancelDelete()"
                        [disabled]="deletingId() === s.id"
                        class="px-3 py-1 rounded-lg border border-gray-300 text-gray-700 text-xs font-medium hover:bg-gray-50 disabled:opacity-40"
                      >
                        Annuler
                      </button>
                      <button
                        type="button"
                        (click)="confirmDelete(s)"
                        [disabled]="deletingId() === s.id"
                        class="px-3 py-1 rounded-lg bg-status-danger text-white text-xs font-medium hover:opacity-90 disabled:opacity-40"
                      >
                        {{ deletingId() === s.id ? 'Suppression…' : 'Supprimer' }}
                      </button>
                    </div>
                  </div>
                  @if (deleteErrorId() === s.id) {
                    <p class="text-xs text-status-danger mb-3">Echec de la suppression. Reessayez.</p>
                  }
                }
              }

              <!-- évaluateurs -->
              <div class="mb-4">
                <div class="text-xs text-gray-400 mb-2">Evaluateurs</div>
                <div class="flex flex-wrap items-center gap-2">
                  @for (id of s.evaluateurIds ?? []; track id) {
                    <span
                      class="inline-flex items-center gap-1.5 text-xs bg-brand-50 text-brand-dark px-2.5 py-1 rounded-full"
                    >
                      {{ evalName(id) }}
                      @if (editable()) {
                        <button
                          type="button"
                          [disabled]="savingId() === s.id"
                          (click)="removeEvaluateur(s, id)"
                          class="text-brand-dark/60 hover:text-brand-dark disabled:opacity-40"
                          [attr.aria-label]="'Retirer ' + evalName(id)"
                        >
                          &times;
                        </button>
                      }
                    </span>
                  } @empty {
                    <span class="text-xs text-gray-400">Aucun evaluateur affecte</span>
                  }

                  <!-- picker (#163: évaluateurs pris par une autre station sont
                       listés désactivés — un évaluateur = une seule station/examen) -->
                  @if (editable()) {
                    @if (pickerHasOptions(s)) {
                      <select
                        #pick
                        [disabled]="savingId() === s.id"
                        (change)="addEvaluateur(s, pick.value); pick.value = ''"
                        class="text-xs border border-gray-200 rounded-full px-2.5 py-1 text-gray-600 bg-white disabled:opacity-40"
                      >
                        <option value="">+ ajouter</option>
                        @for (u of available(s); track u.id) {
                          <option [value]="u.id">{{ u.prenom }} {{ u.nom }}</option>
                        }
                        @for (e of takenElsewhere(s); track e.user.id) {
                          <option [value]="e.user.id" disabled>
                            {{ e.user.prenom }} {{ e.user.nom }} — déjà sur {{ e.station }}
                          </option>
                        }
                      </select>
                    }
                    @if (savingId() === s.id) {
                      <span class="text-xs text-gray-400">Enregistrement…</span>
                    }
                  }
                </div>
                @if (saveErrorId() === s.id) {
                  <p class="text-xs text-status-danger mt-1.5">
                    {{ saveError() ?? 'Echec de l\'enregistrement. Reessayez.' }}
                  </p>
                }
              </div>

              <!-- grille -->
              <div class="border-t border-gray-100 pt-3">
                @if (s.hasGrille || editable()) {
                  <button
                    type="button"
                    (click)="toggleGrille(s)"
                    class="flex items-center gap-1.5 text-sm text-brand hover:underline"
                  >
                    <span>{{ expandedId() === s.id ? '▾' : '▸' }}</span>
                    Grille d'evaluation
                    @if (!s.hasGrille) {
                      <span class="text-xs text-gray-400">(a creer)</span>
                    }
                  </button>
                  @if (expandedId() === s.id) {
                    <div class="mt-3">
                      <app-grille-editor
                        [stationId]="s.id"
                        [editable]="editable()"
                        [hasGrille]="!!s.hasGrille"
                        (existenceChanged)="onGrilleExistence(s, $event)"
                      />
                    </div>
                  }
                } @else {
                  <div class="flex items-center gap-2 text-sm text-gray-400">
                    <span class="w-2 h-2 rounded-full bg-status-warning"></span>
                    Aucune grille attachee a cette station.
                  </div>
                }
              </div>
            </li>
          }
        </ul>
      }
    }
  `,
})
export class StationsGrillesComponent {
  private readonly examApi = inject(ExamApiService);
  private readonly directory = inject(DirectoryApiService);
  private readonly fb = inject(FormBuilder);
  private readonly store = inject(ExamenWorkspaceStore);

  /** Inherited from the parent examens/:id route via withComponentInputBinding(). */
  readonly id = input.required<string>();

  readonly types = TYPES;

  readonly stations = signal<StationSummary[]>([]);
  readonly evaluateurs = signal<UserResponse[]>([]);
  readonly loading = signal(true);
  readonly error = signal(false);

  /** Station CRUD is allowed only while the exam is BROUILLON or CONFIGURE. */
  readonly editable = computed(() => {
    const e = this.store.exam();
    return e ? e.statut === 'BROUILLON' || e.statut === 'CONFIGURE' : false;
  });

  // create
  readonly showCreate = signal(false);
  readonly creating = signal(false);
  readonly createError = signal<string | null>(null);
  readonly createForm = this.fb.nonNullable.group({
    nom: ['', [Validators.required, Validators.maxLength(150)]],
    type: ['PRATIQUE' as TypeStation, [Validators.required]],
    description: ['', [Validators.maxLength(300)]],
  });

  // edit
  readonly editingId = signal<number | null>(null);
  readonly savingStationId = signal<number | null>(null);
  readonly editErrorId = signal<number | null>(null);
  readonly editError = signal<string | null>(null);
  readonly editForm = this.fb.nonNullable.group({
    nom: ['', [Validators.required, Validators.maxLength(150)]],
    type: ['PRATIQUE' as TypeStation, [Validators.required]],
    description: ['', [Validators.maxLength(300)]],
  });

  // delete
  readonly confirmDeleteId = signal<number | null>(null);
  readonly deletingId = signal<number | null>(null);
  readonly deleteErrorId = signal<number | null>(null);

  // évaluateur binding
  readonly savingId = signal<number | null>(null);
  readonly saveErrorId = signal<number | null>(null);
  readonly saveError = signal<string | null>(null);

  readonly expandedId = signal<number | null>(null);

  /** id → directory entry, for resolving évaluateur names on chips. */
  private readonly evalMap = computed(() => {
    const m = new Map<number, UserResponse>();
    for (const u of this.evaluateurs()) m.set(u.id, u);
    return m;
  });

  constructor() {
    effect(() => {
      const examId = Number(this.id());
      if (!Number.isFinite(examId)) {
        this.error.set(true);
        this.loading.set(false);
        return;
      }
      this.load(examId);
    }, { allowSignalWrites: true });
  }

  reload(): void {
    this.load(Number(this.id()));
  }

  private load(examId: number): void {
    this.loading.set(true);
    this.error.set(false);
    this.expandedId.set(null);
    forkJoin({
      stations: this.examApi.listStations(examId),
      evaluateurs: this.directory.listUsers('EVALUATEUR'),
    }).subscribe({
      next: ({ stations, evaluateurs }) => {
        this.stations.set(stations);
        this.evaluateurs.set(evaluateurs);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  /** Refresh just the station rows (quiet — no skeleton). Used after a delete so
   *  the server-recomputed ordre is reflected without a full reload. */
  private refetchStations(): void {
    this.examApi.listStations(Number(this.id())).subscribe({
      next: (stations) => this.stations.set(stations),
    });
  }

  // ---- station CRUD -------------------------------------------------------

  openCreate(): void {
    this.createForm.reset({ nom: '', type: 'PRATIQUE', description: '' });
    this.createError.set(null);
    this.showCreate.set(true);
  }

  cancelCreate(): void {
    this.showCreate.set(false);
    this.createError.set(null);
  }

  submitCreate(): void {
    if (this.createForm.invalid || this.creating()) return;
    const raw = this.createForm.getRawValue();
    this.creating.set(true);
    this.createError.set(null);
    this.examApi
      .createStation(Number(this.id()), {
        nom: raw.nom.trim(),
        type: raw.type,
        description: raw.description.trim() || undefined,
      })
      .subscribe({
        next: (created) => {
          this.creating.set(false);
          this.showCreate.set(false);
          this.stations.update((list) => [...list, created]);
          this.store.reloadPrep(); // #185 — tick the workspace stepper
        },
        error: (err: HttpErrorResponse) => {
          this.creating.set(false);
          this.createError.set(this.mutationMessage(err));
        },
      });
  }

  openEdit(s: StationSummary): void {
    this.confirmDeleteId.set(null);
    this.editErrorId.set(null);
    this.editError.set(null);
    this.editForm.reset({
      nom: s.nom ?? '',
      type: s.type ?? 'PRATIQUE',
      description: s.description ?? '',
    });
    this.editingId.set(s.id);
  }

  cancelEdit(): void {
    this.editingId.set(null);
    this.editError.set(null);
  }

  submitEdit(s: StationSummary): void {
    if (this.editForm.invalid || this.savingStationId() === s.id) return;
    const raw = this.editForm.getRawValue();
    this.savingStationId.set(s.id);
    this.editErrorId.set(null);
    this.editError.set(null);
    // evaluateurIds omitted on purpose — the backend keeps the existing bindings.
    this.examApi
      .updateStation(s.id, {
        nom: raw.nom.trim(),
        type: raw.type,
        description: raw.description.trim() || undefined,
      })
      .subscribe({
        next: (updated) => {
          this.savingStationId.set(null);
          this.editingId.set(null);
          this.replaceStation(s.id, {
            nom: updated.nom,
            type: updated.type,
            description: updated.description,
          });
        },
        error: (err: HttpErrorResponse) => {
          this.savingStationId.set(null);
          this.editErrorId.set(s.id);
          this.editError.set(this.mutationMessage(err));
        },
      });
  }

  askDelete(s: StationSummary): void {
    this.editingId.set(null);
    this.deleteErrorId.set(null);
    this.confirmDeleteId.set(s.id);
  }

  cancelDelete(): void {
    this.confirmDeleteId.set(null);
    this.deleteErrorId.set(null);
  }

  confirmDelete(s: StationSummary): void {
    if (this.deletingId() === s.id) return;
    this.deletingId.set(s.id);
    this.deleteErrorId.set(null);
    this.examApi.deleteStation(s.id).subscribe({
      next: () => {
        this.deletingId.set(null);
        this.confirmDeleteId.set(null);
        // Drop locally for instant feedback, then refetch so the server's
        // recomputed ordre on the survivors is reflected.
        this.stations.update((list) => list.filter((x) => x.id !== s.id));
        this.refetchStations();
        this.store.reloadPrep(); // #185 — tick the workspace stepper
      },
      error: () => {
        this.deletingId.set(null);
        this.deleteErrorId.set(s.id);
      },
    });
  }

  /** Friendly message for a 4xx from a station mutation (duplicate nom, scope…). */
  private mutationMessage(err: HttpErrorResponse): string {
    if (err.status === 409 || err.status === 400) {
      return typeof err.error?.message === 'string'
        ? err.error.message
        : 'Requete invalide. Verifiez le nom (peut-etre deja utilise).';
    }
    if (err.status === 403) return "Vous n'avez pas les droits sur cet examen.";
    return 'Echec de l\'enregistrement. Reessayez.';
  }

  // ---- évaluateur binding -------------------------------------------------

  /**
   * Active évaluateurs pickable for this station: not already bound here AND not
   * bound to any OTHER station of the exam (#163 — one évaluateur = one station
   * per exam). Those taken elsewhere are surfaced separately (disabled) so the
   * responsable sees why they're unavailable rather than them silently vanishing.
   */
  available(s: StationSummary): UserResponse[] {
    const bound = new Set(s.evaluateurIds ?? []);
    const elsewhere = this.evaluateurStationMap(s.id);
    return this.evaluateurs()
      .filter((u) => u.isActive && !bound.has(u.id) && !elsewhere.has(u.id))
      .sort((a, b) => a.nom.localeCompare(b.nom));
  }

  /** Active évaluateurs already bound to another station of this exam, with the
   *  name of that station — rendered as disabled picker options (#163). */
  takenElsewhere(s: StationSummary): { user: UserResponse; station: string }[] {
    const bound = new Set(s.evaluateurIds ?? []);
    const elsewhere = this.evaluateurStationMap(s.id);
    return this.evaluateurs()
      .filter((u) => u.isActive && !bound.has(u.id) && elsewhere.has(u.id))
      .map((u) => ({ user: u, station: elsewhere.get(u.id)! }))
      .sort((a, b) => a.user.nom.localeCompare(b.user.nom));
  }

  /** Whether the picker should render at all (something to add or to explain). */
  pickerHasOptions(s: StationSummary): boolean {
    return this.available(s).length > 0 || this.takenElsewhere(s).length > 0;
  }

  /** évaluateur id → nom of the OTHER station (of THIS exam) it is bound to. */
  private evaluateurStationMap(excludeStationId: number): Map<number, string> {
    const m = new Map<number, string>();
    for (const st of this.stations()) {
      if (st.id === excludeStationId) continue;
      for (const id of st.evaluateurIds ?? []) {
        if (!m.has(id)) m.set(id, st.nom || `Station ${st.ordre ?? ''}`.trim());
      }
    }
    return m;
  }

  addEvaluateur(s: StationSummary, rawId: string): void {
    const id = Number(rawId);
    if (!Number.isFinite(id) || id === 0) return;
    if ((s.evaluateurIds ?? []).includes(id)) return;
    // Guard the conflict client-side (disabled options can't fire, but be safe);
    // the backend also 409s if a race slips through.
    if (this.evaluateurStationMap(s.id).has(id)) return;
    this.patchEvaluateurs(s, [...(s.evaluateurIds ?? []), id]);
  }

  removeEvaluateur(s: StationSummary, id: number): void {
    this.patchEvaluateurs(s, (s.evaluateurIds ?? []).filter((x) => x !== id));
  }

  private patchEvaluateurs(s: StationSummary, nextIds: number[]): void {
    this.savingId.set(s.id);
    this.saveErrorId.set(null);
    this.saveError.set(null);
    this.examApi.setStationEvaluateurs(s.id, nextIds).subscribe({
      next: (updated) => {
        // Refresh from the response rather than the optimistic guess — the
        // server is the source of truth for the bound list.
        this.replaceStation(s.id, { evaluateurIds: updated.evaluateurIds ?? nextIds });
        this.savingId.set(null);
        this.store.reloadPrep(); // #185 — tick the workspace stepper
      },
      error: (err: HttpErrorResponse) => {
        // #163: a 409 carries the conflicting station name — surface it verbatim
        // instead of the generic retry message.
        this.saveErrorId.set(s.id);
        this.saveError.set(this.mutationMessage(err));
        this.savingId.set(null);
      },
    });
  }

  private replaceStation(id: number, patch: Partial<StationSummary>): void {
    this.stations.update((list) => list.map((s) => (s.id === id ? { ...s, ...patch } : s)));
  }

  // ---- grille (delegated to <app-grille-editor>) --------------------------

  /** Expand/collapse a station's grille editor. The editor owns its own load,
   *  create, and item CRUD — this only toggles which station is open. */
  toggleGrille(s: StationSummary): void {
    this.expandedId.set(this.expandedId() === s.id ? null : s.id);
  }

  /** The editor created (true) or deleted (false) this station's grille; mirror
   *  it onto the row so hasGrille-driven UI (toggle label) stays in sync. */
  onGrilleExistence(s: StationSummary, exists: boolean): void {
    this.replaceStation(s.id, { hasGrille: exists });
    this.store.reloadPrep(); // #185 — tick the workspace stepper
  }

  // ---- labels -------------------------------------------------------------

  evalCount(s: StationSummary): number {
    return s.evaluateurIds?.length ?? 0;
  }

  evalLabel(s: StationSummary): string {
    const n = this.evalCount(s);
    return n === 0 ? 'Aucun evaluateur' : `${n} evaluateur(s)`;
  }

  evalName(id: number): string {
    const u = this.evalMap().get(id);
    return u ? `${u.prenom} ${u.nom}` : `Evaluateur #${id}`;
  }

  typeLabel(t: TypeStation): string {
    return TYPE_LABELS[t];
  }
}
