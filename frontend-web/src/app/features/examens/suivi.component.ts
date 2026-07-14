import {
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ScoringApiService } from '../../core/api/scoring-api.service';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import {
  EtudiantSummary,
  ParticipationSummary,
  RotationSummary,
  StationSummary,
  UserResponse,
} from '../../core/api/models';
import { ExamenWorkspaceStore } from './examen-workspace.store';

/** One créneau slot at a station — a group's scheduled visit. */
export interface Slot {
  rotationId: number;
  ordrePassage: number;
  debutMs: number; // absolute planned start (ms epoch, local)
  debutLabel: string; // HH:mm
}

/** A station's whole timeline + its bound évaluateur. */
interface Lane {
  stationId: number;
  nom: string;
  ordre: number;
  evaluateurNom: string | null;
  slots: Slot[]; // sorted by debutMs
}

type SlotState = 'done' | 'live' | 'upcoming';

/**
 * A station's state on the board. {@code sansRotations} is NOT a time-derived
 * state — it means the station has no rotation plan at all, and it is resolved
 * BEFORE any clock branch so an unknown station can never read as a finished one.
 */
export type LaneState = 'sansRotations' | 'live' | 'upcoming' | 'done';

const DEFAULT_DUREE_MIN = 15;
const DEFAULT_HEURE = '09:00';

/**
 * Resolve a station's board state — the #182 invariant, kept pure so it can be
 * pinned by a unit test without a TestBed.
 *
 * The {@code sansRotations} branch comes FIRST and must stay first: a station with
 * no rotation plan is UNKNOWN, not finished. The board previously fell through
 * live → upcoming → "Terminée", so a station whose rotations were never generated
 * rendered as already over — a phantom terminal state that cost a live exam
 * simulation. 'done' must only ever be reachable from a real, non-empty slot set.
 *
 * @param slots the station's slots, sorted by debutMs (may be empty)
 * @param effectiveNowMs ADR-0009 effective instant, or null before the clock resolves
 * @param dureeMs a slot's duration in ms
 */
export function resolveLaneState(
  slots: readonly Slot[],
  effectiveNowMs: number | null,
  dureeMs: number,
): LaneState {
  if (slots.length === 0) return 'sansRotations';
  if (effectiveNowMs == null) return 'upcoming';
  const live = slots.some(
    (s) => effectiveNowMs >= s.debutMs && effectiveNowMs < s.debutMs + dureeMs,
  );
  if (live) return 'live';
  const first = slots[0];
  if (effectiveNowMs < first.debutMs) return 'upcoming';
  return 'done';
}

/**
 * Suivi en direct — the live exam-day monitoring board (EN_COURS only).
 *
 * <p><b>Why the clock, not the status:</b> rotations are persisted EN_ATTENTE and
 * nothing on the backend ever flips them (the mobile évaluateur app that would is
 * unbuilt — see RotationGenerationService + RotationStatus). So this screen DERIVES
 * each slot's live state from time: the generator lays every rotation's
 * {@code debutCreneau} on a back-to-back schedule anchored at the exam's planned
 * start, and a slot is live while {@code [debutCreneau, debutCreneau + dureeStationMin)}
 * brackets "now".
 *
 * <p><b>Clock origin (ADR-0010):</b> the schedule and the live clock share one origin —
 * the REAL launch instant {@code launchedAt}, stamped once when Lancement flips the exam
 * to EN_COURS, falling back to the PLANNED {@code dateExamen + heureDebut} only for
 * legacy/not-yet-launched rows. So an exam started off its planned hour no longer reads
 * "X minutes already elapsed" the moment the board opens — the timeline shifts with it.
 *
 * <p><b>Effective time (ADR-0009 pause/resume):</b> a paused exam stays EN_COURS but
 * its clock must stop, or every countdown would drift through the break. While running,
 * "now" is the EFFECTIVE instant {@code examStart + (now − examStart) − totalPauseSec},
 * anchored to the browser clock. When the responsable pauses, the board FREEZES at the
 * effective instant the pause began ({@code pausedAt − totalPauseSec}); Reprendre continues
 * exactly from there (the server folds the gap into {@code totalPauseSec}, so the running
 * formula picks up seamlessly).
 *
 * <p><b>"Terminée" is never a fallthrough (#182):</b> a station with NO rotations is
 * {@code sansRotations} — unknown, not finished — and {@link #laneState} resolves that
 * BEFORE any clock branch. The old template fell through live → upcoming → "Terminée",
 * so a station whose rotations were never generated rendered as already over. That
 * phantom terminal state cost a real exam simulation: the responsable read "Terminée"
 * across the board and concluded it was too late, when in fact
 * {@code RotationGenerationService} still permits generation while the exam is EN_COURS
 * — nothing was lost. Do not reintroduce a default that reads as "done".
 *
 * <p><b>Timezone (ADR-0010, option A):</b> the backend Clock is pinned to the exam zone
 * (prod {@code Africa/Tunis}; dev aligned to the host via {@code APP_TIMEZONE}), so the
 * server-stamped {@code launchedAt}/{@code pausedAt} share the schedule's wall-clock
 * domain. The board trusts those server timestamps on any (re)load — including the
 * reload-while-paused freeze. The client-captured {@code pauseFrozenMs} stays only as a
 * belt-and-braces optimisation for the live session (exact mid-tick, skips a parse).
 */
