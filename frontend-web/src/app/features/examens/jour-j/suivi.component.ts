import { HttpErrorResponse } from '@angular/common/http';
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
import { ExamApiService } from '../../../core/api/exam-api.service';
import { ScoringApiService } from '../../../core/api/scoring-api.service';
import { DirectoryApiService } from '../../../core/api/directory-api.service';
import {
  EtudiantSummary,
  ParticipationSummary,
  RotationStatus,
  RotationSummary,
  StationProgression,
  StationSummary,
  SuiviProgression,
  UserResponse,
} from '../../../core/api/models';
import { ExamenWorkspaceStore } from '../workspace/examen-workspace.store';

/** One créneau slot at a station — a group's scheduled visit. */
export interface Slot {
  rotationId: number;
  ordrePassage: number;
  debutMs: number; // absolute planned start (ms epoch, local)
  debutLabel: string; // HH:mm
  /**
   * The rotation's real completion signal. EN_ATTENTE until the évaluateur
   * validates its lot, then TERMINE (scoring validerLot). #184 uses this to
   * distinguish a truly-finished slot from one whose créneau merely elapsed.
   */
  statut: RotationStatus;
}

/** A station's whole timeline + its bound évaluateur. */
interface Lane {
  stationId: number;
  nom: string;
  ordre: number;
  /** #296 — l'id, pas seulement le nom : la suppléance en a besoin. */
  evaluateurId: number | null;
  evaluateurNom: string | null;
  slots: Slot[]; // sorted by debutMs
}

type SlotState = 'done' | 'live' | 'upcoming';

/**
 * État d'une station sur le tableau — désormais **LU** dans la progression servie par le
 * backend (#208), plus jamais déduit de l'horloge.
 *
 * <p>{@code sansRotations} n'est pas un état temporel : la station n'a aucun planning, et il
 * est résolu AVANT tout le reste pour qu'une station inconnue ne puisse jamais se lire comme
 * terminée (#182).
 *
 * <p>⛔ {@code enRetard} (« dépassement ») a été **supprimé**. Ce n'était pas un état mais une
 * opinion de l'horloge sur un travail qu'elle ne voyait pas : le tableau l'affichait sur des
 * rotations pourtant `TERMINE` en base (constaté le 2026-07-21 sur l'examen 31). ADR-0014 : le
 * temps ne mesure pas l'avancement, l'avancement se lit.
 */
export type LaneState =
  | 'sansRotations'
  | 'live'
  | 'upcoming'
  | 'done';

const DEFAULT_DUREE_MIN = 15;
const DEFAULT_HEURE = '09:00';

/**
 * Résout l'état d'une station **à partir de l'avancement stocké**, jamais de l'heure (#208).
 *
 * <p>La branche {@code sansRotations} vient EN PREMIER et doit y rester : une station sans
 * planning est INCONNUE, pas terminée. Le tableau tombait autrefois en cascade
 * live → upcoming → « Terminée », si bien qu'une station dont les rotations n'avaient jamais
 * été générées s'affichait comme déjà finie — l'état terminal fantôme qui a coûté une
 * simulation d'examen entière (#182).
 *
 * <p><b>Ce que cette fonction ne fait plus.</b> Elle prenait {@code effectiveNowMs} et une
 * durée, et comparait des créneaux : d'où le « dépassement » affiché sur des passages
 * pourtant validés. Le backend écrit désormais une vraie progression (#207) et la sert
 * dérivée (#208) : on la lit.
 *
 * @param slots  les passages de la station (peut être vide) — sert UNIQUEMENT à distinguer
 *               « aucun planning » du reste
 * @param statut le statut servi par le backend pour cette station, ou null s'il est absent
 *               de la progression (station sans vague ouverte)
 */
