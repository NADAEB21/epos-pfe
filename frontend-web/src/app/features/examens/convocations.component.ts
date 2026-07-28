import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ScoringApiService } from '../../core/api/scoring-api.service';
import { EnvoiConvocationsResult, ExamenResponse } from '../../core/api/models';
import { ExamenWorkspaceStore } from './examen-workspace.store';

/** Default when a station count is missing — display only, never a schedule. */
const DEFAULT_DUREE_MIN = 15;

/**
 * One printable slip. A VIEW of the backend's ConvocationDTO — every field here
 * is read from the API, none is derived locally (#227).
 */
interface Convocation {
  participationId: number;
  /** #227 — the directory record, needed to patch a missing address in place. */
  etudiantId: number | null;
  lotNumero: number;
  /** #147 — the day the student's lot runs (yyyy-MM-dd; lot.jour ?? dateExamen). */
  jour: string | null;
  /** Wave arrival time (HH:mm) as computed BY THE BACKEND — see ConvocationService. */
  reportTime: string;
  nom: string;
  prenom: string;
  numeroInscription: string | null;
  /** #227 — the student's address, or null when the roster never carried one. */
  email: string | null;
  /** #256 — position in the imported sheet; null for hand-added students. */
  ordreImport: number | null;
  /** #227 — when this student's convocation was e-mailed; null = never. */
  envoyeeA: string | null;
}

/**
 * Convocations — per-student exam-day call slips (gap #2). A CONFIGURE-time
 * deliverable: once the roster is partitioned into lots (waves), each student
 * gets a printable convocation telling them WHICH wave they're in and WHEN to
 * report.
 *
 * <p><b>Derived, not stored.</b> No arrival window is persisted anywhere — the
 * Lot entity carries only numeroLot/tailleLot, and rotations (which hold
 * debutCreneau) are generated day-of, per-lot, after presence. So the report
 * time is derived here exactly as the day-of generator will anchor it
 * (RotationGenerationService): a lot runs back-to-back after the preceding
 * circuits, i.e. heureDebut + (numeroLot − 1) · K · dureeStationMin, where K is
 * the station count. The slip gives the WAVE arrival time; the student's
 * within-wave station order is assigned on the day (depends on présence), so it
 * is intentionally not promised here.
 *
 * <p><b>Order (#256).</b> Slips are ordered by lot, then by the student's
 * position in the imported sheet (`ordre_import`), never alphabetically. The
 * supervisor ruled the sheet's row order IS the official listing order, and the
 * convocation is that listing. Hand-added students (`ordre_import` null) sort
 * last within their lot, by name.
 *
 * <p><b>Venue is deliberately absent (ADR-0014-A §4).</b> A convocation carries
 * the lot + the day ONLY, never a room. The venue field was considered and
 * rejected as having no consumer — its absence is a decision, not a gap.
 *
 * <p><b>Sending is not built yet (#227).</b> `Etudiant.email` now exists and is
 * surfaced here, so the CSV is a working mail-merge source. There is still no
 * convocation sender: auth-service's `EmailService` exposes only
 * `sendPasswordResetEmail` and ships disabled (`MAIL_ENABLED:false`). Until one
 * exists these stay print/CSV deliverables — and the UI says so honestly,
 * including how many students have no address at all.
 */
