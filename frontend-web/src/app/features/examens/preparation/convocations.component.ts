import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ExamApiService } from '../../../core/api/exam-api.service';
import { ScoringApiService } from '../../../core/api/scoring-api.service';
import { EnvoiConvocationsResult, ExamenResponse } from '../../../core/api/models';
import { ExamenWorkspaceStore } from '../workspace/examen-workspace.store';

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
  templateUrl: './convocations.component.html',
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
