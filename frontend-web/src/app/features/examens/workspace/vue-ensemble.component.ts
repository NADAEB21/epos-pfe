import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';
import { catchError, forkJoin, of } from 'rxjs';
import { ExamApiService } from '../../../core/api/exam-api.service';
import { ScoringApiService } from '../../../core/api/scoring-api.service';
import { ExamenResponse, StationSummary, TypeStation } from '../../../core/api/models';
import { ExamenWorkspaceStore } from './examen-workspace.store';

type ReadinessState = 'ok' | 'todo' | 'unknown';

interface ReadinessItem {
  label: string;
  state: ReadinessState;
  hint?: string;
}

const TYPE_LABELS: Record<TypeStation, string> = {
  PRATIQUE: 'Pratique',
  THEORIQUE: 'Théorique',
};

/**
 * Read-only per-exam dashboard. BROUILLON/CONFIGURE landing tab and the target of
 * the Accueil "Continuer la configuration" CTA, so it closes the click-through.
 *
 * Station data comes from the dedicated GET /examens/{id}/stations endpoint, not
 * the stations embedded in getExamen(id): only the dedicated endpoint populates
 * evaluateurIds + hasGrille, which the per-station coverage + readiness summary
 * need. The exam call still supplies meta (dates, durée, sujet PDF, statut).
 */
@Component({
  selector: 'app-vue-ensemble',
  standalone: true,
  templateUrl: './vue-ensemble.component.html',
  imports: [RouterLink, ReactiveFormsModule],
})
export class VueEnsembleComponent {
  private readonly examApi = inject(ExamApiService);
  private readonly scoring = inject(ScoringApiService);
  private readonly store = inject(ExamenWorkspaceStore);
  private readonly fb = inject(FormBuilder);
  private readonly router = inject(Router);

  /** Inherited from the parent examens/:id route via withComponentInputBinding(). */
  readonly id = input.required<string>();

  readonly exam = signal<ExamenResponse | null>(null);
  readonly stations = signal<StationSummary[]>([]);
  readonly loading = signal(true);
  readonly error = signal(false);

  /** Metadata is editable only while BROUILLON (mirrors Examen.isModifiable). */
  readonly canEdit = computed(() => this.exam()?.statut === 'BROUILLON');

  /** #405 — un examen clos n'a plus d'« état de préparation » : il a un résumé. */
  readonly isClos = computed(() => {
    const s = this.exam()?.statut;
    return s === 'TERMINE' || s === 'ARCHIVE';
  });
  /** Résumé d'un examen clos : compteurs servis par scoring, aucune moyenne recalculée ici. */
  readonly resume = signal<{ notes: number; verrouillees: number; bareme: number | null } | null>(null);
  readonly resumeEtat = signal<'chargement' | 'absent' | 'pret'>('chargement');

  private chargerResume(examId: number): void {
    this.resumeEtat.set('chargement');
    this.scoring
      .getExamenResults(examId)
      .pipe(catchError(() => of(null)))
      .subscribe((rows) => {
        if (!rows) {
          this.resumeEtat.set('absent');
          return;
        }
        const verrouillees = rows.filter((r) => r.stations.length > 0 && r.stations.every((st) => st.verrouillee === true)).length;
        const bareme = rows.find((r) => r.baremeVersion != null && r.totalDelibere != null)?.baremeVersion ?? null;
        this.resume.set({ notes: rows.length, verrouillees, bareme });
        this.resumeEtat.set('pret');
      });
  }

  /**
   * Delete/cancel is allowed only while BROUILLON or CONFIGURE — mirrors the
   * backend gate (ExamenServiceImpl.supprimer blocks EN_COURS/TERMINE/ARCHIVE).
   */
  readonly canDelete = computed(() => {
    const s = this.exam()?.statut;
    return s === 'BROUILLON' || s === 'CONFIGURE';
  });

  readonly confirmingDelete = signal(false);
  readonly deleting = signal(false);
  readonly deleteError = signal<string | null>(null);

  /**
   * Revert is the one backwards edge the state machine allows
   * (ExamenServiceImpl.validerTransitionStatut: CONFIGURE→BROUILLON). It re-opens
   * the BROUILLON-only metadata edit for a configured exam whose date/heure/durée
   * would otherwise be frozen.
   */
  readonly canRevert = computed(() => this.exam()?.statut === 'CONFIGURE');
  readonly reverting = signal(false);
  readonly revertError = signal<string | null>(null);

  /**
   * Sujet PDF can be attached/replaced only while authoring (BROUILLON/CONFIGURE);
   * once EN_COURS the sujet is in use. The backend itself doesn't gate the upload,
   * so this is a UI-phase guard. Download is always offered when a PDF exists.
   */
  readonly canManagePdf = computed(() => {
    const s = this.exam()?.statut;
    return s === 'BROUILLON' || s === 'CONFIGURE';
  });
  readonly pdfUploading = signal(false);
  readonly pdfDownloading = signal(false);
  readonly pdfError = signal<string | null>(null);

