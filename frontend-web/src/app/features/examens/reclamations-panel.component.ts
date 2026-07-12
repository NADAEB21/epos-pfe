import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { forkJoin } from 'rxjs';
import { ScoringApiService } from '../../core/api/scoring-api.service';
import {
  EtudiantSummary,
  ParticipationSummary,
  Reclamation,
  ReclamationStatus,
} from '../../core/api/models';

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
  template: `
    <section class="mt-8">
      <div class="flex items-center justify-between mb-3">
        <h2 class="text-sm font-medium text-gray-500">
          Réclamations
          @if (reclamations().length > 0) {
            <span class="text-gray-400">· {{ reclamations().length }}</span>
          }
        </h2>
        @if (!showForm()) {
          <button
            type="button"
            (click)="openForm()"
            class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-gray-300 text-gray-600 text-sm font-medium hover:bg-gray-50 transition-colors"
          >
            + Nouvelle réclamation
          </button>
        }
      </div>

      <!-- File a complaint -->
      @if (showForm()) {
        <div class="rounded-xl bg-white border border-gray-200 shadow-card p-4 mb-4">
          <h3 class="text-sm font-semibold text-gray-700 mb-3">Enregistrer une réclamation</h3>
          <div class="space-y-3">
            <label class="block text-xs text-gray-600">
              Étudiant concerné <span class="text-status-danger">*</span>
              <select
                [value]="formParticipationId() ?? ''"
                (change)="formParticipationId.set($any($event.target).value ? +$any($event.target).value : null)"
                class="mt-1 block w-full rounded border border-gray-300 px-2 py-1.5 text-sm"
              >
                <option value="">— Sélectionner un étudiant —</option>
                @for (opt of participationOptions(); track opt.participationId) {
                  <option [value]="opt.participationId">{{ opt.label }}</option>
                }
              </select>
            </label>
            <label class="block text-xs text-gray-600">
              Objet de la réclamation <span class="text-status-danger">*</span>
              <textarea
                rows="3"
                [value]="formObjet()"
                (input)="formObjet.set($any($event.target).value)"
                maxlength="1000"
                class="mt-1 block w-full rounded border border-gray-300 px-2 py-1.5 text-sm"
                placeholder="Ex. : l'étudiant conteste la note de la station 2 et demande un recomptage."
              ></textarea>
            </label>
            @if (formError()) {
              <p role="alert" class="text-xs text-status-danger">{{ formError() }}</p>
            }
            <div class="flex gap-2">
              <button
                type="button"
                (click)="submitForm()"
                [disabled]="formBusy()"
                class="rounded-lg bg-brand px-3 py-1.5 text-sm font-medium text-white hover:bg-brand-dark disabled:opacity-50"
              >
                {{ formBusy() ? 'Enregistrement…' : 'Enregistrer' }}
              </button>
              <button
                type="button"
                (click)="cancelForm()"
                [disabled]="formBusy()"
                class="rounded-lg px-3 py-1.5 text-sm text-gray-500 hover:text-gray-700"
              >
                Annuler
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Register -->
      @if (loading()) {
        <div class="h-20 rounded-xl bg-gray-200 animate-pulse"></div>
      } @else if (error()) {
        <div class="rounded-xl bg-white border border-gray-200 p-4 text-center">
          <p class="text-sm text-gray-600 mb-2">Impossible de charger les réclamations.</p>
          <button type="button" (click)="reload()" class="text-sm text-brand font-medium hover:underline">
            Réessayer
          </button>
        </div>
      } @else if (reclamations().length === 0) {
        @if (!showForm()) {
          <div class="rounded-xl bg-white border border-gray-200 p-6 text-center shadow-card">
            <p class="text-sm text-gray-500">
              Aucune réclamation pour cet examen. Enregistrez une contestation d'un étudiant
              pour en garder la trace et la décision.
            </p>
          </div>
        }
      } @else {
        <ul class="space-y-3">
          @for (r of reclamations(); track r.id) {
            <li class="rounded-xl bg-white border border-gray-200 shadow-card p-4">
              <div class="flex items-start justify-between gap-3">
                <div class="min-w-0">
                  <div class="flex items-center gap-2 flex-wrap">
                    <span class="text-xs font-medium px-2 py-0.5 rounded-full" [class]="statutClass(r.statut)">
                      {{ statutLabel(r.statut) }}
                    </span>
                    <span class="text-sm font-medium text-gray-900">{{ participationLabel(r.participationId) }}</span>
                  </div>
                  <p class="mt-1.5 text-sm text-gray-700 whitespace-pre-wrap">{{ r.objet }}</p>
                  <p class="mt-1 text-xs text-gray-400">
                    Déposée le {{ r.createdAt | date: 'dd/MM/yyyy HH:mm' }}
                  </p>
                </div>
                @if (r.statut === 'EN_ATTENTE' && resolveId() !== r.id) {
                  <button
                    type="button"
                    (click)="openResolve(r.id)"
                    class="shrink-0 rounded-lg border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50"
                  >
                    Traiter
                  </button>
                }
              </div>

              <!-- Decision (already resolved) -->
              @if (r.statut !== 'EN_ATTENTE') {
                <div class="mt-3 rounded-lg bg-gray-50 border border-gray-100 p-3">
                  <p class="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-1">Décision</p>
                  <p class="text-sm text-gray-700 whitespace-pre-wrap">{{ r.reponse }}</p>
                  @if (r.resolvedAt) {
                    <p class="mt-1 text-xs text-gray-400">Traitée le {{ r.resolvedAt | date: 'dd/MM/yyyy HH:mm' }}</p>
                  }
                  @if (r.statut === 'ACCEPTEE') {
                    <p class="mt-1.5 text-xs italic text-gray-500">
                      Réclamation acceptée — la correction éventuelle de la note se fait via le
                      réajustement sur la ligne concernée du classement.
                    </p>
                  }
                </div>
              }

              <!-- Resolve dialog -->
              @if (resolveId() === r.id) {
                <div class="mt-3 rounded-lg border border-amber-200 bg-amber-50/60 p-3 space-y-3">
                  <p class="text-xs font-semibold text-amber-800 uppercase tracking-wide">Traiter la réclamation</p>
                  <div class="flex gap-4">
                    <label class="inline-flex items-center gap-1.5 text-sm text-gray-700">
                      <input type="radio" name="statut-{{ r.id }}" value="ACCEPTEE"
                        [checked]="resolveStatut() === 'ACCEPTEE'"
                        (change)="resolveStatut.set('ACCEPTEE')" />
                      Accepter
                    </label>
                    <label class="inline-flex items-center gap-1.5 text-sm text-gray-700">
                      <input type="radio" name="statut-{{ r.id }}" value="REJETEE"
                        [checked]="resolveStatut() === 'REJETEE'"
                        (change)="resolveStatut.set('REJETEE')" />
                      Rejeter
                    </label>
                  </div>
                  <label class="block text-xs text-gray-600">
                    Réponse / justification <span class="text-status-danger">*</span>
                    <textarea rows="2" [value]="resolveReponse()"
                      (input)="resolveReponse.set($any($event.target).value)"
                      maxlength="1000"
                      class="mt-1 block w-full rounded border border-gray-300 px-2 py-1.5 text-sm"
                      placeholder="Ex. : après recomptage, la note est confirmée / corrigée."></textarea>
                  </label>
                  @if (resolveError()) {
                    <p role="alert" class="text-xs text-status-danger">{{ resolveError() }}</p>
                  }
                  <div class="flex gap-2">
                    <button type="button" (click)="submitResolve(r.id)" [disabled]="resolveBusy()"
                      class="rounded-lg bg-amber-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-amber-700 disabled:opacity-50">
                      {{ resolveBusy() ? 'Enregistrement…' : 'Enregistrer la décision' }}
                    </button>
                    <button type="button" (click)="cancelResolve()" [disabled]="resolveBusy()"
                      class="rounded-lg px-3 py-1.5 text-sm text-gray-500 hover:text-gray-700">Annuler</button>
                  </div>
                  <p class="text-[11px] text-gray-400">
                    Une réclamation ne se décide qu'une seule fois. Une réclamation acceptée n'ajuste
                    pas la note automatiquement — utilisez le réajustement sur le classement.
                  </p>
                </div>
              }
            </li>
          }
        </ul>
      }
    </section>
  `,
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