@Component({
  selector: 'app-convocations',
  standalone: true,
  imports: [RouterLink, DatePipe],
  template: `
    @if (loading()) {
      <div class="h-24 rounded-xl bg-gray-200 animate-pulse"></div>
    } @else if (error()) {
      <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card">
        <p class="text-gray-700">Impossible de charger les convocations.</p>
      </div>
    } @else {
      <!-- Toolbar (hidden when printing) -->
      <div class="flex flex-wrap items-center justify-between gap-3 mb-4 no-print">
        <div>
          <h2 class="text-lg font-semibold text-gray-900">Convocations</h2>
          <p class="text-sm text-gray-500">
            {{ convocations().length }} étudiant(s) répartis &middot; {{ lotCount() }} lot(s)
            &middot; circuit ≈ {{ circuitDureeMin() }} min
          </p>
        </div>
        @if (convocations().length > 0) {
          <div class="flex gap-2">
            <!-- #227 — envoi. Désactivé s'il n'y a personne à joindre : proposer
                 « Envoyer » à 0 destinataire ne peut que décevoir. -->
            <button
              type="button"
              (click)="envoyer()"
              [disabled]="envoiEnCours() || avecEmailCount() === 0"
              class="rounded-lg bg-brand px-3 py-2 text-sm font-medium text-white hover:bg-brand-dark disabled:opacity-40 disabled:cursor-not-allowed"
              [title]="
                avecEmailCount() === 0
                  ? 'Aucun étudiant n\\'a d\\'adresse e-mail'
                  : 'Envoyer la convocation aux ' + avecEmailCount() + ' étudiant(s) joignable(s)'
              "
            >
              {{
                envoiEnCours()
                  ? 'Envoi…'
                  : 'Envoyer par e-mail (' + avecEmailCount() + ')'
              }}
            </button>
            <button
              type="button"
              (click)="print()"
              class="rounded-lg bg-brand px-3 py-2 text-sm font-medium text-white hover:bg-brand-dark"
            >
              Imprimer / PDF
            </button>
            <button
              type="button"
              (click)="exportCsv()"
              class="rounded-lg border border-gray-300 px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
            >
              Exporter (CSV)
            </button>
          </div>
        }
      </div>

      <!-- #227 — e-mail coverage. The responsable must know BEFORE distributing
           how many students they cannot reach; a silent gap reads as "everyone
           was convoked". -->
      @if (convocations().length > 0) {
        @if (sansEmailCount() === 0) {
          <div class="no-print mb-3 rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-2 text-sm text-emerald-800">
            <strong>{{ avecEmailCount() }} étudiant(s)</strong> ont une adresse e-mail — le fichier
            CSV peut servir de source de publipostage.
          </div>
        } @else {
          <!-- #227 — the fix lives WHERE the problem is discovered. Sending the
               teacher to another tab to re-upload a spreadsheet just to type an
               address they already know was the wrong answer: they know it, so
               let them type it here. -->
          <div class="no-print mb-3 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
            <p class="font-medium">
              {{ sansEmailCount() }} étudiant(s) sur {{ convocations().length }} n'ont pas
              d'adresse e-mail
            </p>
            <p class="mt-1">
              Leur convocation devra être remise en main propre. Vous pouvez saisir leur adresse
              ici :
            </p>

            <ul class="mt-3 space-y-2">
              @for (c of sansEmail(); track c.participationId) {
                <li class="flex flex-wrap items-center gap-2">
                  <span class="min-w-44 font-medium text-gray-800">
                    {{ c.prenom }} {{ c.nom }}
                  </span>
                  <span class="text-xs text-amber-700/70">Lot {{ c.lotNumero }}</span>
                  <input
                    type="email"
                    [value]="draftFor(c.etudiantId)"
                    (input)="setDraft(c.etudiantId, $any($event.target).value)"
                    (keydown.enter)="saveEmail(c)"
                    placeholder="prenom.nom@etu.tn"
                    maxlength="255"
                    class="w-60 rounded-lg border border-amber-300 bg-white px-2 py-1 text-sm text-gray-800 focus:outline-none focus:ring-2 focus:ring-brand"
                  />
                  <button
                    type="button"
                    (click)="saveEmail(c)"
                    [disabled]="savingId() === c.etudiantId || !draftFor(c.etudiantId).trim()"
                    class="px-2.5 py-1 rounded-lg bg-brand text-white text-xs font-medium hover:bg-brand-dark disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    {{ savingId() === c.etudiantId ? '…' : 'Enregistrer' }}
                  </button>
                </li>
              }
            </ul>

            @if (saveError()) {
              <p role="alert" class="mt-2 text-xs text-status-danger">{{ saveError() }}</p>
            }
            <p class="mt-3 text-xs text-amber-700/80">
              Pour en saisir beaucoup d'un coup, ajoutez une colonne <b>email</b> au fichier et
              réimportez-le depuis l'onglet
              <a [routerLink]="['../etudiants']" class="underline hover:no-underline">Étudiants</a>.
            </p>
          </div>
        }
      }

      <!-- #227 — bilan d'envoi. Jamais un « envoyé ! » global : le responsable
           doit voir qui est parti, qui ne l'est pas, et si quoi que ce soit est
           réellement parti (la messagerie est coupée par défaut). -->
      @if (envoiResult(); as env) {
        <div
          class="no-print mb-4 rounded-lg border px-4 py-3 text-sm"
          [class]="
            env.simule
              ? 'no-print mb-4 rounded-lg border px-4 py-3 text-sm border-blue-200 bg-blue-50 text-blue-800'
              : env.echecs > 0
                ? 'no-print mb-4 rounded-lg border px-4 py-3 text-sm border-amber-200 bg-amber-50 text-amber-800'
                : 'no-print mb-4 rounded-lg border px-4 py-3 text-sm border-emerald-200 bg-emerald-50 text-emerald-800'
          "
        >
          @if (env.simule) {
            <!-- #227 — le responsable ne PEUT PAS activer l'envoi lui-même (c'est
                 une config serveur). Lui montrer « MAIL_ENABLED=true » et « SMTP »
                 lui donnait du vocabulaire d'informaticien pour un problème qu'il
                 ne peut pas résoudre. On lui dit ce que ÇA change pour lui, et à
                 qui s'adresser. -->
            <p class="font-medium">Aucun e-mail n'a été envoyé.</p>
            <p class="mt-1">
              L'envoi par e-mail n'est pas encore activé sur ce serveur.
              <strong>Imprimez les convocations</strong> pour les distribuer.
            </p>
          } @else {
            <p class="font-medium">
              {{ env.envoyes }} convocation(s) envoyée(s) par e-mail.
            </p>
          }
          @if (env.sansAdresse > 0) {
            <p class="mt-1">
              {{ env.sansAdresse }} étudiant(s) sans adresse — à convoquer en main propre.
            </p>
          }
          @if (env.echecs > 0) {
            <p class="mt-1 font-medium">{{ env.echecs }} échec(s) :</p>
            <ul class="mt-1 list-disc pl-5">
              @for (l of echecs(); track l.participationId) {
                <li>{{ l.prenom }} {{ l.nom }} ({{ l.email }}) — {{ l.message }}</li>
              }
            </ul>
          }
        </div>
      }
      @if (envoiError()) {
        <p role="alert" class="no-print mb-4 text-sm text-status-danger">{{ envoiError() }}</p>
      }

      <!-- What this document does and does not promise -->
      <div class="no-print mb-5 rounded-lg border border-gray-200 bg-gray-50 px-4 py-3 text-sm text-gray-600">
        <p class="font-medium text-gray-700">Contenu de la convocation</p>
        <p class="mt-1">
          Chaque convocation porte le <strong>lot</strong>, le <strong>jour</strong> et
          l'<strong>heure de convocation du lot</strong> (début de l'examen + décalage de la vague),
          calculée comme le jour J. L'ordre de passage par station est attribué sur place, après
          l'appel — il n'est donc pas promis ici. L'<strong>envoi automatique par e-mail n'existe
          pas encore</strong> : imprimez les convocations ou exportez le CSV.
        </p>
      </div>

      @if (convocations().length === 0) {
        <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card no-print">
          <p class="text-gray-700 mb-1">Aucun lot réparti.</p>
          <p class="text-sm text-gray-500 mb-3">
            Répartissez d'abord les étudiants en lots pour générer leurs convocations.
          </p>
          <a [routerLink]="['../lots']" class="text-sm text-brand hover:underline">Aller aux lots &rarr;</a>
        </div>
      } @else {
        @if (unassignedCount() > 0) {
          <div class="no-print mb-4 rounded-lg border border-gray-200 bg-gray-50 px-4 py-2 text-sm text-gray-600">
            {{ unassignedCount() }} étudiant(s) non encore répartis — sans convocation.
            Relancez la répartition après les avoir inscrits.
          </div>
        }

        <!-- Print sheet: one slip per student -->
        <div id="convocation-print">
          @for (c of convocations(); track c.participationId) {
            <article class="convocation-card rounded-xl border border-gray-200 bg-white p-6 shadow-card mb-4">
              <div class="flex items-start justify-between border-b border-gray-100 pb-3 mb-3">
                <div>
                  <p class="text-xs uppercase tracking-wide text-gray-400">Convocation à l'examen</p>
                  <h3 class="text-lg font-semibold text-gray-900">{{ exam()?.nom }}</h3>
                </div>
                <div class="text-right">
                  <p class="text-xs text-gray-400">Date</p>
                  <!-- #147 — the STUDENT'S day: their lot's jour when the cohort
                       is spread over several days, else the exam's own date. -->
                  <p class="text-sm font-medium text-gray-900">
                    {{ (c.jour ?? exam()?.dateExamen) | date: 'dd/MM/yyyy' }}
                  </p>
                </div>
              </div>
              <dl class="grid grid-cols-2 gap-x-6 gap-y-2 text-sm">
                <div>
                  <dt class="text-gray-400">Étudiant</dt>
                  <dd class="font-medium text-gray-900">{{ c.prenom }} {{ c.nom }}</dd>
                </div>
                <div>
                  <dt class="text-gray-400">N° d'inscription</dt>
                  <dd class="font-medium text-gray-900">{{ c.numeroInscription ?? '—' }}</dd>
                </div>
                <div>
                  <dt class="text-gray-400">Lot (vague)</dt>
                  <dd class="font-medium text-gray-900">Lot {{ c.lotNumero }}</dd>
                </div>
                <div>
                  <dt class="text-gray-400">Heure de convocation</dt>
                  <dd class="font-semibold text-brand-dark">{{ c.reportTime }}</dd>
                </div>
              </dl>
              <p class="mt-4 text-xs text-gray-400">
                Présentez-vous 15 minutes avant l'heure indiquée. L'ordre de passage par station
                vous sera communiqué sur place.
              </p>
              <!-- #227 — screen-only: the slip the student receives must not
                   carry their own address, but the responsable needs to see at
                   a glance who has to be handed one in person. -->
              @if (!c.email) {
                <p class="no-print mt-2 text-xs font-medium text-amber-700">
                  Pas d'adresse e-mail &middot; à remettre en main propre
                </p>
              }
            </article>
          }
        </div>
      }
    }
  `,
})
export class ConvocationsComponent {
  private readonly examApi = inject(ExamApiService);
  private readonly scoring = inject(ScoringApiService);
  private readonly store = inject(ExamenWorkspaceStore);

