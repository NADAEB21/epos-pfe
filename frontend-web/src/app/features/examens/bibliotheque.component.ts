import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { forkJoin } from 'rxjs';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ExamenResponse, GrilleTemplate, StationSummary } from '../../core/api/models';

/**
 * Bibliothèque de grilles — the global, shared template library.
 *
 * Backend truth (verified against GrilleTemplateController / GrilleTemplateServiceImpl,
 * 2026-06-09): GET /templates/grilles is GLOBAL (no matière filter), so a responsable
 * browses every template here. Applying one (POST /templates/grilles/{tid}/appliquer/
 * stations/{sid}) is a FULL REPLACE — the backend deletes the target station's grille
 * and recreates it from the template — and is gated to a modifiable exam
 * (BROUILLON/CONFIGURE, else 400) + the caller's matière scope (else 403). So the apply
 * picker only offers BROUILLON/CONFIGURE exams and confirms before overwriting.
 *
 * Standalone template create + DELETE are SUPER_ADMIN-only, so this responsable surface
 * deliberately has NO delete affordance — saving happens from the grille editor, deleting
 * (if ever) from the admin console.
 */
@Component({
  selector: 'app-bibliotheque',
  standalone: true,
  imports: [RouterLink],
  template: `
    <header class="mb-6">
      <h1 class="text-2xl font-semibold text-gray-900">Bibliothèque de grilles</h1>
      <p class="text-sm text-gray-500 mt-1">
        Modèles de grilles réutilisables, partagés entre toutes les matières. Enregistrez une
        grille comme modèle depuis l'éditeur d'une station, puis appliquez-la ici à une autre
        station.
      </p>
    </header>

    @if (loading()) {
      <div class="space-y-3 animate-pulse">
        <div class="h-24 rounded-xl bg-gray-200"></div>
        <div class="h-24 rounded-xl bg-gray-200"></div>
      </div>
    } @else if (loadError()) {
      <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card">
        <p class="text-gray-700 mb-3">Impossible de charger la bibliothèque.</p>
        <button type="button" (click)="load()" class="text-sm text-brand hover:underline">
          Réessayer
        </button>
      </div>
    } @else if (templates().length === 0) {
      <!-- empty state -->
      <div class="rounded-xl bg-white border border-gray-200 p-10 text-center shadow-card">
        <div class="text-gray-700 font-medium mb-1">Aucun modèle pour le moment</div>
        <p class="text-sm text-gray-500 mb-5 max-w-md mx-auto">
          Ouvrez la grille d'une station dans l'onglet « Stations &amp; Grilles » d'un examen,
          puis utilisez « Enregistrer comme modèle » pour alimenter cette bibliothèque.
        </p>
        <a
          [routerLink]="['/examens']"
          class="inline-flex items-center px-4 py-2 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark transition-colors"
        >
          Voir mes examens
        </a>
      </div>
    } @else {
      <ul class="space-y-4">
        @for (t of templates(); track t.id) {
          <li class="rounded-xl bg-white border border-gray-200 shadow-card p-5">
            <div class="flex items-start justify-between gap-3">
              <div class="min-w-0">
                <div class="text-sm font-semibold text-gray-900">{{ t.nom }}</div>
                @if (t.description) {
                  <p class="text-xs text-gray-500 mt-0.5">{{ t.description }}</p>
                }
                <div class="text-xs text-gray-500 mt-1 tabular-nums">
                  {{ t.nombreItems ?? t.items?.length ?? 0 }} critère(s) ·
                  pondérations {{ t.sommePonderations ?? '—' }} / {{ t.noteMax ?? '—' }}
                </div>
              </div>
              <div class="flex items-center gap-3 shrink-0">
                <button
                  type="button"
                  (click)="toggleItems(t.id)"
                  class="text-xs text-gray-500 hover:text-brand"
                >
                  {{ expandedId() === t.id ? 'Masquer' : 'Voir les critères' }}
                </button>
                <button
                  type="button"
                  (click)="openApply(t)"
                  class="inline-flex items-center px-3 py-1.5 rounded-lg border border-brand text-brand text-sm font-medium hover:bg-brand-50 transition-colors"
                >
                  Appliquer à une station
                </button>
              </div>
            </div>

            <!-- items preview -->
            @if (expandedId() === t.id && t.items?.length) {
              <ul class="mt-3 border-t border-gray-100 pt-3 divide-y divide-gray-100">
                @for (it of t.items; track it.id) {
                  <li class="py-1.5 flex items-center justify-between gap-3 text-sm">
                    <span class="text-gray-700 min-w-0">
                      <span class="text-gray-400">{{ it.ordre ?? '·' }}.</span> {{ it.libelle }}
                    </span>
                    <span class="text-xs text-gray-500 shrink-0">
                      {{ it.type === 'NUMERIQUE' ? 'Numérique' : 'Binaire' }}
                      @if (it.type === 'NUMERIQUE' && it.valeurMax != null) {
                        · /{{ it.valeurMax }}
                      }
                      · pond. {{ it.ponderation ?? '—' }}
                    </span>
                  </li>
                }
              </ul>
            }

            <!-- apply flow -->
            @if (applyTemplateId() === t.id) {
              <div class="mt-4 border-t border-gray-100 pt-4 space-y-3">
                @if (examsLoading()) {
                  <div class="text-xs text-gray-500">Chargement des examens…</div>
                } @else if (examsError()) {
                  <div class="text-xs text-gray-500 flex items-center gap-2">
                    <span>Impossible de charger les examens.</span>
                    <button type="button" (click)="openApply(t)" class="text-brand hover:underline">
                      Réessayer
                    </button>
                  </div>
                } @else if (modifiableExams()?.length === 0) {
                  <div class="text-xs text-gray-500 flex items-center gap-2">
                    <span>
                      Aucun examen modifiable (brouillon ou configuré). Un modèle ne peut s'appliquer
                      qu'avant le lancement.
                    </span>
                    <button type="button" (click)="cancelApply()" class="text-gray-500 hover:underline">
                      Fermer
                    </button>
                  </div>
                } @else {
                  <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
                    <div>
                      <label class="block text-xs font-medium text-gray-700 mb-1">Examen</label>
                      <select
                        #examPick
                        (change)="chooseExam(examPick.value)"
                        class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm bg-white focus:outline-none focus:ring-2 focus:ring-brand"
                      >
                        <option value="">Choisir un examen…</option>
                        @for (e of modifiableExams() ?? []; track e.id) {
                          <option [value]="e.id" [selected]="selectedExamId() === e.id">
                            {{ e.nom }} · {{ statutLabel(e.statut) }} ({{ e.dateExamen }})
                          </option>
                        }
                      </select>
                    </div>
                    @if (selectedExamId() != null) {
                      <div>
                        <label class="block text-xs font-medium text-gray-700 mb-1">Station</label>
                        @if (stationsLoading()) {
                          <div class="text-xs text-gray-500 py-2">Chargement des stations…</div>
                        } @else if ((stations() ?? []).length === 0) {
                          <div class="text-xs text-gray-500 py-2">
                            Cet examen n'a aucune station.
                          </div>
                        } @else {
                          <select
                            #stationPick
                            (change)="chooseStation(stationPick.value)"
                            class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm bg-white focus:outline-none focus:ring-2 focus:ring-brand"
                          >
                            <option value="">Choisir une station…</option>
                            @for (s of stations() ?? []; track s.id) {
                              <option [value]="s.id" [selected]="selectedStationId() === s.id">
                                {{ s.nom || 'Station ' + (s.ordre ?? '') }}
                                @if (s.hasGrille) {
                                  (grille existante)
                                }
                              </option>
                            }
                          </select>
                        }
                      </div>
                    }
                  </div>

                  <!-- smart confirm -->
                  @if (selectedStation(); as s) {
                    <div
                      class="rounded-lg border px-3 py-2 space-y-2"
                      [class.bg-red-50]="s.hasGrille"
                      [class.border-red-200]="s.hasGrille"
                      [class.bg-surface]="!s.hasGrille"
                      [class.border-gray-200]="!s.hasGrille"
                    >
                      @if (s.hasGrille) {
                        <p class="text-sm text-status-danger">
                          Remplacer la grille actuelle de « {{ s.nom || 'cette station' }} » par
                          « {{ t.nom }} » ? Les critères existants seront supprimés.
                        </p>
                      } @else {
                        <p class="text-sm text-gray-700">
                          Appliquer « {{ t.nom }} » à « {{ s.nom || 'cette station' }} » ?
                        </p>
                      }
                      @if (applyError()) {
                        <p role="alert" class="text-xs text-status-danger">{{ applyError() }}</p>
                      }
                      <div class="flex items-center justify-end gap-2">
                        <button
                          type="button"
                          (click)="cancelApply()"
                          [disabled]="applying()"
                          class="px-3 py-1 rounded-lg border border-gray-300 text-gray-700 text-xs font-medium hover:bg-gray-50 disabled:opacity-40"
                        >
                          Annuler
                        </button>
                        <button
                          type="button"
                          (click)="confirmApply(t)"
                          [disabled]="applying()"
                          class="px-3 py-1 rounded-lg text-white text-xs font-medium hover:opacity-90 disabled:opacity-40"
                          [class.bg-status-danger]="s.hasGrille"
                          [class.bg-brand]="!s.hasGrille"
                        >
                          {{ applying() ? '…' : s.hasGrille ? 'Remplacer' : 'Appliquer' }}
                        </button>
                      </div>
                    </div>
                  } @else {
                    <div class="flex justify-end">
                      <button
                        type="button"
                        (click)="cancelApply()"
                        class="text-xs text-gray-500 hover:text-gray-700"
                      >
                        Annuler
                      </button>
                    </div>
                  }
                }
              </div>
            }

            @if (appliedMessage() && appliedTemplateId() === t.id) {
              <p class="mt-3 text-xs text-status-success">{{ appliedMessage() }}</p>
            }
          </li>
        }
      </ul>
    }
  `,
})
export class BibliothequeComponent {
  private readonly examApi = inject(ExamApiService);

