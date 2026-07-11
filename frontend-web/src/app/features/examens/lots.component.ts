import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ScoringApiService } from '../../core/api/scoring-api.service';
import {
  EtudiantSummary,
  GenerationResult,
  LotSummary,
  ParticipationSummary,
} from '../../core/api/models';
import { ExamenWorkspaceStore } from './examen-workspace.store';

/** One student inside a lot, joined to the directory + carrying live present-state. */
interface LotMember {
  participationId: number;
  nom: string;
  prenom: string;
  numeroInscription: string | null;
  present: boolean | null;
}

/** A lot enriched with its members + the derived arrival window + per-lot UI state. */
interface LotView {
  id: number;
  numeroLot: number;
  statut: LotSummary['statut'];
  members: LotMember[];
  /** Back-to-back wave start: heureDebut + (numeroLot-1)·K·dureeStationMin. */
  arrivee: string | null;
}

/**
 * Lots tab — the two-phase wave workflow surface.
 *
 * <p><b>CONFIGURE (Phase 1 — pre-exam):</b> "Répartir en lots" partitions the
 * enrolled roster into waves of K stations × nbEtudiantsParStation. The result
 * is the pre-exam deliverable: each student's lot + arrival window. Re-runnable
 * (wipes the prior partition and any generated plan). This is the gate the
 * Lancement checklist reads as "Lots répartis".
 *
 * <p><b>EN_COURS (Phase 2 — exam day):</b> per lot, as the wave arrives: mark who
 * showed up (default all present), then generate that lot's OSCE rotations. The
 * gate is deliberately here, not at CONFIGURE — nothing students rely on changes
 * (their lot is stable; station order is a day-of thing), absences are handled
 * cleanly, and each lot is scoped on its own.
 *
 * <p>Rooms = station-name-as-room for now: the pre-exam info is lot + time +
 * venue; the exact station sequence is decided day-of by the generated circuit.
 */