  /** Inherited from the parent examens/:id route via withComponentInputBinding(). */
  readonly id = input.required<string>();

  readonly exam = signal<ExamenResponse | null>(null);
  readonly convocations = signal<Convocation[]>([]);
  readonly unassignedCount = signal(0);
  readonly stationCount = signal(0);
  readonly loading = signal(true);
  readonly error = signal(false);

  readonly lotCount = computed(() => new Set(this.convocations().map((c) => c.lotNumero)).size);
  readonly circuitDureeMin = computed(() => this.stationCount() * this.dureeMin());

  /** #227 — how many convoked students can actually be reached by e-mail. */
  readonly avecEmailCount = computed(
    () => this.convocations().filter((c) => !!c.email?.trim()).length,
  );
  readonly sansEmailCount = computed(() => this.convocations().length - this.avecEmailCount());

  /** #227 — the students the responsable cannot reach, in listing order. */
  readonly sansEmail = computed(() =>
    this.convocations().filter((c) => !c.email?.trim() && c.etudiantId != null),
  );

  // ---- #227 envoi ----------------------------------------------------------
  readonly envoiEnCours = signal(false);
  readonly envoiResult = signal<EnvoiConvocationsResult | null>(null);
  readonly envoiError = signal<string | null>(null);

  readonly echecs = computed(() => this.envoiResult()?.lignes.filter((l) => l.statut === 'ECHEC') ?? []);

