import { Component, computed, effect, inject, signal } from '@angular/core';
import { input } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { forkJoin } from 'rxjs';
import { ScoringApiService } from '../../core/api/scoring-api.service';
import { EtudiantSummary, ParticipationSummary } from '../../core/api/models';
import { ExamenWorkspaceStore } from './examen-workspace.store';

/** A participation joined to its student — one roster row. */
interface RosterRow {
  participationId: number;
  etudiantId: number | null;
  nom: string;
  prenom: string;
  numeroInscription: string | null;
  numEchantillon: string | null;
  present: boolean | null;
  note: number | null;
}

/** Normalise a numéro d'inscription for duplicate comparison (trim + casefold). */
function normNumero(v: string | null | undefined): string {
  return (v ?? '').trim().toLowerCase();
}

/**
 * Étudiants tab — the per-exam roster + authoring. A student isn't tied to an
 * exam directly; the only link is scoring-service's ExamenParticipation, so the
 * roster of exam X is its participations (filtered server-side via ?examenId)
 * joined to the global student directory by etudiantId.
 *
 * Authoring (gated on editable() — BROUILLON/CONFIGURE, mirroring stations/grilles):
 *   • Ajouter un nouvel étudiant — 2-step: POST /etudiants (global directory),
 *     then POST /participations to enrol. A student can exist without being
 *     enrolled, so the two calls are distinct.
 *   • Ajouter un étudiant existant — pick from the directory (minus those already
 *     enrolled) → POST /participations only.
 *   • Retirer — DELETE /participations/{id} (the enrolment only; the student
 *     stays in the directory).
 *
 * Backend holes guarded here client-side (verified against EtudiantService /
 * ExamenParticipationService — neither enforces these):
 *   • numero_inscription is NOT unique server-side → the add-new form blocks a
 *     numéro already in the directory and points to the existing-student path.
 *   • no duplicate-(examen,étudiant) guard → the existing-student picker excludes
 *     anyone already on the roster.
 *   • no exam-status gate on scoring → editable() is the only thing stopping a
 *     roster edit once EN_COURS (UX parity with the other tabs, not server-enforced).
 *
 * Présence / note / num_echantillon stay read-only here — they belong to exam day
 * and orchestration, not roster authoring.
 */