  readonly templates = signal<GrilleTemplate[]>([]);
  readonly loading = signal(true);
  readonly loadError = signal(false);

  readonly expandedId = signal<number | null>(null);

  // apply flow (one card open at a time)
  readonly applyTemplateId = signal<number | null>(null);
  readonly modifiableExams = signal<ExamenResponse[] | null>(null);
  readonly examsLoading = signal(false);
  readonly examsError = signal(false);
  readonly selectedExamId = signal<number | null>(null);
  readonly stations = signal<StationSummary[] | null>(null);
  readonly stationsLoading = signal(false);
  readonly selectedStationId = signal<number | null>(null);
  readonly applying = signal(false);
  readonly applyError = signal<string | null>(null);

  // post-apply confirmation message, scoped to the card it applies to
  readonly appliedMessage = signal<string | null>(null);
  readonly appliedTemplateId = signal<number | null>(null);

  /** The chosen station object, for the smart confirm wording (hasGrille). */
  readonly selectedStation = computed(() => {
    const id = this.selectedStationId();
    if (id == null) return null;
    return (this.stations() ?? []).find((s) => s.id === id) ?? null;
  });

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.examApi.listGrilleTemplates().subscribe({
      next: (list) => {
        this.templates.set(list);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set(true);
        this.loading.set(false);
      },
    });
  }

  toggleItems(id: number): void {
    this.expandedId.set(this.expandedId() === id ? null : id);
  }

  // ---- apply flow ---------------------------------------------------------

  openApply(t: GrilleTemplate): void {
    this.applyTemplateId.set(t.id);
    this.selectedExamId.set(null);
    this.selectedStationId.set(null);
    this.stations.set(null);
    this.applyError.set(null);
    this.appliedMessage.set(null);
    if (this.modifiableExams() === null) this.loadModifiableExams();
  }

  cancelApply(): void {
    this.applyTemplateId.set(null);
    this.selectedExamId.set(null);
    this.selectedStationId.set(null);
    this.applyError.set(null);
  }

  /** Only BROUILLON + CONFIGURE exams — apply 400s on anything launched. */
  private loadModifiableExams(): void {
    this.examsLoading.set(true);
    this.examsError.set(false);
    forkJoin({
      brouillon: this.examApi.listExamens({ statut: 'BROUILLON', size: 100 }),
      configure: this.examApi.listExamens({ statut: 'CONFIGURE', size: 100 }),
    }).subscribe({
      next: ({ brouillon, configure }) => {
        this.modifiableExams.set([...brouillon.content, ...configure.content]);
        this.examsLoading.set(false);
      },
      error: () => {
        this.examsError.set(true);
        this.examsLoading.set(false);
      },
    });
  }

  chooseExam(rawId: string): void {
    const id = Number(rawId);
    this.selectedStationId.set(null);
    this.stations.set(null);
    this.applyError.set(null);
    if (!Number.isFinite(id) || id === 0) {
      this.selectedExamId.set(null);
      return;
    }
    this.selectedExamId.set(id);
    this.stationsLoading.set(true);
    this.examApi.listStations(id).subscribe({
      next: (list) => {
        this.stations.set(list);
        this.stationsLoading.set(false);
      },
      error: () => {
        this.stations.set([]);
        this.stationsLoading.set(false);
      },
    });
  }

  chooseStation(rawId: string): void {
    const id = Number(rawId);
    this.applyError.set(null);
    this.selectedStationId.set(Number.isFinite(id) && id !== 0 ? id : null);
  }

  confirmApply(t: GrilleTemplate): void {
    const s = this.selectedStation();
    if (!s || this.applying()) return;
    const replacing = !!s.hasGrille;
    this.applying.set(true);
    this.applyError.set(null);
    this.examApi.applyTemplateToStation(t.id, s.id).subscribe({
      next: () => {
        this.applying.set(false);
        const verb = replacing ? 'remplacée' : 'appliquée';
        this.appliedTemplateId.set(t.id);
        this.appliedMessage.set(
          `Modèle « ${t.nom} » ${verb} sur « ${s.nom || 'la station'} ».`,
        );
        // Reflect the now-present grille so a second apply onto the same station
        // gets the replace wording.
        this.stations.update((list) =>
          (list ?? []).map((x) => (x.id === s.id ? { ...x, hasGrille: true } : x)),
        );
        this.cancelApply();
      },
      error: (err: HttpErrorResponse) => {
        this.applying.set(false);
        this.applyError.set(this.mutationMessage(err));
      },
    });
  }

  private mutationMessage(err: HttpErrorResponse): string {
    if (err.status === 400) {
      return typeof err.error?.message === 'string'
        ? err.error.message
        : "Application impossible. L'examen doit être en brouillon ou configuré.";
    }
    if (err.status === 403) return "Vous n'avez pas les droits sur cet examen.";
    if (err.status === 404) return 'Ressource introuvable. Rechargez la page.';
    return "Échec de l'application. Réessayez.";
  }

  statutLabel(s: ExamenResponse['statut']): string {
    return (
      { BROUILLON: 'Brouillon', CONFIGURE: 'Configuré', EN_COURS: 'En cours', TERMINE: 'Terminé', ARCHIVE: 'Archivé' }[
        s
      ] ?? s
    );
  }
}
