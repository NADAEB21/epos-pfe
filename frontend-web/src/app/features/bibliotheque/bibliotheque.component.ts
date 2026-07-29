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
  templateUrl: './bibliotheque.component.html',
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