  readonly editing = signal(false);
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);
  readonly editForm = this.fb.nonNullable.group({
    nom: ['', [Validators.required, Validators.minLength(3), Validators.maxLength(150)]],
    dateExamen: ['', [Validators.required]],
    heureDebut: ['09:00', [Validators.required]],
    dureeStationMin: this.fb.control<number | null>(null, [Validators.min(1), Validators.max(180)]),
    nbEtudiantsParStation: this.fb.control<number | null>(null, [Validators.min(1), Validators.max(10)]),
    description: ['', [Validators.maxLength(500)]],
  });

  openEdit(e: ExamenResponse): void {
    this.editForm.reset({
      nom: e.nom,
      dateExamen: e.dateExamen,
      heureDebut: e.heureDebut ?? '09:00',
      dureeStationMin: e.dureeStationMin,
      nbEtudiantsParStation: e.nbEtudiantsParStation,
      description: e.description ?? '',
    });
    this.saveError.set(null);
    this.editing.set(true);
  }

  cancelEdit(): void {
    this.editing.set(false);
    this.saveError.set(null);
  }

  submitEdit(): void {
    const current = this.exam();
    if (!current || this.editForm.invalid || this.saving()) return;
    const raw = this.editForm.getRawValue();
    this.saving.set(true);
    this.saveError.set(null);
    // matiereId is @NotNull server-side and not editable here — resend the
    // exam's existing matière so the PUT validates.
    this.examApi
      .updateExamen(current.id, {
        nom: raw.nom.trim(),
        matiereId: current.matiereId,
        dateExamen: raw.dateExamen,
        heureDebut: raw.heureDebut || undefined,
        dureeStationMin: raw.dureeStationMin ?? undefined,
        nbEtudiantsParStation: raw.nbEtudiantsParStation ?? undefined,
        description: raw.description.trim() || undefined,
      })
      .subscribe({
        next: (updated) => {
          this.saving.set(false);
          this.editing.set(false);
          this.exam.set(updated);
          // Refresh the route-scoped store so the workspace header (nom/date)
          // reflects the edit without a manual reload.
          this.store.reload();
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          if (err.status === 403) this.saveError.set("Vous n'avez pas les droits sur cet examen.");
          else if (err.status === 400)
            this.saveError.set('Certains champs sont invalides. Verifiez le formulaire.');
          else if (err.status === 409)
            this.saveError.set("L'examen n'est plus modifiable (statut change).");
          else this.saveError.set('Erreur de connexion. Reessayez.');
        },
      });
  }

  // ---- delete / cancel ----------------------------------------------------

  askDelete(): void {
    this.deleteError.set(null);
    this.confirmingDelete.set(true);
  }

  cancelDelete(): void {
    this.confirmingDelete.set(false);
    this.deleteError.set(null);
  }

  confirmDelete(e: ExamenResponse): void {
    if (this.deleting()) return;
    this.deleting.set(true);
    this.deleteError.set(null);
    this.examApi.deleteExamen(e.id).subscribe({
      next: () => {
        // Exam is gone — leave the workspace for the list rather than landing
        // on a now-404 tab.
        this.router.navigate(['/examens']);
      },
      error: (err: HttpErrorResponse) => {
        this.deleting.set(false);
        this.confirmingDelete.set(false);
        if (err.status === 403) this.deleteError.set("Vous n'avez pas les droits sur cet examen.");
        else if (err.status === 400 || err.status === 409)
          this.deleteError.set(
            "L'examen ne peut plus etre supprime a ce statut. Rechargez la page.",
          );
        else if (err.status === 404) this.deleteError.set('Examen introuvable (deja supprime ?).');
        else this.deleteError.set('Erreur de connexion. Reessayez.');
      },
    });
  }

  // ---- revert to brouillon ------------------------------------------------

  revertToBrouillon(e: ExamenResponse): void {
    if (this.reverting()) return;
    this.reverting.set(true);
    this.revertError.set(null);
    this.examApi.changerStatut(e.id, 'BROUILLON').subscribe({
      next: (updated) => {
        this.reverting.set(false);
        this.exam.set(updated);
        // Header chip (statut) lives in the workspace store — refresh it too.
        this.store.reload();
      },
      error: (err: HttpErrorResponse) => {
        this.reverting.set(false);
        if (err.status === 403) this.revertError.set("Vous n'avez pas les droits sur cet examen.");
        else if (err.status === 400 || err.status === 409)
          this.revertError.set("L'examen ne peut plus revenir au brouillon. Rechargez la page.");
        else this.revertError.set('Erreur de connexion. Reessayez.');
      },
    });
  }

  // ---- sujet PDF ----------------------------------------------------------

  onPdfSelected(event: Event, e: ExamenResponse): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    // Reset so re-selecting the SAME file still fires (change) again.
    input.value = '';
    if (!file) return;
    if (file.type !== 'application/pdf') {
      this.pdfError.set('Seuls les fichiers PDF sont acceptes.');
      return;
    }
    // Mirror the server-side ~10 MB cap to fail fast before the upload.
    if (file.size > 10 * 1024 * 1024) {
      this.pdfError.set('Le fichier depasse la taille maximale de 10 Mo.');
      return;
    }
    this.pdfUploading.set(true);
    this.pdfError.set(null);
    this.examApi.uploadPdfSujet(e.id, file).subscribe({
      next: (updated) => {
        this.pdfUploading.set(false);
        this.exam.set(updated);
      },
      error: (err: HttpErrorResponse) => {
        this.pdfUploading.set(false);
        if (err.status === 403) this.pdfError.set("Vous n'avez pas les droits sur cet examen.");
        else if (err.status === 400)
          this.pdfError.set('Fichier invalide (PDF requis, 10 Mo max).');
        else this.pdfError.set("Echec de l'import. Reessayez.");
      },
    });
  }

  downloadPdf(e: ExamenResponse): void {
    if (this.pdfDownloading()) return;
    this.pdfDownloading.set(true);
    this.pdfError.set(null);
    this.examApi.downloadPdfSujet(e.id).subscribe({
      next: (blob) => {
        this.pdfDownloading.set(false);
        // Open the fetched blob in a new tab (the endpoint needs the JWT, so we
        // can't link to it directly). Revoke later so the tab has time to load.
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank');
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      },
      error: (err: HttpErrorResponse) => {
        this.pdfDownloading.set(false);
        if (err.status === 404) this.pdfError.set('Sujet introuvable (deja supprime ?).');
        else this.pdfError.set('Echec du telechargement. Reessayez.');
      },
    });
  }

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
    forkJoin({
      exam: this.examApi.getExamen(examId),
      stations: this.examApi.listStations(examId),
    }).subscribe({
      next: ({ exam, stations }) => {
        this.exam.set(exam);
        this.stations.set(stations);
        this.loading.set(false);
        if (exam.statut === 'TERMINE' || exam.statut === 'ARCHIVE') this.chargerResume(examId);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  /** Stations with no évaluateur assigned (coverage gap). */
  private readonly stationsSansEvaluateur = computed(
    () => this.stations().filter((s) => this.evalCount(s) === 0).length,
  );

  /** Stations with no grille attached. */
  private readonly stationsSansGrille = computed(
    () => this.stations().filter((s) => !s.hasGrille).length,
  );

  readonly readinessSummary = computed(() => {
    const total = this.stations().length;
    if (total === 0) return 'Aucune station';
    const parts = [`${total} station(s)`];
    if (this.stationsSansEvaluateur() > 0) parts.push(`${this.stationsSansEvaluateur()} sans evaluateur`);
    if (this.stationsSansGrille() > 0) parts.push(`${this.stationsSansGrille()} sans grille`);
    if (this.stationsSansEvaluateur() === 0 && this.stationsSansGrille() === 0) parts.push('couverture complete');
    return parts.join(' · ');
  });

  readonly readiness = computed<ReadinessItem[]>(() => {
    const e = this.exam();
    const stations = this.stations();
    const hasStations = stations.length > 0;

    // ok if all stations covered, todo if any gap, unknown until stations exist.
    const coverState = (gap: number): ReadinessState =>
      !hasStations ? 'unknown' : gap === 0 ? 'ok' : 'todo';

    return [
      {
        label: 'Stations définies',
        state: hasStations ? 'ok' : 'todo',
        hint: hasStations ? `${stations.length} station(s)` : undefined,
      },
      {
        label: 'Évaluateurs affectés',
        state: coverState(this.stationsSansEvaluateur()),
        hint:
          hasStations && this.stationsSansEvaluateur() > 0
            ? `${this.stationsSansEvaluateur()} station(s) sans evaluateur`
            : undefined,
      },
      {
        label: 'Grilles complètes',
        state: coverState(this.stationsSansGrille()),
        hint:
          hasStations && this.stationsSansGrille() > 0
            ? `${this.stationsSansGrille()} station(s) sans grille`
            : undefined,
      },
      { label: 'Sujet PDF', state: e?.hasPdfSujet ? 'ok' : 'todo' },
      // Roster / planning / launch readiness need the backend pre-launch
      // validation endpoint (Task B backlog) — neutral until then.
      { label: 'Liste des étudiants chargée', state: 'unknown' },
      { label: 'Prêt au lancement', state: 'unknown' },
    ];
  });

  evalCount(s: StationSummary): number {
    return s.evaluateurIds?.length ?? 0;
  }

  evalLabel(s: StationSummary): string {
    const n = this.evalCount(s);
    return n === 0 ? 'Aucun évaluateur' : `${n} évaluateur(s)`;
  }

  typeLabel(t: TypeStation): string {
    return TYPE_LABELS[t];
  }

  value(n: number | null, suffix = ''): string {
    if (n == null) return '—';
    return suffix ? `${n} ${suffix}` : `${n}`;
  }
}