export function resolveLaneState(
  slots: readonly Slot[],
  statut: RotationStatus | null,
): LaneState {
  if (slots.length === 0) return 'sansRotations';
  switch (statut) {
    case 'EN_COURS':
      return 'live';
    case 'TERMINE':
      return 'done';
    default:
      // EN_ATTENTE, ou station absente de la vague affichée : elle n'a pas commencé.
      // Surtout pas « done » — cf. le fantôme #182 ci-dessus.
      return 'upcoming';
  }
}

/**
 * Suivi en direct — le tableau de pilotage du jour J (examens EN_COURS uniquement).
 *
 * <p><b>L'avancement se LIT, il ne se déduit plus de l'heure (#208, ADR-0014).</b> L'ancien
 * en-tête de cette classe commençait par « Why the clock, not the status » : les rotations
 * restaient EN_ATTENTE en base et rien ne les faisait jamais avancer, donc l'écran devinait
 * l'état de chaque passage en comparant l'heure courante aux créneaux. Cette prémisse est
 * MORTE : #207 écrit une vraie progression (EN_COURS à l'ouverture d'un groupe, TERMINE à sa
 * validation), et le backend la sert dérivée ({@code GET /lots/examens/{id}/progression}).
 * Le tableau affiche cette progression. Il ne possède AUCUN état calculé depuis
 * {@code Date.now()} — c'est ainsi qu'il affichait « dépassement — encore en cours » sur des
 * rotations pourtant TERMINE en base (constaté le 2026-07-21).
 *
 * <p><b>Ce que le responsable voit (spec Nada, 2026-07-21) :</b> par station, le groupe en
 * cours et « N/M notés » ; pour la vague, un chronomètre unique qui repart à zéro à chaque
 * ouverture de lot (#252, durée calculée PAR LE SERVEUR — les fuseaux navigateur/backend
 * divergent, ADR-0010) et s'arrête quand la vague est finie. Aucun « dépassement », nulle
 * part : ce n'était pas un état mais une opinion de l'horloge sur un travail qu'elle ne
 * voyait pas.
 *
 * <p><b>La poignée de main (ADR-0014-B) :</b> quand tous les évaluateurs ont validé leurs
 * groupes, l'alerte « Lot N terminé » apparaît et le responsable — lui seul — ouvre la vague
 * suivante (« Ouvrir le lot N+1 »). « Terminer l'examen » se débloque quand toutes les vagues
 * sont passées, dérivé de l'avancement réel et non d'une heure atteinte.
 *
 * <p><b>Rafraîchissement :</b> la progression est rechargée toutes les 5 s et après chaque
 * action. L'ancien intervalle d'1 s ne faisait avancer QUE l'horloge d'affichage sur des
 * données figées au chargement — un chronomètre posé sur un tableau mort.
 *
 * <p><b>« Terminée » n'est jamais un défaut de fallthrough (#182) :</b> une station SANS
 * rotations est {@code sansRotations} — inconnue, pas finie — résolu AVANT toute autre
 * branche. Ne jamais réintroduire un défaut qui se lit comme « fini ».
 *
 * <p><b>Les horaires plannifiés restent affichés</b> (frise, libellés HH:mm) : c'est le PLAN,
 * une indication ADR-0014-A §3. Ils ne pilotent ni statut, ni visibilité, ni action.
 */