@Component({
  selector: 'app-suivi',
  standalone: true,
  imports: [RouterLink],
  template: `
    @if (loading()) {
      <div class="space-y-4 animate-pulse">
        <div class="h-20 rounded-xl bg-gray-200"></div>
        <div class="h-40 rounded-xl bg-gray-200"></div>
      </div>
    } @else if (error()) {
      <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card">
        <p class="text-gray-700 mb-1">Impossible de charger le suivi.</p>
        <p class="text-sm text-gray-500 mb-4">Verifiez votre connexion puis reessayez.</p>
        <button
          type="button"
          (click)="reload()"
          class="inline-flex items-center px-4 py-2 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark transition-colors"
        >
          Reessayer
        </button>
      </div>
    } @else if (lanes().length === 0) {
      <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card">
        <p class="text-gray-700 mb-1">Aucune rotation à suivre.</p>
        <p class="text-sm text-gray-500">
          Générez les rotations dans l'onglet
          <a [routerLink]="['../lots']" class="text-brand hover:underline">Lots &amp; présence</a>
          (le jour J, après la présence de chaque lot).
        </p>
      </div>
    } @else {
      <!-- Exam-level live bar -->
      <section
        class="rounded-xl border shadow-card p-5 mb-6"
        [class.bg-white]="normalRunning()"
        [class.border-gray-200]="normalRunning()"
        [class.bg-amber-50]="isEnPause()"
        [class.border-amber-200]="isEnPause()"
        [class.bg-red-50]="overtimeRunning()"
        [class.border-red-200]="overtimeRunning()"
      >
        <div class="flex flex-wrap items-center justify-between gap-4">
          <div>
            <div class="flex items-center gap-2">
              @if (isEnPause()) {
                <span class="h-2.5 w-2.5 rounded-full bg-status-warning"></span>
                <h2 class="font-semibold text-amber-800">Examen en pause</h2>
              } @else if (overtimeRunning()) {
                <span class="h-2.5 w-2.5 rounded-full bg-status-danger animate-pulse"></span>
                <h2 class="font-semibold text-red-800">Temps dépassé</h2>
              } @else {
                <span class="h-2.5 w-2.5 rounded-full bg-status-success animate-pulse"></span>
                <h2 class="font-semibold text-gray-900">Examen en cours</h2>
              }
            </div>
            <p
              class="text-sm mt-1"
              [class.text-amber-700]="isEnPause()"
              [class.text-red-700]="overtimeRunning()"
              [class.text-gray-500]="normalRunning()"
            >
              @if (isEnPause()) {
                Le chronomètre est figé — les minuteurs reprendront à l'identique.
              } @else {
                Temps effectif écoulé : <span class="font-medium tabular-nums">{{ elapsedLabel() }}</span>
                @if (overtimeRunning()) {
                  · dépassement <span class="font-medium tabular-nums">+{{ overtimeLabel() }}</span>
                }
                @if (totalPauseSec() > 0) {
                  · <span class="text-gray-400">pauses cumulées {{ pauseLabel() }}</span>
                }
              }
            </p>
          </div>

          <div class="flex flex-wrap items-center gap-3">
            @if (actionError()) {
              <span role="alert" class="text-sm text-status-danger">{{ actionError() }}</span>
            }
            @if (isEnPause()) {
              <button
                type="button"
                [disabled]="pausing()"
                (click)="reprendre()"
                class="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg bg-status-success text-white text-sm font-medium hover:opacity-90 transition-opacity disabled:opacity-50"
              >
                {{ pausing() ? '…' : '▶ Reprendre' }}
              </button>
            } @else {
              @if (overtimeRunning()) {
                @if (confirmingEnd()) {
                  <span class="text-sm font-medium text-gray-800">Terminer définitivement&nbsp;?</span>
                  <button
                    type="button"
                    [disabled]="terminating()"
                    (click)="terminer()"
                    class="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg bg-status-danger text-white text-sm font-medium hover:opacity-90 transition-opacity disabled:opacity-50"
                  >
                    {{ terminating() ? '…' : 'Oui, terminer' }}
                  </button>
                  <button
                    type="button"
                    [disabled]="terminating()"
                    (click)="cancelTerminer()"
                    class="inline-flex items-center px-3 py-2 rounded-lg border border-gray-300 text-gray-600 text-sm font-medium hover:bg-gray-50 transition-colors disabled:opacity-50"
                  >
                    Annuler
                  </button>
                } @else {
                  <button
                    type="button"
                    (click)="askTerminer()"
                    class="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg bg-status-danger text-white text-sm font-medium hover:opacity-90 transition-opacity"
                  >
                    ■ Terminer l'examen
                  </button>
                }
              }
              <button
                type="button"
                [disabled]="pausing() || terminating()"
                (click)="pause()"
                class="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg border border-amber-400 text-amber-700 text-sm font-medium hover:bg-amber-50 transition-colors disabled:opacity-50"
              >
                {{ pausing() ? '…' : '⏸ Mettre en pause' }}
              </button>
            }
          </div>
        </div>

        <!-- Global progress through the planned circuit -->
        <div class="mt-4">
          <div class="h-1.5 rounded-full bg-gray-200 overflow-hidden">
            <div
              class="h-full rounded-full transition-all duration-1000 ease-linear"
              [class.bg-status-warning]="isEnPause()"
              [class.bg-status-danger]="overtimeRunning()"
              [class.bg-brand]="normalRunning()"
              [style.width.%]="globalProgressPct()"
            ></div>
          </div>
          <div class="flex justify-between text-xs text-gray-400 mt-1 tabular-nums">
            <span>{{ startLabel() }}</span>
            <span>{{ endLabel() }}</span>
          </div>
        </div>
      </section>

      <!--
        Missing-rotations alarm. The exam is live but one or more stations have no
        rotation plan — nothing will ever run there. This is recoverable (rotations
        can still be generated while EN_COURS), so say so and link to the fix.
      -->
      @if (hasMissingRotations()) {
        <section
          role="alert"
          class="rounded-xl bg-amber-50 border border-amber-300 shadow-card p-5 mb-6"
        >
          <div class="flex flex-wrap items-start justify-between gap-4">
            <div>
              <h2 class="font-semibold text-amber-900">
                ⚠ {{ lanesSansRotations().length }} station(s) sans rotations
              </h2>
              <p class="text-sm text-amber-800 mt-1">
                Aucun passage n'est planifié pour
                <span class="font-medium">{{ lanesSansRotationsLabel() }}</span> — rien ne s'y
                déroulera. Générez les rotations depuis
                <span class="font-medium">Lots &amp; présence</span> (après la présence de chaque
                lot). L'examen est en cours : c'est encore rattrapable.
              </p>
            </div>
            <a
              [routerLink]="['../lots']"
              class="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg bg-amber-600 text-white text-sm font-medium hover:opacity-90 transition-opacity shrink-0"
            >
              Générer les rotations
            </a>
          </div>
        </section>
      }

      <!-- Per-station lanes -->
      <div class="space-y-4">
        @for (lane of lanes(); track lane.stationId) {
          <section class="rounded-xl bg-white border border-gray-200 shadow-card p-5">
            <div class="flex flex-wrap items-start justify-between gap-3 mb-3">
              <div>
                <h3 class="font-semibold text-gray-900">
                  <span class="text-gray-400 text-sm font-normal">#{{ lane.ordre }}</span>
                  {{ lane.nom }}
                </h3>
                <p class="text-sm text-gray-500">
                  Évaluateur :
                  <span class="text-gray-700">{{ lane.evaluateurNom || 'non assigné' }}</span>
                </p>
              </div>

              <!--
                Station badge. 'sansRotations' is resolved FIRST: a station with no
                rotation plan is unknown, not finished — it must never fall through
                to "Terminée" (that phantom terminal state cost us a live exam).
              -->
              @switch (laneState(lane)) {
                @case ('sansRotations') {
                  <div class="text-right">
                    <div class="text-xs text-amber-600 uppercase tracking-wide">Station</div>
                    <div class="font-semibold text-amber-700">⚠ Aucune rotation générée</div>
                    <a
                      [routerLink]="['../lots']"
                      class="text-xs text-brand hover:underline"
                    >
                      Générer les rotations
                    </a>
                  </div>
                }
                @case ('live') {
                  @if (liveSlot(lane); as s) {
                    <div class="text-right">
                      <div class="text-xs text-gray-400 uppercase tracking-wide">En ce moment</div>
                      <div class="font-semibold text-status-success tabular-nums">
                        Passage {{ s.ordrePassage }} · fin dans {{ countdown(slotEndMs(s)) }}
                      </div>
                    </div>
                  }
                }
                @case ('upcoming') {
                  @if (beforeStart(lane); as next) {
                    <div class="text-right">
                      <div class="text-xs text-gray-400 uppercase tracking-wide">À venir</div>
                      <div class="font-medium text-gray-600 tabular-nums">
                        Début dans {{ countdown(next.debutMs) }}
                      </div>
                    </div>
                  }
                }
                @default {
                  <div class="text-right">
                    <div class="text-xs text-gray-400 uppercase tracking-wide">Station</div>
                    <div class="font-medium text-gray-500">Terminée</div>
                  </div>
                }
              }
            </div>

            <!-- Live slot's students (auto-loaded) -->
            @if (liveSlot(lane); as s) {
              <div class="rounded-lg bg-green-50 border border-green-100 px-3 py-2 mb-3">
                <!-- thin slot progress -->
                <div class="h-1 rounded-full bg-green-100 overflow-hidden mb-2">
                  <div
                    class="h-full bg-status-success transition-all duration-1000 ease-linear"
                    [style.width.%]="slotProgressPct(s)"
                  ></div>
                </div>
                @if (students()[s.rotationId]; as names) {
                  <p class="text-sm text-gray-700">
                    <span class="text-gray-400">Étudiant(s) :</span>
                    {{ names.length ? names.join(', ') : 'aucun' }}
                  </p>
                } @else {
                  <p class="text-sm text-gray-400">Chargement des étudiants…</p>
                }
              </div>
            }

            @if (laneState(lane) === 'sansRotations') {
              <div class="rounded-lg bg-amber-50 border border-amber-200 px-3 py-2 text-sm text-amber-800">
                Cette station n'a aucun passage planifié. Les rotations n'ont pas été générées pour
                les lots de cet examen.
              </div>
            }

            <!-- Timeline strip -->
            <div class="flex gap-1">
              @for (s of lane.slots; track s.rotationId) {
                <div
                  class="h-2 flex-1 rounded-full"
                  [class.bg-gray-200]="slotState(s) === 'upcoming'"
                  [class.bg-status-success]="slotState(s) === 'live'"
                  [class.bg-brand]="slotState(s) === 'done'"
                  [title]="s.debutLabel + ' · passage ' + s.ordrePassage"
                ></div>
              }
            </div>

            <!-- Expand: full schedule + students (nothing to expand without a plan) -->
            @if (lane.slots.length > 0) {
              <button
                type="button"
                (click)="toggle(lane.stationId)"
                class="text-xs text-brand hover:underline mt-3"
              >
                {{ isOpen(lane.stationId) ? 'Masquer le détail' : 'Voir tous les passages' }}
              </button>
            }

            @if (isOpen(lane.stationId)) {
              <ul class="mt-3 divide-y divide-gray-50 border border-gray-100 rounded-lg">
                @for (s of lane.slots; track s.rotationId) {
                  <li
                    class="px-3 py-2 text-sm flex items-start justify-between gap-3"
                    [class.bg-green-50]="slotState(s) === 'live'"
                  >
                    <span class="text-gray-700 tabular-nums w-32 shrink-0">
                      {{ s.debutLabel }}
                      <span
                        class="ml-1 text-xs px-1.5 py-0.5 rounded-full align-middle"
                        [class.bg-gray-100]="slotState(s) === 'upcoming'"
                        [class.text-gray-500]="slotState(s) === 'upcoming'"
                        [class.bg-status-success]="slotState(s) === 'live'"
                        [class.text-white]="slotState(s) === 'live'"
                        [class.bg-brand-50]="slotState(s) === 'done'"
                        [class.text-brand-dark]="slotState(s) === 'done'"
                      >
                        {{ slotStateLabel(s) }}
                      </span>
                    </span>
                    <span class="text-gray-600 text-right grow">
                      @if (students()[s.rotationId]; as names) {
                        {{ names.length ? names.join(', ') : 'aucun étudiant' }}
                      } @else {
                        <span class="text-gray-400">…</span>
                      }
                    </span>
                  </li>
                }
              </ul>
            }
          </section>
        }
      </div>
    }
  `,
})
export class SuiviComponent {
  private readonly examApi = inject(ExamApiService);
  private readonly scoring = inject(ScoringApiService);
  private readonly directory = inject(DirectoryApiService);
  private readonly store = inject(ExamenWorkspaceStore);
  private readonly destroyRef = inject(DestroyRef);
  private readonly router = inject(Router);