  /** How many already received theirs — drives the re-send warning. */
  readonly dejaEnvoyeesCount = computed(() => this.convocations().filter((c) => !!c.envoyeeA).length);

  /**
   * Sends to every reachable student.
   *
   * <p>Re-sending is CONFIRMED, never silent: the second click would put a
   * duplicate convocation in a student's inbox, and the responsable can't take
   * that back. The stored send date is what lets us ask the question at all.
   */
  envoyer(): void {
    const deja = this.dejaEnvoyeesCount();
    if (deja > 0) {
      const ok = window.confirm(
        `${deja} étudiant(s) ont déjà reçu leur convocation.\n\n` +
          `Renvoyer enverra un nouvel e-mail à tous les étudiants joignables, y compris eux. Continuer ?`,
      );
      if (!ok) return;
    }
    this.envoiEnCours.set(true);
    this.envoiError.set(null);
    this.envoiResult.set(null);
    this.scoring.envoyerConvocations(Number(this.id())).subscribe({
      next: (res) => {
        this.envoiResult.set(res);
        this.envoiEnCours.set(false);
        // Re-read: the send stamped convocationEnvoyeeA, and that timestamp is
        // what guards the NEXT click.
        this.load(Number(this.id()));
        this.store.marquerConvocationsFaites();
      },
      error: () => {
        this.envoiEnCours.set(false);
        this.envoiError.set("Échec de l'envoi des convocations. Réessayez.");
      },
    });
  }

  /** Per-student drafts for the quick-fix inputs, keyed by etudiantId. */
  private readonly drafts = signal<Record<number, string>>({});
  readonly savingId = signal<number | null>(null);
  readonly saveError = signal<string | null>(null);

  draftFor(etudiantId: number | null): string {
    return etudiantId == null ? '' : (this.drafts()[etudiantId] ?? '');
  }

  setDraft(etudiantId: number | null, value: string): void {
    if (etudiantId == null) return;
    this.drafts.update((d) => ({ ...d, [etudiantId]: value }));
  }

