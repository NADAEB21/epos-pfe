import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { forkJoin } from 'rxjs';
import { ScoringApiService } from '../../../core/api/scoring-api.service';
import {
  EtudiantSummary,
  ParticipationSummary,
  Reclamation,
  ReclamationStatus,
} from '../../../core/api/models';

/** One enrolled student, resolved to a display label for the file-complaint picker. */
interface ParticipationOption {
  participationId: number;
  label: string;
}

/**
 * Réclamations — the student complaint register (#136), embedded on the Résultats
 * screen so a complaint sits in the same exam/participation context as the scores
 * it contests. RESPONSABLE_MATIERE / SUPER_ADMIN surface (the backend endpoints
 * are RESP/ADMIN-only; the évaluateur never sees this).
 *
 * <p>Three pieces: <b>file</b> a complaint (participation + objet), the <b>register</b>
 * list per exam with EN_ATTENTE / ACCEPTEE / REJETEE badges, and <b>resolve</b>
 * (decide once: ACCEPTEE/REJETEE + mandatory réponse). The register only RECORDS
 * the decision — the actual score change stays the audited réajustement flow on the
 * results table (ADR-0013 Part 2), which is why this panel carries NO score-edit UI:
 * an upheld complaint is corrected there, the register just notes it was upheld.
 *
 * <p>Students have no login, so the responsable files on their behalf. The
 * participation picker is the exam roster (participations joined to étudiants), not
 * only the scored students — a complaint can concern any enrolment.
 */
@Component({
  selector: 'app-reclamations-panel',
  standalone: true,
  imports: [DatePipe],
  templateUrl: './reclamations-panel.component.html',
})
export class ReclamationsPanelComponent {
  private readonly scoring = inject(ScoringApiService);

  /** The exam whose complaints this register shows. */
  readonly examenId = input.required<number>();

  readonly loading = signal(true);
  readonly error = signal(false);
  readonly reclamations = signal<Reclamation[]>([]);

  /** Roster (participations) + student directory, joined for the picker + labels. */
  private readonly participations = signal<ParticipationSummary[]>([]);
  private readonly etudiantById = new Map<number, EtudiantSummary>();

  // ---- file-complaint form ----
  readonly showForm = signal(false);
  readonly formParticipationId = signal<number | null>(null);
  readonly formObjet = signal('');
  readonly formBusy = signal(false);
  readonly formError = signal<string | null>(null);

  // ---- resolve dialog (id of the réclamation being decided, or null) ----
  readonly resolveId = signal<number | null>(null);
  readonly resolveStatut = signal<'ACCEPTEE' | 'REJETEE' | null>(null);
  readonly resolveReponse = signal('');
  readonly resolveBusy = signal(false);
  readonly resolveError = signal<string | null>(null);

  /** Enrolled students, sorted by label, for the file-complaint picker. */
  readonly participationOptions = computed<ParticipationOption[]>(() =>
    this.participations()
      .map((p) => ({ participationId: p.id, label: this.participationLabel(p.id) }))
      .sort((a, b) => a.label.localeCompare(b.label, 'fr')),
  );

  constructor() {
    effect(
      () => {
        const id = this.examenId();
        if (Number.isFinite(id)) this.load(id);
      },
      { allowSignalWrites: true },
    );
  }

  reload(): void {
    this.load(this.examenId());
  }