@Component({
  selector: 'app-lots',
  standalone: true,
  imports: [RouterLink],
  template: `
    @if (loading()) {
      <div class="space-y-4 animate-pulse">
        <div class="h-10 rounded-lg bg-gray-200 w-1/3"></div>
        <div class="h-40 rounded-xl bg-gray-200"></div>
      </div>
    } @else if (error()) {
      <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card">
        <p class="text-gray-700 mb-1">Impossible de charger les lots.</p>
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
      <!-- Intro / sizing -->
      <section class="rounded-xl bg-white border border-gray-200 shadow-card p-5 mb-6">
        <h2 class="font-semibold text-gray-900 mb-1">Lots (vagues d'étudiants)</h2>
        <p class="text-sm text-gray-500">
          Un lot est une vague d'étudiants qui parcourent le circuit ensemble à un horaire donné.
          Taille d'un lot : {{ stationCount() }} station(s) × {{ capacite() }} =
          <span class="font-medium text-gray-700">{{ lotSize() }}</span> étudiants.
          @if (isConfigure()) {
            Les étudiants connaissent leur lot et leur horaire d'arrivée à l'avance ; les rotations
            sont générées le jour J, lot par lot.
          }
        </p>
      </section>

      <!-- CONFIGURE: répartition -->
      @if (isConfigure()) {
        <section class="rounded-xl bg-white border border-gray-200 shadow-card p-5 mb-6">
          <div class="flex items-center justify-between gap-4 mb-3">
            <h3 class="font-semibold text-gray-900">Répartition</h3>
            @if (lots().length > 0) {
              <span class="text-xs font-medium px-2 py-0.5 rounded-full bg-status-success text-white">
                {{ lots().length }} lot(s) répartis
              </span>
            }
          </div>

          @if (actionError()) {
            <p role="alert" class="text-sm text-status-danger mb-3">{{ actionError() }}</p>
          }

          @if (lots().length === 0) {
            <p class="text-sm text-gray-500 mb-3">
              Aucun lot pour l'instant. Répartissez les {{ rosterCount() }} étudiant(s) inscrit(s) en vagues.
            </p>
          } @else {
            <p class="text-sm text-gray-500 mb-3">
              Re-répartir reconstruit les lots à partir du roster actuel et efface toute planification déjà générée.
            </p>
          }

          <button
            type="button"
            [disabled]="busy() || rosterCount() === 0"
            (click)="repartir()"
            class="inline-flex items-center px-4 py-2 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {{ busy() ? 'Répartition…' : lots().length > 0 ? 'Re-répartir en lots' : 'Répartir en lots' }}
          </button>
          @if (rosterCount() === 0) {
            <p class="text-xs text-gray-400 mt-2">
              Inscrivez d'abord des étudiants dans l'onglet
              <a [routerLink]="['../etudiants']" class="text-brand hover:underline">Étudiants</a>.
            </p>
          }
        </section>
      }

      <!-- Lot list / per-lot actions -->
      @if (lotViews().length > 0) {
        <div class="space-y-4">
          @for (lot of lotViews(); track lot.id) {
            <section class="rounded-xl bg-white border border-gray-200 shadow-card p-5">
              <div class="flex items-center justify-between gap-4 mb-1">
                <h3 class="font-semibold text-gray-900">Lot {{ lot.numeroLot }}</h3>
                <span
                  class="text-xs font-medium px-2 py-0.5 rounded-full"
                  [class.bg-gray-100]="lot.statut === 'EN_ATTENTE'"
                  [class.text-gray-600]="lot.statut === 'EN_ATTENTE'"
                  [class.bg-status-warning]="lot.statut === 'EN_COURS'"
                  [class.text-white]="lot.statut === 'EN_COURS' || lot.statut === 'TERMINE'"
                  [class.bg-status-success]="lot.statut === 'TERMINE'"
                >
                  {{ statutLabel(lot.statut) }}
                </span>
              </div>
              <p class="text-sm text-gray-500 mb-3">
                {{ lot.members.length }} étudiant(s)@if (lot.arrivee) { · arrivée prévue
                <span class="font-medium text-gray-700">{{ lot.arrivee }}</span>}.
              </p>

              <!-- EN_COURS: presence + generation -->
              @if (isEnCours()) {
                <div class="rounded-lg border border-gray-100 divide-y divide-gray-50 mb-3">
                  @for (m of lot.members; track m.participationId) {
                    <label class="flex items-center justify-between gap-3 px-3 py-2 text-sm cursor-pointer hover:bg-surface">
                      <span class="text-gray-800">
                        {{ m.prenom }} {{ m.nom }}
                        <span class="text-xs text-gray-400">{{ m.numeroInscription || '' }}</span>
                      </span>
                      <span class="flex items-center gap-2">
                        <input
                          type="checkbox"
                          [checked]="!isAbsent(lot.id, m.participationId)"
                          (change)="togglePresence(lot.id, m.participationId)"
                          class="h-4 w-4 rounded border-gray-300 text-brand focus:ring-brand"
                        />
                        <span class="text-xs text-gray-500 w-14">
                          {{ isAbsent(lot.id, m.participationId) ? 'Absent' : 'Présent' }}
                        </span>
                      </span>
                    </label>
                  }
                </div>

                <div class="flex flex-wrap items-center gap-3">
                  <button
                    type="button"
                    [disabled]="busy()"
                    (click)="enregistrerPresence(lot)"
                    class="inline-flex items-center px-3 py-1.5 rounded-lg border border-brand text-brand text-sm font-medium hover:bg-brand-50 transition-colors disabled:opacity-50"
                  >
                    {{ savingPresenceLot() === lot.id ? 'Enregistrement…' : 'Enregistrer la présence' }}
                  </button>

                  <button
                    type="button"
                    [disabled]="busy() || lot.statut === 'EN_ATTENTE'"
                    (click)="genererLot(lot)"
                    class="inline-flex items-center px-3 py-1.5 rounded-lg text-sm font-medium text-white bg-status-success hover:opacity-90 transition-opacity disabled:opacity-40 disabled:cursor-not-allowed"
                    [title]="lot.statut === 'EN_ATTENTE' ? 'Enregistrez d’abord la présence' : ''"
                  >
                    {{ generatingLot() === lot.id
                      ? 'Génération…'
                      : genByLot()[lot.id]
                        ? 'Régénérer les rotations'
                        : 'Générer les rotations' }}
                  </button>

                  @if (lot.statut === 'EN_ATTENTE') {
                    <span class="text-xs text-gray-400">Présence requise avant génération.</span>
                  }
                </div>

                @if (genByLot()[lot.id]; as g) {
                  <div class="rounded-lg bg-brand-50 border border-brand-100 px-3 py-2 text-sm text-gray-700 mt-3">
                    {{ g.groupes }} groupe(s) · {{ g.rotations }} rotation(s) · {{ g.assignments }} passage(s)
                    · {{ g.etudiantsPresents }} présent(s)@if (g.etudiantsAbsents > 0) {, {{ g.etudiantsAbsents }} absent(s)}.
                    @if (g.avertissement) {
                      <span class="block text-amber-700 mt-1">⚠ {{ g.avertissement }}</span>
                    }
                  </div>
                }
                @if (lotError()[lot.id]; as msg) {
                  <p role="alert" class="text-sm text-status-danger mt-2">{{ msg }}</p>
                }
              } @else if (isConfigure()) {
                <!-- CONFIGURE: roster + per-student manual move to another lot (#165) -->
                <ul class="rounded-lg border border-gray-100 divide-y divide-gray-50">
                  @for (m of lot.members; track m.participationId) {
                    <li class="flex items-center justify-between gap-3 px-3 py-2 text-sm">
                      <span class="text-gray-800">
                        {{ m.prenom }} {{ m.nom }}
                        <span class="text-xs text-gray-400">{{ m.numeroInscription || '' }}</span>
                      </span>
                      @if (otherLots(lot.id).length > 0) {
                        <select
                          [disabled]="busy()"
                          (change)="deplacer(m.participationId, lot.id, $event)"
                          class="text-xs rounded-md border border-gray-300 px-2 py-1 text-gray-600 focus:ring-brand focus:border-brand disabled:opacity-50"
                          aria-label="Déplacer l'étudiant vers un autre lot"
                        >
                          <option value="">Déplacer vers…</option>
                          @for (o of otherLots(lot.id); track o.id) {
                            <option [value]="o.id">Lot {{ o.numeroLot }}</option>
                          }
                        </select>
                      }
                    </li>
                  }
                  @if (lot.members.length === 0) {
                    <li class="px-3 py-2 text-xs text-gray-400">Aucun étudiant dans ce lot.</li>
                  }
                </ul>
                @if (lotError()[lot.id]; as msg) {
                  <p role="alert" class="text-sm text-status-danger mt-2">{{ msg }}</p>
                }
              } @else {
                <!-- read-only roster of the wave (TERMINE) -->
                <ul class="text-sm text-gray-600 space-y-0.5">
                  @for (m of lot.members; track m.participationId) {
                    <li>{{ m.prenom }} {{ m.nom }}
                      <span class="text-xs text-gray-400">{{ m.numeroInscription || '' }}</span>
                    </li>
                  }
                </ul>
              }
            </section>
          }
        </div>
      } @else if (!isConfigure()) {
        <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card">
          <p class="text-gray-700 mb-1">Aucun lot pour cet examen.</p>
          <p class="text-sm text-gray-500">Les lots se répartissent en phase de configuration.</p>
        </div>
      }
    }
  `,
})
export class LotsComponent {
  private readonly examApi = inject(ExamApiService);
  private readonly scoring = inject(ScoringApiService);
  private readonly store = inject(ExamenWorkspaceStore);