  /** Inherited from the parent examens/:id route via withComponentInputBinding(). */
  readonly id = input.required<string>();

  readonly loading = signal(true);
  readonly error = signal(false);
  readonly lanes = signal<Lane[]>([]);
  readonly pausing = signal(false);
  readonly actionError = signal<string | null>(null);
  /** Ending the exam (EN_COURS → TERMINE) is in flight. */
  readonly terminating = signal(false);
  /** The two-step "Terminer" confirmation is showing. */
  readonly confirmingEnd = signal(false);

  /** Ticks every second — drives every clock-derived state in the template. */
  private readonly now = signal(Date.now());

  /**
   * Effective instant the board was frozen at when paused, captured on the client
   * at pause time (timezone-proof). Null while running. See class doc.
   */
  private readonly pauseFrozenMs = signal<number | null>(null);

  // Student-name resolution.
  private readonly partById = signal<Map<number, ParticipationSummary>>(new Map());
  private readonly etuById = signal<Map<number, EtudiantSummary>>(new Map());
  /** rotationId → resolved student display names (lazy cache). */
  readonly students = signal<Record<number, string[]>>({});
  private readonly loadingRotations = new Set<number>();

  private readonly openStations = signal<Set<number>>(new Set());

  readonly exam = this.store.exam;
  readonly isEnPause = computed(() => this.exam()?.enPause === true);
  readonly totalPauseSec = computed(() => this.exam()?.totalPauseSec ?? 0);

