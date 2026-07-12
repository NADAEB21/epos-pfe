import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ScoringApiService } from '../../core/api/scoring-api.service';
import {
  EtudiantSummary,
  ExamenResponse,
  LotSummary,
  ParticipationSummary,
} from '../../core/api/models';

/** Backend defaults (RotationGenerationService): start 09:00, 15 min/station. */
const DEFAULT_HEURE_DEBUT = '09:00';
const DEFAULT_DUREE_MIN = 15;

/** One printable convocation slip for a single enrolled student. */
interface Convocation {
  participationId: number;
  lotNumero: number;
  /** Derived wave arrival time (HH:mm) — see waveStartMinutes(). */
  reportTime: string;
  nom: string;
  prenom: string;
  numeroInscription: string | null;
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
 * <p><b>DATA-BLOCKED (ADR-0011).</b> No salle on Lot/Station and no email on
 * Etudiant, so convocations cannot carry a room or be emailed — they are
 * print/CSV deliverables for hand distribution. Flagged in the UI.
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

      <!-- Data-gap disclosure -->
      <div class="no-print mb-5 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
        <p class="font-medium">Données disponibles à ce stade</p>
        <p class="mt-1">
          L'heure indiquée est l'<strong>heure de convocation du lot</strong> (heure de début de
          l'examen + décalage de la vague), calculée comme le jour J. La salle et l'ordre de
          passage par station ne sont pas encore connus ; l'envoi par e-mail n'est pas disponible
          (pas d'adresse étudiant). Convocations à imprimer / distribuer.
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
                  <p class="text-sm font-medium text-gray-900">{{ exam()?.dateExamen | date: 'dd/MM/yyyy' }}</p>
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
      participations: this.scoring.listParticipations(examId),
      etudiants: this.scoring.listEtudiants(),
      lots: this.scoring.listLots(examId),
    }).subscribe({
      next: ({ exam, stations, participations, etudiants, lots }) => {
        this.exam.set(exam);
        this.stationCount.set(stations.length);
        this.build(exam, stations.length, participations, etudiants, lots);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  /**
   * Joins the roster to names + lots and derives each slip's report time.
   * Wave start mirrors RotationGenerationService: heureDebut + (numeroLot−1)·K·durée.
   */
  private build(
    exam: ExamenResponse,
    k: number,
    participations: ParticipationSummary[],
    etudiants: EtudiantSummary[],
    lots: LotSummary[],
  ): void {
    const nameById = new Map<number, EtudiantSummary>();
    for (const e of etudiants) nameById.set(e.id, e);
    const lotNumById = new Map<number, number>();
    for (const l of lots) if (l.numeroLot != null) lotNumById.set(l.id, l.numeroLot);

    const baseMin = this.parseHeure(exam.heureDebut);
    const duree = exam.dureeStationMin ?? DEFAULT_DUREE_MIN;

    let unassigned = 0;
    const out: Convocation[] = [];
    for (const p of participations) {
      const lotNumero = p.lotId != null ? lotNumById.get(p.lotId) : undefined;
      if (lotNumero == null) {
        unassigned++;
        continue;
      }
      const e = p.etudiantId != null ? nameById.get(p.etudiantId) : undefined;
      const waveStart = baseMin + (lotNumero - 1) * k * duree;
      out.push({
        participationId: p.id,
        lotNumero,
        reportTime: this.formatMin(waveStart),
        nom: e?.nom ?? '',
        prenom: e?.prenom ?? '',
        numeroInscription: e?.numero_inscription ?? null,
      });
    }

    out.sort(
      (a, b) =>
        a.lotNumero - b.lotNumero ||
        (a.nom || '').localeCompare(b.nom || '') ||
        (a.prenom || '').localeCompare(b.prenom || ''),
    );
    this.convocations.set(out);
    this.unassignedCount.set(unassigned);
  }

  /** "HH:mm" → minutes since midnight; falls back to the backend default 09:00. */
  private parseHeure(heure: string | null): number {
    const src = heure ?? DEFAULT_HEURE_DEBUT;
    const [h, m] = src.split(':').map((x) => Number(x));
    if (!Number.isFinite(h) || !Number.isFinite(m)) return this.parseHeure(DEFAULT_HEURE_DEBUT);
    return h * 60 + m;
  }

  /** Minutes since midnight → "HH:mm" (wraps defensively at 24h). */
  private formatMin(total: number): string {
    const t = ((total % 1440) + 1440) % 1440;
    const h = Math.floor(t / 60);
    const m = t % 60;
    return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
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
  }

  /** Client-side CSV of all convocations — no backend round-trip. */
  exportCsv(): void {
    const header = ['Lot', 'Heure', 'Nom', 'Prenom', 'Numero inscription'];
    const lines = this.convocations().map((c) =>
      [c.lotNumero, c.reportTime, c.nom, c.prenom, c.numeroInscription ?? ''].map((x) => this.csvCell(x)).join(','),
    );
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