  /** Inherited from the parent examens/:id route via withComponentInputBinding(). */
  readonly id = input.required<string>();

  readonly loading = signal(true);
  readonly error = signal(false);

  readonly lots = signal<LotSummary[]>([]);
  private readonly participations = signal<ParticipationSummary[]>([]);
  private readonly directory = signal<EtudiantSummary[]>([]);
  readonly stationCount = signal(0);

  // Per-lot live UI state.
  private readonly absentByLot = signal<Record<number, Set<number>>>({});
  readonly genByLot = signal<Record<number, GenerationResult>>({});
  readonly lotError = signal<Record<number, string>>({});
  readonly savingPresenceLot = signal<number | null>(null);
  readonly generatingLot = signal<number | null>(null);
  readonly movingParticipation = signal<number | null>(null);
  readonly actionError = signal<string | null>(null);
  private readonly repartitionning = signal(false);

  readonly busy = computed(
    () =>
      this.repartitionning() ||
      this.savingPresenceLot() != null ||
      this.generatingLot() != null ||
      this.movingParticipation() != null,
  );

  readonly statut = computed(() => this.store.exam()?.statut ?? null);
  readonly isConfigure = computed(() => this.statut() === 'CONFIGURE');
  readonly isEnCours = computed(() => this.statut() === 'EN_COURS');