  private readonly dureeMs = computed(
    () => (this.exam()?.dureeStationMin ?? DEFAULT_DUREE_MIN) * 60_000,
  );

  /**
   * The clock origin (ms epoch). Anchors on the REAL launch instant
   * ({@code launchedAt}, ADR-0010) when present, so plan + live clock share the
   * moment Lancement flipped the exam to EN_COURS — not its planned hour. Falls
   * back to the PLANNED start ({@code dateExamen + heureDebut}) for pre-ADR rows
   * or exams without a launch stamp. Naive timestamps parse as browser-local; the
   * dev backend Clock is zone-aligned to the host (ADR-0010), so they agree.
   */
  private readonly examStartMs = computed(() => {
    const e = this.exam();
    if (!e) return null;
    if (e.launchedAt) {
      const ms = new Date(e.launchedAt.replace(' ', 'T')).getTime();
      if (!Number.isNaN(ms)) return ms;
    }
    if (!e.dateExamen) return null;
    const heure = e.heureDebut || DEFAULT_HEURE;
    const ms = new Date(`${e.dateExamen}T${heure}:00`).getTime();
    return Number.isNaN(ms) ? null : ms;
  });

  /**
   * The EFFECTIVE current instant (ms epoch) — wall clock minus all pause time.
   * Running: browser clock minus accumulated pauses. Paused: frozen at the
   * effective instant the pause began — {@code pausedAt − totalPauseSec}, which is
   * exactly ADR-0009's effective-time formula evaluated at the pause moment.
   *
   * <p>The server-stamped {@code pausedAt} is the TRUSTED freeze source on any
   * (re)load of an already-paused exam, now that ADR-0010 pins the backend Clock
   * to the exam zone so it shares one clock domain with the schedule. The
   * client-captured {@code pauseFrozenMs} stays as a belt-and-braces optimisation
   * for the live session (it skips a parse and is exact even mid-tick).
   */
  private readonly effectiveNowMs = computed<number | null>(() => {
    const e = this.exam();
    const start = this.examStartMs();
    if (!e || start == null) return null;
    const totalPauseMs = (e.totalPauseSec ?? 0) * 1000;
    if (e.enPause) {
      const frozen = this.pauseFrozenMs();
      if (frozen != null) return frozen;
      // Reload-while-paused: reconstruct the freeze from the server clock
      // (zone-coherent since ADR-0010) — pausedAt minus prior accumulated pauses.
      if (e.pausedAt) {
        const pAt = new Date(e.pausedAt.replace(' ', 'T')).getTime();
        if (!Number.isNaN(pAt)) return pAt - totalPauseMs;
      }
      return start;
    }
    return start + (this.now() - start - totalPauseMs);
  });