@Component({
  selector: 'app-etudiants',
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
    @if (loading()) {
      <div class="space-y-3 animate-pulse">
        <div class="h-10 rounded-lg bg-gray-200 w-1/3"></div>
        <div class="h-64 rounded-xl bg-gray-200"></div>
      </div>
    } @else if (error()) {
      <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card">
        <p class="text-gray-700 mb-1">Impossible de charger la liste des etudiants.</p>
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
      <!-- Authoring actions -->
      @if (editable()) {
        <div class="mb-4 space-y-4">
          @if (mode() === 'idle') {
            <div class="flex flex-wrap items-center gap-2">
              <button
                type="button"
                (click)="openAddNew()"
                class="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark transition-colors"
              >
                + Ajouter un nouvel etudiant
              </button>
              <button
                type="button"
                (click)="openAddExisting()"
                class="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg border border-gray-300 text-gray-700 text-sm font-medium hover:bg-gray-50 transition-colors"
              >
                Ajouter un etudiant existant
              </button>
            </div>
          }

          <!-- Add a brand-new student (2-step: create then enrol) -->
          @if (mode() === 'new') {
            <form
              [formGroup]="newForm"
              (ngSubmit)="submitAddNew()"
              novalidate
              class="rounded-xl bg-white border border-gray-200 shadow-card p-5 space-y-4"
            >
              <div class="text-sm font-semibold text-gray-900">Nouvel etudiant</div>
              <div class="grid grid-cols-1 sm:grid-cols-3 gap-4">
                <div>
                  <label class="block text-xs font-medium text-gray-700 mb-1">Prenom</label>
                  <input
                    type="text"
                    formControlName="prenom"
                    maxlength="100"
                    class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
                  />
                  @if (newForm.controls.prenom.touched && newForm.controls.prenom.invalid) {
                    <p class="text-xs text-status-danger mt-1">Le prenom est obligatoire.</p>
                  }
                </div>
                <div>
                  <label class="block text-xs font-medium text-gray-700 mb-1">Nom</label>
                  <input
                    type="text"
                    formControlName="nom"
                    maxlength="100"
                    class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
                  />
                  @if (newForm.controls.nom.touched && newForm.controls.nom.invalid) {
                    <p class="text-xs text-status-danger mt-1">Le nom est obligatoire.</p>
                  }
                </div>
                <div>
                  <label class="block text-xs font-medium text-gray-700 mb-1">N&deg; inscription</label>
                  <input
                    type="text"
                    formControlName="numeroInscription"
                    maxlength="50"
                    class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
                  />
                  @if (newForm.controls.numeroInscription.touched && newForm.controls.numeroInscription.invalid) {
                    <p class="text-xs text-status-danger mt-1">Le numero est obligatoire.</p>
                  }
                </div>
              </div>
              @if (addError()) {
                <p role="alert" class="text-xs text-status-danger">{{ addError() }}</p>
              }
              <div class="flex items-center justify-end gap-2">
                <button
                  type="button"
                  (click)="cancelAdd()"
                  class="px-3 py-1.5 rounded-lg border border-gray-300 text-gray-700 text-sm font-medium hover:bg-gray-50"
                >
                  Annuler
                </button>
                <button
                  type="submit"
                  [disabled]="newForm.invalid || submitting()"
                  class="px-3 py-1.5 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  {{ submitting() ? 'Ajout…' : 'Ajouter et inscrire' }}
                </button>
              </div>
            </form>
          }

          <!-- Enrol an existing student from the directory -->
          @if (mode() === 'existing') {
            <div class="rounded-xl bg-white border border-gray-200 shadow-card p-5 space-y-3">
              <div class="flex items-center justify-between gap-3">
                <div class="text-sm font-semibold text-gray-900">Ajouter un etudiant existant</div>
                <button
                  type="button"
                  (click)="cancelAdd()"
                  class="text-xs text-gray-500 hover:text-gray-800"
                >
                  Fermer
                </button>
              </div>
              <input
                type="text"
                [value]="search()"
                (input)="search.set($any($event.target).value)"
                placeholder="Rechercher par nom ou numero…"
                class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
              />
              @if (addError()) {
                <p role="alert" class="text-xs text-status-danger">{{ addError() }}</p>
              }
              <div class="max-h-64 overflow-y-auto rounded-lg border border-gray-100 divide-y divide-gray-50">
                @for (e of filteredAvailable(); track e.id) {
                  <button
                    type="button"
                    [disabled]="enrollingId() === e.id"
                    (click)="enrolExisting(e)"
                    class="w-full flex items-center justify-between gap-3 px-3 py-2 text-left text-sm hover:bg-surface disabled:opacity-50"
                  >
                    <span class="text-gray-800">{{ e.prenom }} {{ e.nom }}</span>
                    <span class="text-xs text-gray-400">
                      {{ e.numero_inscription || '—' }}
                      @if (enrollingId() === e.id) { <span class="text-gray-400">· …</span> }
                    </span>
                  </button>
                } @empty {
                  <p class="px-3 py-4 text-sm text-gray-400 text-center">
                    @if (availableStudents().length === 0) {
                      Tous les etudiants du repertoire sont deja inscrits.
                    } @else {
                      Aucun etudiant ne correspond a la recherche.
                    }
                  </p>
                }
              </div>
              <p class="text-xs text-gray-400">
                Le repertoire est partage entre toutes les matieres (pas de filtre par matiere cote
                scoring — #86).
              </p>
            </div>
          }
        </div>
      }

      @if (rows().length === 0) {
        <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card">
          <p class="text-gray-700 mb-1">Aucun etudiant inscrit a cet examen.</p>
          <p class="text-sm text-gray-500">
            @if (editable()) {
              Ajoutez un nouvel etudiant ou inscrivez-en un depuis le repertoire pour commencer.
            } @else {
              La liste des inscrits apparaitra ici.
            }
          </p>
        </div>
      } @else {
        <!-- summary -->
        <div class="flex flex-wrap items-center gap-x-6 gap-y-1 mb-4 text-sm">
          <span class="text-gray-900 font-semibold">{{ rows().length }} etudiant(s)</span>
          @if (presentsCount() > 0) {
            <span class="text-gray-500">{{ presentsCount() }} present(s)</span>
          }
          @if (absentsCount() > 0) {
            <span class="text-gray-500">{{ absentsCount() }} absent(s)</span>
          }
        </div>

        <div class="rounded-xl bg-white border border-gray-200 shadow-card overflow-hidden">
          <table class="w-full text-sm">
            <thead>
              <tr class="text-left text-xs text-gray-400 border-b border-gray-100">
                <th class="px-4 py-2.5 font-medium w-8">#</th>
                <th class="px-4 py-2.5 font-medium">Etudiant</th>
                <th class="px-4 py-2.5 font-medium">N&deg; inscription</th>
                <th class="px-4 py-2.5 font-medium">N&deg; echantillon</th>
                <th class="px-4 py-2.5 font-medium">Presence</th>
                <th class="px-4 py-2.5 font-medium text-right">Note</th>
                @if (editable()) {
                  <th class="px-4 py-2.5 font-medium text-right w-32"></th>
                }
              </tr>
            </thead>
            <tbody class="divide-y divide-gray-50">
              @for (r of rows(); track r.participationId; let i = $index) {
                <tr class="hover:bg-surface">
                  <td class="px-4 py-2.5 text-gray-400">{{ i + 1 }}</td>
                  <td class="px-4 py-2.5 text-gray-800 font-medium">{{ displayName(r) }}</td>
                  <td class="px-4 py-2.5 text-gray-600">{{ r.numeroInscription || '—' }}</td>
                  <td class="px-4 py-2.5 text-gray-600">{{ r.numEchantillon || '—' }}</td>
                  <td class="px-4 py-2.5">
                    @if (r.present === true) {
                      <span class="text-xs px-2 py-0.5 rounded-full bg-status-success text-white">Present</span>
                    } @else if (r.present === false) {
                      <span class="text-xs px-2 py-0.5 rounded-full bg-status-danger/10 text-status-danger">Absent</span>
                    } @else {
                      <span class="text-xs text-gray-400">—</span>
                    }
                  </td>
                  <td class="px-4 py-2.5 text-right text-gray-700">
                    {{ r.note != null ? r.note : '—' }}
                  </td>
                  @if (editable()) {
                    <td class="px-4 py-2.5 text-right">
                      @if (confirmRemoveId() === r.participationId) {
                        <span class="inline-flex items-center gap-1.5">
                          <button
                            type="button"
                            [disabled]="removingId() === r.participationId"
                            (click)="confirmRemove(r)"
                            class="px-2 py-1 rounded-lg bg-status-danger text-white text-xs font-medium hover:opacity-90 disabled:opacity-40"
                          >
                            {{ removingId() === r.participationId ? '…' : 'Retirer' }}
                          </button>
                          <button
                            type="button"
                            [disabled]="removingId() === r.participationId"
                            (click)="cancelRemove()"
                            class="px-2 py-1 rounded-lg border border-gray-300 text-gray-600 text-xs hover:bg-gray-50 disabled:opacity-40"
                          >
                            Annuler
                          </button>
                        </span>
                      } @else {
                        <button
                          type="button"
                          (click)="askRemove(r)"
                          class="text-xs text-gray-500 hover:text-status-danger"
                        >
                          Retirer
                        </button>
                      }
                    </td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>

        @if (removeError()) {
          <p class="text-xs text-status-danger mt-2">{{ removeError() }}</p>
        }

        <p class="text-xs text-gray-400 mt-3">
          Presence, note et numero d'echantillon se remplissent le jour de l'examen — non editables ici.
        </p>
      }
    }
  `,
})
export class EtudiantsComponent {
  private readonly scoring = inject(ScoringApiService);
  private readonly store = inject(ExamenWorkspaceStore);
  private readonly fb = inject(FormBuilder);

  /** Inherited from the parent examens/:id route via withComponentInputBinding(). */
  readonly id = input.required<string>();

  readonly rows = signal<RosterRow[]>([]);
  /** Full student directory, kept to resolve names + guard duplicate numéros. */
  readonly directory = signal<EtudiantSummary[]>([]);
  readonly loading = signal(true);
  readonly error = signal(false);

  /** Roster authoring is allowed only while the exam is BROUILLON or CONFIGURE. */
  readonly editable = computed(() => {
    const e = this.store.exam();
    return e ? e.statut === 'BROUILLON' || e.statut === 'CONFIGURE' : false;
  });

  readonly presentsCount = computed(() => this.rows().filter((r) => r.present === true).length);
  readonly absentsCount = computed(() => this.rows().filter((r) => r.present === false).length);

  // ---- authoring UI state -------------------------------------------------
  readonly mode = signal<'idle' | 'new' | 'existing'>('idle');
  readonly submitting = signal(false);
  readonly enrollingId = signal<number | null>(null);
  readonly addError = signal<string | null>(null);
  readonly search = signal('');

  readonly confirmRemoveId = signal<number | null>(null);
  readonly removingId = signal<number | null>(null);
  readonly removeError = signal<string | null>(null);

  readonly newForm = this.fb.nonNullable.group({
    prenom: ['', [Validators.required, Validators.maxLength(100)]],
    nom: ['', [Validators.required, Validators.maxLength(100)]],
    numeroInscription: ['', [Validators.required, Validators.maxLength(50)]],
  });

  /** etudiantIds already on this exam's roster, for the existing-student filter. */
  private readonly enrolledIds = computed(
    () => new Set(this.rows().map((r) => r.etudiantId).filter((x): x is number => x != null)),
  );

  /** Directory students not yet enrolled — the pool the existing-picker draws from. */
  readonly availableStudents = computed(() => {
    const enrolled = this.enrolledIds();
    return this.directory()
      .filter((e) => !enrolled.has(e.id))
      .sort((a, b) => (a.nom ?? '').localeCompare(b.nom ?? '') || (a.prenom ?? '').localeCompare(b.prenom ?? ''));
  });

  readonly filteredAvailable = computed(() => {
    const q = this.search().trim().toLowerCase();
    if (!q) return this.availableStudents();
    return this.availableStudents().filter((e) =>
      `${e.prenom ?? ''} ${e.nom ?? ''} ${e.numero_inscription ?? ''}`.toLowerCase().includes(q),
    );
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
    this.mode.set('idle');
    forkJoin({
      participations: this.scoring.listParticipations(examId),
      etudiants: this.scoring.listEtudiants(),
    }).subscribe({
      next: ({ participations, etudiants }) => {
        this.directory.set(etudiants);
        this.rows.set(this.buildRows(participations, etudiants));
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  /** Join participations (enrolments) to the student directory, ordered by name. */
  private buildRows(
    participations: ParticipationSummary[],
    etudiants: EtudiantSummary[],
  ): RosterRow[] {
    const byId = new Map<number, EtudiantSummary>();
    for (const e of etudiants) byId.set(e.id, e);

    return participations
      .map((p) => {
        const e = p.etudiantId != null ? byId.get(p.etudiantId) : undefined;
        return {
          participationId: p.id,
          etudiantId: p.etudiantId,
          nom: e?.nom ?? '',
          prenom: e?.prenom ?? '',
          numeroInscription: e?.numero_inscription ?? null,
          numEchantillon: p.num_echantillon,
          present: p.est_present,
          note: p.note,
        };
      })
      .sort((a, b) => (a.nom || '').localeCompare(b.nom || '') || (a.prenom || '').localeCompare(b.prenom || ''));
  }

  // ---- add-new (2-step) ---------------------------------------------------

  openAddNew(): void {
    this.newForm.reset({ prenom: '', nom: '', numeroInscription: '' });
    this.addError.set(null);
    this.mode.set('new');
  }

  openAddExisting(): void {
    this.addError.set(null);
    this.search.set('');
    this.mode.set('existing');
  }

  cancelAdd(): void {
    this.mode.set('idle');
    this.addError.set(null);
  }

  submitAddNew(): void {
    if (this.newForm.invalid || this.submitting()) return;
    const raw = this.newForm.getRawValue();
    const numero = raw.numeroInscription.trim();

    // Guard the backend's missing uniqueness check: a numéro already in the
    // directory would silently create a second student. Point the user at the
    // existing-student path instead of forking the directory.
    const clash = this.directory().find((e) => normNumero(e.numero_inscription) === normNumero(numero));
    if (clash) {
      this.addError.set(
        `Le numero « ${numero} » est deja utilise (${clash.prenom ?? ''} ${clash.nom ?? ''}). ` +
          `Utilisez « Ajouter un etudiant existant » pour l'inscrire.`,
      );
      return;
    }

    this.submitting.set(true);
    this.addError.set(null);
    const examId = Number(this.id());
    this.scoring
      .createEtudiant({ prenom: raw.prenom.trim(), nom: raw.nom.trim(), numero_inscription: numero })
      .subscribe({
        next: (student) => {
          // Student now exists in the directory regardless of what happens next.
          this.directory.update((list) => [...list, student]);
          this.scoring
            .createParticipation({ examen_id: examId, etudiantId: student.id })
            .subscribe({
              next: (p) => {
                this.submitting.set(false);
                this.mode.set('idle');
                this.appendRow(p, student);
              },
              error: (err: HttpErrorResponse) => {
                // Student was created but enrolment failed: don't lose that work —
                // keep them in the directory and steer to the existing-picker.
                this.submitting.set(false);
                this.mode.set('existing');
                this.search.set(numero);
                this.addError.set(
                  `Etudiant cree mais inscription echouee (${this.httpMessage(err)}). ` +
                    `Selectionnez-le ci-dessous pour l'inscrire.`,
                );
              },
            });
        },
        error: (err: HttpErrorResponse) => {
          this.submitting.set(false);
          this.addError.set(this.httpMessage(err));
        },
      });
  }

  // ---- enrol existing -----------------------------------------------------

  enrolExisting(e: EtudiantSummary): void {
    if (this.enrollingId() != null) return;
    // Defensive: the picker already excludes enrolled students, but guard the
    // backend's missing duplicate-participation check in case state raced.
    if (this.enrolledIds().has(e.id)) {
      this.addError.set('Cet etudiant est deja inscrit a cet examen.');
      return;
    }
    this.enrollingId.set(e.id);
    this.addError.set(null);
    this.scoring
      .createParticipation({ examen_id: Number(this.id()), etudiantId: e.id })
      .subscribe({
        next: (p) => {
          this.enrollingId.set(null);
          this.appendRow(p, e);
          // Stay open so several students can be enrolled in a row.
        },
        error: (err: HttpErrorResponse) => {
          this.enrollingId.set(null);
          this.addError.set(this.httpMessage(err));
        },
      });
  }

  // ---- remove -------------------------------------------------------------

  askRemove(r: RosterRow): void {
    this.removeError.set(null);
    this.confirmRemoveId.set(r.participationId);
  }

  cancelRemove(): void {
    this.confirmRemoveId.set(null);
    this.removeError.set(null);
  }

  confirmRemove(r: RosterRow): void {
    if (this.removingId() === r.participationId) return;
    this.removingId.set(r.participationId);
    this.removeError.set(null);
    this.scoring.deleteParticipation(r.participationId).subscribe({
      next: () => {
        this.removingId.set(null);
        this.confirmRemoveId.set(null);
        this.rows.update((list) => list.filter((x) => x.participationId !== r.participationId));
      },
      error: (err: HttpErrorResponse) => {
        this.removingId.set(null);
        this.confirmRemoveId.set(null);
        this.removeError.set(`Echec du retrait : ${this.httpMessage(err)}`);
      },
    });
  }

  // ---- helpers ------------------------------------------------------------

  /** Add a freshly-created enrolment to the roster, keeping it name-sorted. */
  private appendRow(p: ParticipationSummary, e: EtudiantSummary): void {
    const row: RosterRow = {
      participationId: p.id,
      etudiantId: p.etudiantId ?? e.id,
      nom: e.nom ?? '',
      prenom: e.prenom ?? '',
      numeroInscription: e.numero_inscription ?? null,
      numEchantillon: p.num_echantillon,
      present: p.est_present,
      note: p.note,
    };
    this.rows.update((list) =>
      [...list, row].sort(
        (a, b) => (a.nom || '').localeCompare(b.nom || '') || (a.prenom || '').localeCompare(b.prenom || ''),
      ),
    );
  }

  private httpMessage(err: HttpErrorResponse): string {
    if (err.status === 403) return "Vous n'avez pas les droits requis.";
    if ((err.status === 400 || err.status === 409) && typeof err.error?.message === 'string') {
      return err.error.message;
    }
    return 'Reessayez.';
  }

  /** Full name, or a stable fallback for an enrolment whose student row is missing. */
  displayName(r: RosterRow): string {
    const full = `${r.prenom} ${r.nom}`.trim();
    return full || (r.etudiantId != null ? `Etudiant #${r.etudiantId}` : 'Etudiant inconnu');
  }
}