  readonly capacite = computed(() => this.store.exam()?.nbEtudiantsParStation ?? 4);
  readonly lotSize = computed(() => Math.max(1, this.stationCount()) * this.capacite());
  readonly rosterCount = computed(() => this.participations().length);

  readonly lotViews = computed<LotView[]>(() => {
    const names = new Map<number, EtudiantSummary>();
    for (const e of this.directory()) names.set(e.id, e);
    const partsByLot = new Map<number, ParticipationSummary[]>();
    for (const p of this.participations()) {
      if (p.lotId == null) continue;
      (partsByLot.get(p.lotId) ?? partsByLot.set(p.lotId, []).get(p.lotId)!).push(p);
    }
    return [...this.lots()]
      .sort((a, b) => (a.numeroLot ?? 0) - (b.numeroLot ?? 0))
      .map((lot) => {
        const members = (partsByLot.get(lot.id) ?? [])
          .map((p) => {
            const e = p.etudiantId != null ? names.get(p.etudiantId) : undefined;
            return {
              participationId: p.id,
              nom: e?.nom ?? '',
              prenom: e?.prenom ?? '',
              numeroInscription: e?.numero_inscription ?? null,
              present: p.est_present,
            };
          })
          .sort((a, b) => (a.nom || '').localeCompare(b.nom || ''));
        return {
          id: lot.id,
          numeroLot: lot.numeroLot ?? 0,
          statut: lot.statut,
          members,
          arrivee: this.arrivee(lot.numeroLot ?? 1),
        };
      });
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
    this.actionError.set(null);
    forkJoin({
      lots: this.scoring.listLots(examId),
      participations: this.scoring.listParticipations(examId),
      etudiants: this.scoring.listEtudiants(),
      stations: this.examApi.listStations(examId),
    }).subscribe({
      next: ({ lots, participations, etudiants, stations }) => {
        this.lots.set(lots);
        this.participations.set(participations);
        this.directory.set(etudiants);
        this.stationCount.set(stations.length);
        this.seedAbsentState(lots, participations);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  /** Reflect any presence already recorded (est_present===false) in the toggles. */
  private seedAbsentState(lots: LotSummary[], parts: ParticipationSummary[]): void {
    const map: Record<number, Set<number>> = {};
    for (const lot of lots) map[lot.id] = new Set();
    for (const p of parts) {
      if (p.lotId != null && p.est_present === false && map[p.lotId]) {
        map[p.lotId].add(p.id);
      }
    }
    this.absentByLot.set(map);
  }

  // ---- Phase 1: répartition ----------------------------------------------

  repartir(): void {
    const examId = Number(this.id());
    if (this.busy() || this.rosterCount() === 0) return;
    this.repartitionning.set(true);
    this.actionError.set(null);
    this.scoring.repartirLots(examId).subscribe({
      next: () => {
        this.repartitionning.set(false);
        this.genByLot.set({});
        this.load(examId);
      },
      error: (err) => {
        this.repartitionning.set(false);
        this.actionError.set(this.message(err, 'Répartition impossible.'));
      },
    });
  }

  // ---- CONFIGURE: manual single-student lot move (#165) ------------------

  /** The lots a student could be moved to: every lot of this exam except their own. */
  otherLots(currentLotId: number): LotSummary[] {
    return [...this.lots()]
      .filter((l) => l.id !== currentLotId)
      .sort((a, b) => (a.numeroLot ?? 0) - (b.numeroLot ?? 0));
  }

  /**
   * Move one student from {@code sourceLotId} into the lot picked in the select.
   * Re-points the participation server-side (CONFIGURE-gated) then reloads so both
   * rosters + tailleLot reflect the change. Errors surface under the source lot.
   */
  deplacer(participationId: number, sourceLotId: number, event: Event): void {
    const select = event.target as HTMLSelectElement;
    const targetLotId = Number(select.value);
    if (this.busy() || !Number.isFinite(targetLotId) || targetLotId <= 0) {
      select.value = '';
      return;
    }
    this.movingParticipation.set(participationId);
    this.clearLotError(sourceLotId);
    this.scoring.deplacerEtudiant(targetLotId, participationId).subscribe({
      next: () => {
        this.movingParticipation.set(null);
        this.genByLot.set({});
        this.load(Number(this.id()));
      },
      error: (err) => {
        this.movingParticipation.set(null);
        select.value = '';
        this.setLotError(sourceLotId, this.message(err, 'Déplacement impossible.'));
      },
    });
  }

  // ---- Phase 2: presence + generation ------------------------------------

  isAbsent(lotId: number, participationId: number): boolean {
    return this.absentByLot()[lotId]?.has(participationId) ?? false;
  }

  togglePresence(lotId: number, participationId: number): void {
    this.absentByLot.update((map) => {
      const next = { ...map };
      const set = new Set(next[lotId] ?? []);
      if (set.has(participationId)) set.delete(participationId);
      else set.add(participationId);
      next[lotId] = set;
      return next;
    });
  }

  enregistrerPresence(lot: LotView): void {
    if (this.busy()) return;
    const absents = [...(this.absentByLot()[lot.id] ?? [])];
    this.savingPresenceLot.set(lot.id);
    this.clearLotError(lot.id);
    this.scoring.marquerPresence(lot.id, absents).subscribe({
      next: () => {
        this.savingPresenceLot.set(null);
        // Flip the lot to EN_COURS locally so the generate button unlocks.
        this.lots.update((list) =>
          list.map((l) => (l.id === lot.id ? { ...l, statut: 'EN_COURS' } : l)),
        );
        // Reflect est_present on the participations so a reload stays consistent.
        this.participations.update((list) =>
          list.map((p) =>
            p.lotId === lot.id ? { ...p, est_present: !absents.includes(p.id) } : p,
          ),
        );
      },
      error: (err) => {
        this.savingPresenceLot.set(null);
        this.setLotError(lot.id, this.message(err, 'Enregistrement de la présence impossible.'));
      },
    });
  }

  genererLot(lot: LotView): void {
    if (this.busy() || lot.statut === 'EN_ATTENTE') return;
    this.generatingLot.set(lot.id);
    this.clearLotError(lot.id);
    this.scoring.genererRotationsLot(lot.id).subscribe({
      next: (result) => {
        this.generatingLot.set(null);
        this.genByLot.update((m) => ({ ...m, [lot.id]: result }));
      },
      error: (err) => {
        this.generatingLot.set(null);
        this.setLotError(lot.id, this.message(err, 'Génération impossible.'));
      },
    });
  }

  // ---- helpers ------------------------------------------------------------

  /** Back-to-back wave start: heureDebut + (numeroLot-1)·K·dureeStationMin → HH:mm. */
  private arrivee(numeroLot: number): string | null {
    const e = this.store.exam();
    const heure = e?.heureDebut;
    const duree = e?.dureeStationMin;
    const k = this.stationCount();
    if (!heure || !duree || k === 0) return null;
    const [h, m] = heure.split(':').map(Number);
    if (Number.isNaN(h) || Number.isNaN(m)) return null;
    const total = h * 60 + m + (numeroLot - 1) * k * duree;
    const hh = Math.floor((total % (24 * 60)) / 60);
    const mm = total % 60;
    return `${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}`;
  }

  statutLabel(s: LotSummary['statut']): string {
    switch (s) {
      case 'EN_COURS':
        return 'Présence faite';
      case 'TERMINE':
        return 'Terminé';
      default:
        return 'En attente';
    }
  }

  private setLotError(lotId: number, msg: string): void {
    this.lotError.update((m) => ({ ...m, [lotId]: msg }));
  }

  private clearLotError(lotId: number): void {
    this.lotError.update((m) => {
      const next = { ...m };
      delete next[lotId];
      return next;
    });
  }

  private message(err: { status?: number; error?: { message?: string } }, fallback: string): string {
    if (err?.status === 403) return "Vous n'avez pas les droits sur cet examen.";
    if ((err?.status === 400 || err?.status === 409) && typeof err.error?.message === 'string') {
      return err.error.message;
    }
    return fallback;
  }
}
