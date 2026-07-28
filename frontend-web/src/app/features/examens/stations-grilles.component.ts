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
  templateUrl: './stations-grilles.component.html',
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