@Component({
  selector: 'app-suivi',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './suivi.component.html',
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
  /** Resetting the exam (EN_COURS → CONFIGURE, #183) is in flight. */
  readonly resetting = signal(false);
  /** The two-step "Réinitialiser" confirmation is showing. */
  readonly confirmingReset = signal(false);

  /**
   * #208 / #252 — la progression dérivée par le backend. C'est la SOURCE de l'avancement
   * affiché : statut de station, groupe en cours, « N/M notés », chronomètre de la vague,
   * alerte « lot terminé » et cible de « Lot suivant ». Le composant n'en recalcule rien.
   */
  readonly progression = signal<SuiviProgression | null>(null);
  /** Une ouverture de vague est en cours (bouton « Lot suivant »). */
  readonly ouvertureEnCours = signal(false);

  // ---- #185 — le conducteur « Présence & démarrer » ------------------------
  /** L'acte combiné présence + génération est en vol. */
  readonly demarrageEnCours = signal(false);
  /** Refus du backend, montré tel quel (déjà nominatif et actionnable). */
  readonly demarrageError = signal<string | null>(null);
  /** Avertissement non bloquant (capacité…) du dernier démarrage réussi. */
  readonly demarrageInfo = signal<string | null>(null);

  /**
   * #185 — le premier lot à démarrer : l'examen est lancé mais AUCUNE vague n'a
   * encore tourné, et le prochain lot n'a pas de circuit. C'est l'état d'entrée
   * du jour J — le conducteur y remplace l'alarme « stations sans rotations ».
   */
  readonly conducteurPremierLot = computed(() => {
    const p = this.progression();
    if (!p || p.lotOuvert != null) return null;
    const s = p.lotSuivant;
    return s && !s.rotationsGenerees ? s : null;
  });

  // Conservés pour pouvoir recharger les passages seuls (#208) sans refaire tout le load.
  private readonly stationsCache = signal<StationSummary[]>([]);
  private readonly evaluateursCache = signal<UserResponse[]>([]);

  // ---- #296 — suppléance d'un évaluateur en pleine épreuve (ADR-0017 §3) ----
  /** Station dont le panneau de remplacement est ouvert, ou null. */
  readonly remplacementStationId = signal<number | null>(null);
  readonly remplacantId = signal<number | null>(null);
  readonly motifRemplacement = signal('');
  readonly remplacementEnCours = signal(false);
  readonly remplacementErreur = signal<string | null>(null);
  /** Bilan affiché après coup : combien de groupes ont changé de main. */
  readonly remplacementBilan = signal<string | null>(null);

  /**
   * Les évaluateurs proposables en suppléance : joignables, et pas déjà sur une
   * autre station de la vague (le serveur refuse ce cas — autant ne pas l'offrir).
   * On EXCLUT les comptes retirés ou verrouillés : proposer quelqu'un qui ne peut
   * pas se connecter serait remplacer un problème par le même (#287/#294).
   */
  remplacantsPossibles(lane: Lane): UserResponse[] {
    const maintenant = Date.now();
    const prisAilleurs = new Set(
      this.lanes()
        .filter((l) => l.stationId !== lane.stationId && l.evaluateurId != null)
        .map((l) => l.evaluateurId as number),
    );
    return this.evaluateursCache()
      .filter((u) => u.id !== lane.evaluateurId)
      .filter((u) => !prisAilleurs.has(u.id))
      .filter((u) => u.isActive)
      .filter((u) => !u.lockedUntil || new Date(u.lockedUntil).getTime() <= maintenant)
      .sort((a, b) => a.nom.localeCompare(b.nom));
  }

  ouvrirRemplacement(lane: Lane): void {
    this.remplacementErreur.set(null);
    this.remplacementBilan.set(null);
    this.remplacantId.set(null);
    this.motifRemplacement.set('');
    this.remplacementStationId.set(lane.stationId);
  }

  fermerRemplacement(): void {
    this.remplacementStationId.set(null);
  }

  onRemplacantChange(value: string): void {
    this.remplacantId.set(value ? Number(value) : null);
  }

  /**
   * La suppléance n'a de sens qu'une fois la vague DÉMARRÉE : avant, les
   * rotations n'existent pas et la bonne action est de réaffecter la station
   * (ADR-0017 §2). Le serveur le dit aussi, mais autant ne pas proposer un
   * bouton qui ne peut que refuser.
   */
  peutRemplacer(): boolean {
    return this.progression()?.lotOuvert != null;
  }

  confirmerRemplacement(lane: Lane): void {
    const lotId = this.progression()?.lotOuvert?.id;
    const nouvel = this.remplacantId();
    const motif = this.motifRemplacement().trim();
    if (lotId == null || nouvel == null) {
      this.remplacementErreur.set('Choisissez la personne qui prend la station.');
      return;
    }
    if (!motif) {
      this.remplacementErreur.set(
        'Le motif est obligatoire : une suppléance doit pouvoir s’expliquer après coup.',
      );
      return;
    }
    this.remplacementEnCours.set(true);
    this.remplacementErreur.set(null);
    this.scoring.remplacerEvaluateur(lotId, lane.stationId, { nouvelEvaluateurId: nouvel, motif })
      .subscribe({
        next: (res) => {
          this.remplacementEnCours.set(false);
          this.remplacementStationId.set(null);
          this.remplacementBilan.set(res.message);
          // Le tableau doit refléter le nouveau nom immédiatement : les
          // rotations viennent de changer de main.
          this.reloadLanes();
        },
        error: (err: HttpErrorResponse) => {
          this.remplacementEnCours.set(false);
          this.remplacementErreur.set(
            err.error?.message ?? 'Le remplacement a échoué. Réessayez.',
          );
        },
      });
  }

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
  private readonly lastSlotEndMs = computed(() => {
    let max = 0;
    for (const lane of this.lanes()) {
      const last = lane.slots[lane.slots.length - 1];
      if (last) max = Math.max(max, last.debutMs + this.dureeMs());
    }
    return max;
  });

  /**
   * #208 — progression du circuit, dérivée des GROUPES BOUCLÉS de la vague affichée,
   * plus jamais de l'horloge. L'ancienne version mesurait (now − début) / (fin − début)
   * sur le planning : la barre avançait donc toute seule, travail fait ou pas, et
   * saturait à 100 % dès que la pendule dépassait le plan — le plafond en pixels.
   */
  readonly globalProgressPct = computed(() => {
    const lot = this.progression()?.lotOuvert;
    if (!lot || lot.groupesTotal === 0) return 0;
    return Math.min(100, (lot.groupesTermines / lot.groupesTotal) * 100);
  });

  readonly startLabel = computed(() => this.hhmm(this.examStartMs()));
  readonly endLabel = computed(() => this.hhmm(this.lastSlotEndMs() || null));

  constructor() {
    // #208 — RAFRAÎCHISSEMENT DES DONNÉES, à la place du tic d'horloge.
    //
    // Il n'y avait ici qu'un `setInterval(() => this.now.set(Date.now()), 1000)` : un
    // compteur qui avançait sur des données FIGÉES au chargement. Le tableau ne se
    // rechargeait jamais — vérifié le 2026-07-21 : une fois toutes les notations finies,
    // les stations restaient « Dépassement / encore en cours », et seul un F5 les faisait
    // passer à « Terminée ». Le responsable regardait un chronomètre, pas son examen.
    //
    // Supprimer le mauvais intervalle et ajouter le bon est donc le MÊME geste. Le pas est
    // volontairement lent (5 s) : la progression change au rythme d'actes humains (valider
    // un groupe, ouvrir une vague), pas à la seconde.
    //
    // Pas de WebSocket : `frontend-web` n'a aucune dépendance STOMP/SockJS, et le
    // `broadcastLotStatus` du backend n'a donc aucun abonné web (vérifié 2026-07-21).
    const timer = setInterval(() => this.refreshProgression(), 5000);
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

  /**
   * #208 — recharge la progression seule (léger : une requête, tout dérivé côté serveur).
   * Appelé par le minuteur de rafraîchissement ET après chaque action du responsable, pour
   * que le tableau dise la vérité sans exiger un F5.
   *
   * <p>Recharge AUSSI les couloirs quand l'avancement d'une station a bougé : les passages
   * (`lane.slots`) portent le statut stocké, or c'est lui qui colore la frise et l'historique.
   * Sans cela le bandeau serait à jour et la frise resterait figée — l'incohérence même que
   * ce ticket supprime.
   */
  private refreshProgression(): void {
    const examId = Number(this.id());
    if (!Number.isFinite(examId) || this.loading()) return;
    this.scoring.getProgression(examId).subscribe({
      next: (p) => {
        const avant = this.signatureProgression(this.progression());
        this.progression.set(p);
        if (this.signatureProgression(p) !== avant) this.reloadLanes();
      },
      // Silencieux : un rafraîchissement périodique qui échoue ne doit pas remplacer le
      // tableau par une erreur. Le prochain passage réessaiera.
      error: () => {},
    });
  }

  /** Empreinte de l'avancement : change dès qu'une station ou la vague progresse. */
  private signatureProgression(p: SuiviProgression | null): string {
    if (!p) return '';
    const st = p.stations
      .map((s) => `${s.stationId}:${s.statut}:${s.rangEnCours}:${s.etudiantsNotes}`)
      .join('|');
    return `${p.lotOuvert?.id ?? '-'}/${p.lotTermine}/${st}`;
  }

  private load(examId: number): void {
    this.loading.set(true);
    this.error.set(false);
    forkJoin({
      stations: this.examApi.listStations(examId),
      evaluateurs: this.directory.listUsers('EVALUATEUR'),
      participations: this.scoring.listParticipations(examId),
      etudiants: this.scoring.listEtudiants(),
      // #208 — l'avancement, dérivé côté serveur. Chargé AVEC le reste : sans lui, les
      // couloirs se rendraient une fraction de seconde sans statut, donc en « à venir ».
      progression: this.scoring.getProgression(examId),
    }).subscribe({
      next: ({ stations, evaluateurs, participations, etudiants, progression }) => {
        this.indexNames(participations, etudiants);
        this.progression.set(progression);
        const sorted = [...stations].sort(
          (a, b) => (a.ordre ?? 0) - (b.ordre ?? 0),
        );
        if (sorted.length === 0) {
          this.lanes.set([]);
          this.loading.set(false);
          return;
        }
        // Fan out: each station's whole rotation timeline.
        this.stationsCache.set(sorted);
        this.evaluateursCache.set(evaluateurs);
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

  /**
   * #208 — recharge uniquement les passages (statuts stockés), sans repasser par les
   * stations/évaluateurs/roster déjà en mémoire. Déclenché quand la progression signale
   * un mouvement, pour que la frise et l'historique suivent le bandeau.
   */
  private reloadLanes(): void {
    const stations = this.stationsCache();
    const evaluateurs = this.evaluateursCache();
    if (!stations.length) return;
    forkJoin(stations.map((s) => this.scoring.listRotationsByStation(s.id))).subscribe({
      next: (rotationsPerStation) => {
        this.lanes.set(this.buildLanes(stations, rotationsPerStation, evaluateurs));
      },
      error: () => {}, // rafraîchissement silencieux — cf. refreshProgression
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
            statut: r.statut,
          };
        })
        .filter((s) => !Number.isNaN(s.debutMs))
        .sort((a, b) => a.debutMs - b.debutMs);
      // Qui tient la station MAINTENANT. Le générateur lie le même évaluateur à
      // toutes les rotations, donc n'importe laquelle suffisait — jusqu'à ce que
      // la suppléance (#296) existe : elle ne transfère QUE les groupes non
      // terminés, exprès, pour que le travail fait reste au nom de son auteur.
      // Prendre la première rotation venue affichait alors l'ancien nom sur un
      // groupe déjà noté (constaté à l'écran après un remplacement réussi). On
      // lit donc les rotations ENCORE À FAIRE en priorité.
      const evId =
        rots.find((r) => r.evaluateurId != null && r.statut !== 'TERMINE')?.evaluateurId ??
        rots.find((r) => r.evaluateurId != null)?.evaluateurId ??
        s.evaluateurIds?.[0] ??
        null;
      return {
        stationId: s.id,
        nom: s.nom ?? `Station ${s.ordre ?? i + 1}`,
        ordre: s.ordre ?? i + 1,
        evaluateurId: evId,
        evaluateurNom: evId != null ? evalName.get(evId) ?? null : null,
        slots,
      };
    });
  }

  // ---- état d'un passage : LU, plus déduit de l'horloge (#208) -------------

  /**
   * L'état d'un passage vient de son statut STOCKÉ (#207 l'écrit enfin : `EN_COURS` à
   * l'ouverture d'un groupe, `TERMINE` à sa validation).
   *
   * <p>Avant, cette méthode comparait `effectiveNow` à `debutMs + durée` et retournait
   * « dépassement » dès que le créneau était passé — y compris sur des passages validés.
   * C'est exactement ce que Nada a vu le 2026-07-21 : quatre rotations `TERMINE` en base,
   * quatre badges « dépassement » à l'écran.
   */
  slotState(s: Slot): SlotState {
    switch (s.statut) {
      case 'EN_COURS':
        return 'live';
      case 'TERMINE':
        return 'done';
      default:
        return 'upcoming';
    }
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
    return resolveLaneState(lane.slots, this.stationStatut(lane.stationId));
  }

  /**
   * #208 — l'examen peut être clôturé quand **toutes les vagues sont passées** : la vague
   * affichée est terminée et il n'en reste aucune à ouvrir.
   *
   * <p>Remplace l'ancienne garde `overtimeRunning()`, qui n'autorisait « Terminer » qu'en
   * DÉPASSEMENT — c'est-à-dire précisément l'état que ce ticket supprime. La clôture est
   * ainsi DÉRIVÉE de l'avancement réel et non d'une heure atteinte : un examen en avance
   * peut être clôturé, un examen en retard ne l'est pas parce que la pendule le dit.
   */
  readonly toutesVaguesTerminees = computed(() => {
    const p = this.progression();
    if (!p || !p.lotOuvert) return false;
    return p.lotSuivant == null && p.lotOuvert.ecouleSec == null;
  });

  // ---- progression servie par le backend (#208) ---------------------------

  /** La ligne de progression de cette station, si elle appartient à la vague affichée. */
  stationProg(stationId: number): StationProgression | null {
    return this.progression()?.stations.find((s) => s.stationId === stationId) ?? null;
  }

  private stationStatut(stationId: number): RotationStatus | null {
    return this.stationProg(stationId)?.statut ?? null;
  }

  /** « 2/4 notés » — la seule statistique par station demandée par le responsable. */
  notesLabel(stationId: number): string | null {
    const p = this.stationProg(stationId);
    return p ? `${p.etudiantsNotes}/${p.etudiantsTotal} notés` : null;
  }

  /** Chronomètre de la vague en cours. Vide dès qu'elle est terminée — jamais de dépassement. */
  lotElapsedLabel(): string | null {
    const sec = this.progression()?.lotOuvert?.ecouleSec;
    return sec == null ? null : this.hms(sec);
  }

  private hms(totalSec: number): string {
    const h = Math.floor(totalSec / 3600);
    const m = Math.floor((totalSec % 3600) / 60);
    const s = Math.floor(totalSec % 60);
    const mm = String(m).padStart(2, '0');
    const ss = String(s).padStart(2, '0');
    return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
  }

  /**
   * #185 — « Présence & démarrer » : présence (tous présents) + génération du circuit en
   * une transaction backend ({@code LotDemarrageService}). Première vague de l'examen ⇒
   * elle s'ouvre d'elle-même (ADR-0014-B) ; vague suivante ⇒ « Ouvrir le lot N » reste
   * l'acte d'ouverture, gardé sur l'état des rotations. Les absents s'enregistrent dans
   * l'onglet Lots & présence — ce bouton est le chemin rapide « tout le monde est là ».
   */
  demarrerLot(lotId: number): void {
    if (this.demarrageEnCours()) return;
    this.demarrageEnCours.set(true);
    this.demarrageError.set(null);
    this.demarrageInfo.set(null);
    this.scoring.presenceEtDemarrer(lotId).subscribe({
      next: (r) => {
        this.demarrageEnCours.set(false);
        this.demarrageInfo.set(r.avertissement);
        this.reload();
      },
      error: (err) => {
        this.demarrageEnCours.set(false);
        // Refus transactionnel : présence annulée avec la génération — le message
        // du backend dit quoi faire, on le montre tel quel.
        this.demarrageError.set(this.message(err, 'Démarrage du lot impossible.'));
      },
    });
  }

  /** « Lot suivant » — l'avancement lot→lot appartient au responsable (ADR-0014-B). */
  ouvrirLotSuivant(): void {
    const suivant = this.progression()?.lotSuivant;
    if (!suivant || this.ouvertureEnCours()) return;
    this.ouvertureEnCours.set(true);
    this.actionError.set(null);
    this.scoring.ouvrirLot(suivant.id).subscribe({
      next: () => {
        this.ouvertureEnCours.set(false);
        this.reload();
      },
      error: (err) => {
        this.ouvertureEnCours.set(false);
        // Les refus du backend sont explicites (« Lot 1 toujours en cours : 2 groupe(s)
        // restent à valider ») — on les montre tels quels plutôt qu'un message générique.
        this.actionError.set(this.message(err, "Impossible d'ouvrir le lot suivant."));
      },
    });
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

  /**
   * #208 — le passage « en ce moment » d'une station est celui dont le statut STOCKÉ est
   * EN_COURS. Avant : celui dont le créneau encadrait l'heure courante — d'où des étudiants
   * affichés « en passage » sur la foi de la pendule, même une fois leur groupe validé.
   */
  liveSlot(lane: Lane): Slot | null {
    return lane.slots.find((s) => s.statut === 'EN_COURS') ?? null;
  }

  pause(): void {
    if (this.pausing()) return;
    this.pausing.set(true);
    this.actionError.set(null);
    this.examApi.pauseExamen(Number(this.id())).subscribe({
      next: (e) => {
        // Capture the freeze on the client while the exam is still "running" in
        // local state, BEFORE swapping in the paused exam — timezone-proof.
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
        // Reprise : plus aucun gel client à lever — le tableau n'affiche plus d'horloge
        // qui court (#208). On recharge simplement la progression servie.
        this.store.exam.set(e);
        this.refreshProgression();
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

  // ---- réinitialiser (« dé-lancer », #183) --------------------------------

  /** Open the two-step confirmation before resetting the exam. */
  askReset(): void {
    this.actionError.set(null);
    this.confirmingReset.set(true);
  }

  cancelReset(): void {
    this.confirmingReset.set(false);
  }

  /**
   * Reset the exam (EN_COURS → CONFIGURE, #183 — « dé-lancer »). This spans TWO
   * services and MUST be ordered scoring → exam:
   *  1. scoring-service purges the generated plan (rotations/groupes) for every lot.
   *     It carries the #188 guard — if ANY notation exists it refuses (400) and
   *     wipes nothing, so the exam status is never touched on a graded exam.
   *  2. only once the plan is safely gone does exam-service flip the status and clear
   *     launched_at / pause.
   * On the scoring refusal we surface its message (it names the notations at risk).
   * Lots, roster and présence are kept. Exam lands in CONFIGURE → the live tab
   * disappears, so we move to the Lancement screen to re-launch when ready.
   */
  reset(): void {
    if (this.resetting()) return;
    this.resetting.set(true);
    this.actionError.set(null);
    const examenId = Number(this.id());
    this.scoring.resetRotationsExamen(examenId).subscribe({
      next: () => {
        this.examApi.resetExamen(examenId).subscribe({
          next: (e) => {
            this.store.exam.set(e);
            this.resetting.set(false);
            this.confirmingReset.set(false);
            this.router.navigate(['/examens', examenId, 'lancement']);
          },
          error: (err) => {
            this.resetting.set(false);
            this.actionError.set(
              this.message(
                err,
                'Le planning a été purgé mais le statut n’a pas pu être réinitialisé. Réessayez.',
              ),
            );
          },
        });
      },
      error: (err) => {
        this.resetting.set(false);
        this.actionError.set(this.message(err, 'Réinitialisation impossible.'));
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