  /**
   * Saves one address from the convocations screen itself. Patches ONLY the
   * e-mail (partial PUT, #215) and updates the local list, so the amber block
   * shrinks by one row and the coverage counts move immediately — no reload, no
   * trip through the Étudiants tab.
   */
  saveEmail(c: Convocation): void {
    const etudiantId = c.etudiantId;
    if (etudiantId == null) return;
    const value = this.draftFor(etudiantId).trim();
    if (!value) return;
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) {
      this.saveError.set(`Adresse invalide pour ${c.prenom} ${c.nom}.`);
      return;
    }
    this.savingId.set(etudiantId);
    this.saveError.set(null);
    this.scoring.updateEtudiant(etudiantId, { email: value }).subscribe({
      next: () => {
        this.convocations.update((list) =>
          list.map((x) => (x.etudiantId === etudiantId ? { ...x, email: value } : x)),
        );
        this.drafts.update((d) => {
          const { [etudiantId]: _removed, ...rest } = d;
          return rest;
        });
        this.savingId.set(null);
      },
      error: () => {
        this.savingId.set(null);
        this.saveError.set(`Échec de l'enregistrement pour ${c.prenom} ${c.nom}.`);
      },
    });
  }

  private dureeMin(): number {
    return this.exam()?.dureeStationMin ?? DEFAULT_DUREE_MIN;
  }

  constructor() {
    effect(
      () => {
        const examId = Number(this.id());
        if (!Number.isFinite(examId)) {
          this.error.set(true);
          this.loading.set(false);
          return;
        }
        this.load(examId);
      },
      { allowSignalWrites: true },
    );
  }

  private load(examId: number): void {
    this.loading.set(true);
    this.error.set(false);
    forkJoin({
      exam: this.examApi.getExamen(examId),
      stations: this.examApi.listStations(examId),
      convocations: this.scoring.listConvocations(examId),
      participations: this.scoring.listParticipations(examId),
    }).subscribe({
      next: ({ exam, stations, convocations, participations }) => {
        this.exam.set(exam);
        this.stationCount.set(stations.length);
        // #227 — the slips come from the backend already derived (lot, jour,
        // heure, listing order). Nothing is recomputed here; the sender uses the
        // very same objects, so the screen cannot drift from the e-mail.
        this.convocations.set(
          convocations.map((c) => ({
            participationId: c.participationId,
            etudiantId: c.etudiantId,
            lotNumero: c.lotNumero ?? 0,
            jour: c.jour,
            reportTime: c.heureConvocation ?? '—',
            nom: c.nom ?? '',
            prenom: c.prenom ?? '',
            numeroInscription: c.numero_inscription,
            email: c.email,
            ordreImport: c.ordre_import,
            envoyeeA: c.convocationEnvoyeeA,
          })),
        );
        // Enrolled students the backend left out (no lot yet ⇒ no wave ⇒ no
        // arrival time to promise). Counted so the responsable knows they exist.
        this.unassignedCount.set(Math.max(0, participations.length - convocations.length));
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }


  /** Print only the convocation sheet (global print rule keys off this body class). */
  print(): void {
    const body = document.body;
    const cleanup = () => {
      body.classList.remove('printing-convocations');
      window.removeEventListener('afterprint', cleanup);
    };
    window.addEventListener('afterprint', cleanup);
    body.classList.add('printing-convocations');
    window.print();
    // #185 — tell the stepper the Convocations step is handled.
    this.store.marquerConvocationsFaites();
  }

  /** Client-side CSV of all convocations — no backend round-trip. */
  exportCsv(): void {
    // #227 — Email last so the file drops straight into a mail-merge. Rows keep
    // the #256 listing order set in build(); never re-sort here.
    const header = ['Lot', 'Date', 'Heure', 'Nom', 'Prenom', 'Numero inscription', 'Email'];
    const lines = this.convocations().map((c) =>
      [
        c.lotNumero,
        c.jour ?? this.exam()?.dateExamen ?? '',
        c.reportTime,
        c.nom,
        c.prenom,
        c.numeroInscription ?? '',
        c.email ?? '',
      ].map((x) => this.csvCell(x)).join(','),
    );
    // #185 — tell the stepper the Convocations step is handled.
    this.store.marquerConvocationsFaites();
    const csv = [header.map((h) => this.csvCell(h)).join(','), ...lines].join('\r\n');
    const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `convocations-examen-${this.id()}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  private csvCell(value: unknown): string {
    const s = String(value ?? '');
    return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  }
}