  private load(examenId: number): void {
    this.loading.set(true);
    this.error.set(false);
    forkJoin({
      reclamations: this.scoring.listReclamations(examenId),
      participations: this.scoring.listParticipations(examenId),
      etudiants: this.scoring.listEtudiants(),
    }).subscribe({
      next: ({ reclamations, participations, etudiants }) => {
        this.etudiantById.clear();
        for (const e of etudiants) this.etudiantById.set(e.id, e);
        this.participations.set(participations);
        this.reclamations.set(reclamations);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  /** Refresh only the register list after a create/resolve (roster is unchanged). */
  private reloadList(): void {
    this.scoring.listReclamations(this.examenId()).subscribe({
      next: (list) => this.reclamations.set(list),
      error: () => void 0,
    });
  }

  /** "Prénom Nom (N° inscr)" for a participation, or a fallback when unresolved. */
  participationLabel(participationId: number): string {
    const p = this.participations().find((x) => x.id === participationId);
    const e = p?.etudiantId != null ? this.etudiantById.get(p.etudiantId) : undefined;
    if (!e) return `Participation #${participationId}`;
    const name = `${e.prenom ?? ''} ${e.nom ?? ''}`.trim() || 'Étudiant inconnu';
    return e.numero_inscription ? `${name} (N° ${e.numero_inscription})` : name;
  }

  statutLabel(s: ReclamationStatus): string {
    switch (s) {
      case 'ACCEPTEE':
        return 'Acceptée';
      case 'REJETEE':
        return 'Rejetée';
      default:
        return 'En attente';
    }
  }

  statutClass(s: ReclamationStatus): string {
    switch (s) {
      case 'ACCEPTEE':
        return 'bg-emerald-100 text-emerald-700';
      case 'REJETEE':
        return 'bg-red-100 text-red-700';
      default:
        return 'bg-amber-100 text-amber-700';
    }
  }

  // ---- file a complaint --------------------------------------------------

  openForm(): void {
    this.showForm.set(true);
    this.formParticipationId.set(null);
    this.formObjet.set('');
    this.formError.set(null);
  }

  cancelForm(): void {
    this.showForm.set(false);
    this.formBusy.set(false);
    this.formError.set(null);
  }

  submitForm(): void {
    const participationId = this.formParticipationId();
    const objet = this.formObjet().trim();
    if (participationId == null) {
      this.formError.set("Sélectionnez l'étudiant concerné.");
      return;
    }
    if (!objet) {
      this.formError.set("L'objet de la réclamation est obligatoire.");
      return;
    }
    this.formBusy.set(true);
    this.formError.set(null);
    this.scoring
      .createReclamation({ examenId: this.examenId(), participationId, objet })
      .subscribe({
        next: () => {
          this.formBusy.set(false);
          this.showForm.set(false);
          this.reloadList();
        },
        error: (err: { status?: number; error?: { message?: string } }) => {
          this.formBusy.set(false);
          this.formError.set(
            err?.status === 403
              ? "Vous n'avez pas les droits pour enregistrer une réclamation."
              : (err?.error?.message ?? "L'enregistrement a échoué."),
          );
        },
      });
  }

  // ---- resolve -----------------------------------------------------------

  openResolve(id: number): void {
    this.resolveId.set(id);
    this.resolveStatut.set(null);
    this.resolveReponse.set('');
    this.resolveError.set(null);
  }

  cancelResolve(): void {
    this.resolveId.set(null);
    this.resolveBusy.set(false);
    this.resolveError.set(null);
  }

  submitResolve(id: number): void {
    const statut = this.resolveStatut();
    const reponse = this.resolveReponse().trim();
    if (statut == null) {
      this.resolveError.set('Choisissez d’accepter ou de rejeter la réclamation.');
      return;
    }
    if (!reponse) {
      this.resolveError.set('La réponse (justification) est obligatoire.');
      return;
    }
    this.resolveBusy.set(true);
    this.resolveError.set(null);
    this.scoring.resolveReclamation(id, { statut, reponse }).subscribe({
      next: () => {
        this.resolveBusy.set(false);
        this.resolveId.set(null);
        this.reloadList();
      },
      error: (err: { status?: number; error?: { message?: string } }) => {
        this.resolveBusy.set(false);
        this.resolveError.set(
          err?.status === 403
            ? "Vous n'avez pas les droits pour traiter cette réclamation."
            : (err?.error?.message ?? 'Le traitement a échoué.'),
        );
      },
    });
  }
}