  private readonly lastSlotEndMs = computed(() => {
    let max = 0;
    for (const lane of this.lanes()) {
      const last = lane.slots[lane.slots.length - 1];
      if (last) max = Math.max(max, last.debutMs + this.dureeMs());
    }
    return max;
  });

  readonly globalProgressPct = computed(() => {
    const start = this.examStartMs();
    const end = this.lastSlotEndMs();
    const eff = this.effectiveNowMs();
    if (start == null || eff == null || end <= start) return 0;
    return Math.min(100, Math.max(0, ((eff - start) / (end - start)) * 100));
  });

  readonly elapsedLabel = computed(() => {
    const start = this.examStartMs();
    const eff = this.effectiveNowMs();
    if (start == null || eff == null) return '00:00';
    return this.fmtDuration(Math.max(0, eff - start));
  });

  readonly pauseLabel = computed(() => this.fmtDuration(this.totalPauseSec() * 1000));
  readonly startLabel = computed(() => this.hhmm(this.examStartMs()));
  readonly endLabel = computed(() => this.hhmm(this.lastSlotEndMs() || null));

  /**
   * Overtime = the effective clock has passed the last créneau's planned end
   * (the scheduled end of the whole circuit). The exam keeps running; the board
   * just signals the dépassement in red and unlocks "Terminer". The scheduled end
   * is derived from the rotation plan (last debutCreneau + dureeStationMin), since
   * exam-service holds no exam-level duration — the schedule lives in scoring.
   */
  readonly isOvertime = computed(() => {
    const end = this.lastSlotEndMs();
    const eff = this.effectiveNowMs();
    return end > 0 && eff != null && eff >= end;
  });
  /** Overtime AND running (not frozen by a pause) — drives the red state + Terminer. */
  readonly overtimeRunning = computed(() => this.isOvertime() && !this.isEnPause());
  /** Running on schedule (neither paused nor in overtime) — the normal green state. */
  readonly normalRunning = computed(() => !this.isEnPause() && !this.isOvertime());
  /** How far past the scheduled end (mm:ss / h:mm:ss). */
  readonly overtimeLabel = computed(() => {
    const end = this.lastSlotEndMs();
    const eff = this.effectiveNowMs();
    if (end <= 0 || eff == null || eff < end) return '00:00';
    return this.fmtDuration(eff - end);
  });

  constructor() {
    // 1 Hz ticker.
    const timer = setInterval(() => this.now.set(Date.now()), 1000);
    this.destroyRef.onDestroy(() => clearInterval(timer));

    // (Re)load when the route id resolves.
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

    // Auto-resolve the students of whatever slot is live now, per station. Guarded
    // by the cache + in-flight set, so it fires once per créneau transition — not
    // every tick. This is the "who is at this station right now" answer.
    effect(
      () => {
        for (const lane of this.lanes()) {
          const s = this.liveSlot(lane);
          if (s) this.ensureStudents(s.rotationId);
        }
      },
      { allowSignalWrites: true },
    );
  }

  reload(): void {
    this.load(Number(this.id()));
  }

  private load(examId: number): void {
    this.loading.set(true);
    this.error.set(false);
    forkJoin({
      stations: this.examApi.listStations(examId),
      evaluateurs: this.directory.listUsers('EVALUATEUR'),
      participations: this.scoring.listParticipations(examId),
      etudiants: this.scoring.listEtudiants(),
    }).subscribe({
      next: ({ stations, evaluateurs, participations, etudiants }) => {
        this.indexNames(participations, etudiants);
        const sorted = [...stations].sort(
          (a, b) => (a.ordre ?? 0) - (b.ordre ?? 0),
        );
        if (sorted.length === 0) {
          this.lanes.set([]);
          this.loading.set(false);
          return;
        }
        // Fan out: each station's whole rotation timeline.
        forkJoin(sorted.map((s) => this.scoring.listRotationsByStation(s.id))).subscribe({
          next: (rotationsPerStation) => {
            this.lanes.set(this.buildLanes(sorted, rotationsPerStation, evaluateurs));
            this.students.set({});
            this.loadingRotations.clear();
            this.loading.set(false);
          },
          error: () => {
            this.error.set(true);
            this.loading.set(false);
          },
        });
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  private indexNames(parts: ParticipationSummary[], etudiants: EtudiantSummary[]): void {
    const pMap = new Map<number, ParticipationSummary>();
    for (const p of parts) pMap.set(p.id, p);
    const eMap = new Map<number, EtudiantSummary>();
    for (const e of etudiants) eMap.set(e.id, e);
    this.partById.set(pMap);
    this.etuById.set(eMap);
  }

  private buildLanes(
    stations: StationSummary[],
    rotationsPerStation: RotationSummary[][],
    evaluateurs: UserResponse[],
  ): Lane[] {
    const evalName = new Map<number, string>();
    for (const u of evaluateurs) evalName.set(u.id, `${u.prenom} ${u.nom}`.trim());

    return stations.map((s, i) => {
      const rots = rotationsPerStation[i] ?? [];
      const slots: Slot[] = rots
        .filter((r) => !!r.debutCreneau)
        .map((r) => {
          const debutMs = new Date(r.debutCreneau).getTime();
          return {
            rotationId: r.id,
            ordrePassage: r.ordrePassage ?? 0,
            debutMs,
            debutLabel: this.hhmm(debutMs),
          };
        })
        .filter((s) => !Number.isNaN(s.debutMs))
        .sort((a, b) => a.debutMs - b.debutMs);
      // One évaluateur per station (the generator binds the same one to every
      // rotation of the station); fall back to the station's own binding.
      const evId =
        rots.find((r) => r.evaluateurId != null)?.evaluateurId ??
        s.evaluateurIds?.[0] ??
        null;
      return {
        stationId: s.id,
        nom: s.nom ?? `Station ${s.ordre ?? i + 1}`,
        ordre: s.ordre ?? i + 1,
        evaluateurNom: evId != null ? evalName.get(evId) ?? null : null,
        slots,
      };
    });
  }

  // ---- clock-derived per-slot state --------------------------------------

  slotEndMs(s: Slot): number {
    return s.debutMs + this.dureeMs();
  }

  slotState(s: Slot): SlotState {
    const eff = this.effectiveNowMs();
    if (eff == null) return 'upcoming';
    if (eff < s.debutMs) return 'upcoming';
    if (eff < this.slotEndMs(s)) return 'live';
    return 'done';
  }

  slotStateLabel(s: Slot): string {
    switch (this.slotState(s)) {
      case 'live':
        return 'en cours';
      case 'done':
        return 'terminé';
      default:
        return 'à venir';
    }
  }

  /**
   * The station's board state. The {@code sansRotations} check comes FIRST and is
   * the whole point of this method: a station with no rotation plan is UNKNOWN, not
   * finished. Previously the template fell through live → upcoming → "Terminée", so
   * a station with zero slots rendered as "Terminée" — a phantom terminal state that
   * made a fully recoverable exam (rotations simply not generated yet) look like it
   * was already over. Never resolve 'done' from an empty slot set.
   */
  laneState(lane: Lane): LaneState {
    return resolveLaneState(lane.slots, this.effectiveNowMs(), this.dureeMs());
  }

  /** Stations with no rotation plan at all — the exam-day recovery signal. */
  readonly lanesSansRotations = computed(() =>
    this.lanes().filter((l) => l.slots.length === 0),
  );

  /** The exam is live but at least one station has no rotations — must be surfaced loudly. */
  readonly hasMissingRotations = computed(() => this.lanesSansRotations().length > 0);

  /** Names of the stations missing a rotation plan, for the alarm banner. */
  readonly lanesSansRotationsLabel = computed(() =>
    this.lanesSansRotations()
      .map((l) => l.nom)
      .join(', '),
  );

  /** The slot currently live at this station, if any. */
  liveSlot(lane: Lane): Slot | null {
    const eff = this.effectiveNowMs();
    if (eff == null) return null;
    return lane.slots.find((s) => eff >= s.debutMs && eff < this.slotEndMs(s)) ?? null;
  }

  /** If the station hasn't started yet, the first (upcoming) slot — else null. */
  beforeStart(lane: Lane): Slot | null {
    const eff = this.effectiveNowMs();
    const first = lane.slots[0];
    if (eff == null || !first) return null;
    return eff < first.debutMs ? first : null;
  }

  slotProgressPct(s: Slot): number {
    const eff = this.effectiveNowMs();
    if (eff == null) return 0;
    const pct = ((eff - s.debutMs) / this.dureeMs()) * 100;
    return Math.min(100, Math.max(0, pct));
  }

  /** Countdown (mm:ss / h:mm:ss) from effective-now to a target instant. */
  countdown(targetMs: number): string {
    const eff = this.effectiveNowMs();
    if (eff == null) return '--:--';
    return this.fmtDuration(Math.max(0, targetMs - eff));
  }

  // ---- pause / reprendre --------------------------------------------------

  pause(): void {
    if (this.pausing()) return;
    this.pausing.set(true);
    this.actionError.set(null);
    this.examApi.pauseExamen(Number(this.id())).subscribe({
      next: (e) => {
        // Capture the freeze on the client while the exam is still "running" in
        // local state, BEFORE swapping in the paused exam — timezone-proof.
        this.now.set(Date.now());
        this.pauseFrozenMs.set(this.effectiveNowMs());
        this.store.exam.set(e);
        this.pausing.set(false);
      },
      error: (err) => {
        this.pausing.set(false);
        this.actionError.set(this.message(err, 'Mise en pause impossible.'));
      },
    });
  }

  reprendre(): void {
    if (this.pausing()) return;
    this.pausing.set(true);
    this.actionError.set(null);
    this.examApi.reprendreExamen(Number(this.id())).subscribe({
      next: (e) => {
        // Resume: drop the freeze; the server has folded the gap into
        // totalPauseSec, so the running formula continues seamlessly.
        this.pauseFrozenMs.set(null);
        this.store.exam.set(e);
        this.now.set(Date.now());
        this.pausing.set(false);
      },
      error: (err) => {
        this.pausing.set(false);
        this.actionError.set(this.message(err, 'Reprise impossible.'));
      },
    });
  }

  // ---- terminer -----------------------------------------------------------

  /** Open the two-step confirmation before ending the exam. */
  askTerminer(): void {
    this.actionError.set(null);
    this.confirmingEnd.set(true);
  }

  cancelTerminer(): void {
    this.confirmingEnd.set(false);
  }

  /**
   * End the exam (EN_COURS → TERMINE). Only reachable in overtime (the button is
   * gated on {@link overtimeRunning}), so we never end before the scheduled end.
   * The backend rejects ending while paused (resume first). On success the exam
   * is TERMINE → the live tab disappears, so we move to the Résultats tab.
   */
  terminer(): void {
    if (this.terminating()) return;
    this.terminating.set(true);
    this.actionError.set(null);
    this.examApi.changerStatut(Number(this.id()), 'TERMINE').subscribe({
      next: (e) => {
        this.store.exam.set(e);
        this.terminating.set(false);
        this.confirmingEnd.set(false);
        this.router.navigate(['/examens', Number(this.id()), 'resultats']);
      },
      error: (err) => {
        this.terminating.set(false);
        this.actionError.set(this.message(err, "Impossible de terminer l'examen."));
      },
    });
  }

  // ---- student drill-down -------------------------------------------------

  isOpen(stationId: number): boolean {
    return this.openStations().has(stationId);
  }

  toggle(stationId: number): void {
    this.openStations.update((set) => {
      const next = new Set(set);
      if (next.has(stationId)) {
        next.delete(stationId);
      } else {
        next.add(stationId);
        // Resolve every slot's students for the opened station.
        const lane = this.lanes().find((l) => l.stationId === stationId);
        lane?.slots.forEach((s) => this.ensureStudents(s.rotationId));
      }
      return next;
    });
  }

  /** Fetch + cache the student names of one rotation (idempotent, guarded). */
  private ensureStudents(rotationId: number): void {
    if (this.students()[rotationId] || this.loadingRotations.has(rotationId)) return;
    this.loadingRotations.add(rotationId);
    this.scoring.listAssignmentsByRotation(rotationId).subscribe({
      next: (assignments) => {
        const pMap = this.partById();
        const eMap = this.etuById();
        const names = assignments
          .map((a) => {
            const part = a.participationId != null ? pMap.get(a.participationId) : undefined;
            const etu = part?.etudiantId != null ? eMap.get(part.etudiantId) : undefined;
            return etu ? `${etu.prenom ?? ''} ${etu.nom ?? ''}`.trim() : null;
          })
          .filter((n): n is string => !!n)
          .sort((a, b) => a.localeCompare(b));
        this.students.update((m) => ({ ...m, [rotationId]: names }));
        this.loadingRotations.delete(rotationId);
      },
      error: () => {
        // Leave uncached so a later tick/expand can retry; show "…" meanwhile.
        this.loadingRotations.delete(rotationId);
      },
    });
  }

  // ---- formatting ---------------------------------------------------------

  private fmtDuration(ms: number): string {
    const total = Math.floor(ms / 1000);
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = total % 60;
    const mm = String(m).padStart(2, '0');
    const ss = String(s).padStart(2, '0');
    return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
  }

  private hhmm(ms: number | null): string {
    if (ms == null) return '--:--';
    const d = new Date(ms);
    return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  }

  private message(
    err: { status?: number; error?: { message?: string } },
    fallback: string,
  ): string {
    if (err?.status === 403) return "Vous n'avez pas les droits sur cet examen.";
    if ((err?.status === 400 || err?.status === 409) && typeof err.error?.message === 'string') {
      return err.error.message;
    }
    return fallback;
  }
}
