# ADR 0015: Exam definition snapshot at launch — data plane vs control plane

- **Date:** 2026-07-20
- **Status:** **Accepted** — ratifié par la fusion de la PR #245 dans `develop` le 2026-07-20
  (`072527a`). Voir la *limite connue* en fin de §1 : la promesse « une panne est sans effet » vaut
  pour les lectures de scoring, **pas** pour le chemin de notation mobile (#244).
- **Deciders:** Nada (lead architect).
- **Related:** **ADR-0014** + **ADR-0014-A** (évaluateur-paced lifecycle — this ADR must NOT weaken
  them; see the guardrail in Decision 4), ADR-0006 (matière as cross-service logical FK — this ADR
  extends the same precedent from one column to a definition set), ADR-0009 (pause/resume — pause
  state is *control plane* and stays live), ADR-0010 (launch timestamp), ADR-0013 (notation lock).
  Findings addressed: #200 / finding #7 (placeholder station name), the unweighted-grade defect and
  the leaf-guard fail-open (both found 2026-07-20), #238 (dead end — *partially*: one of its two
  causes), plus an unfiled score-reproducibility hole.
  Code in scope: `ExamServiceClient`, `EvaluateurDashboardService`, `NotationReajustementService`,
  `ExamenServiceImpl.changerStatut`, `Examen`, new `scoring_db` snapshot tables.

## Context

Scoring-service fetches, **over the network and at request time**, data that the domain guarantees
cannot change. That is the defect. Every symptom below is downstream of it.

### The freeze rule already exists (verified `file:line`, 2026-07-20)

`Examen.isGrilleModifiable()` (`Examen.java:152-154`) returns true **only** for `BROUILLON` and
`CONFIGURE`. It is enforced at 10+ call sites (`GrilleServiceImpl:57,98,151,170,192,241,288,362`,
`GrilleTemplateServiceImpl:134`, `StationServiceImpl:147,167`). ⇒ **From `EN_COURS` onward, station
names, item types, pondérations and grille structure are immutable by construction.** Scoring asks
for them anyway, on every request.

### What that costs today (all verified this session)

1. **Silent placeholder names.** `ExamServiceClient:44` `STATION_FALLBACK_PREFIX = "Station "`,
   returned by `getStationInfo` (:143-160) on *any* failure — exception, null body, or missing
   `data.nom`. Consumed per-rotation at `EvaluateurDashboardService:398` (`buildSessions`) ⇒ an
   **N+1 HTTP call**, each with an independent chance of degrading. Explicitly **not cached** (:141).
2. **Silent unweighted grades.** `getItemInfosForGrille` returns an **empty map** on failure
   (:118-132). `recalculerScoreFinal` (`EvaluateurDashboardService:489-499`) then hits
   `info == null` at :495 and scores every BINAIRE item **raw**: an item `valeur 1 × pondération 5`
   yields **1 instead of 5**. The wrong `score_final` is **persisted** (:497-498) and **broadcast**
   (:257). No error reaches the évaluateur.
3. **Leaf guard fails open.** `saisirNotation:227` is
   `if (!feuillesValides.isEmpty() && !contains(itemId))` — the empty set from the same outage
   **skips the guard**, letting a `NotationItem` be written on a *parent* criterion. Since
   `recalculerScoreFinal` sums **all** items with no parent/leaf filter, that row then double-counts
   **permanently**, including after exam-service returns.
4. **The existing cache is accidental, not designed.** `grilleItemsCache`
   (`ExamServiceClient:100,124,129`) is written on first success and **never invalidated, expired or
   cleared** (verified: no `evict` / `clear()` / `invalidate` anywhere in scoring-service). It is
   in-memory and per-instance ⇒ lost on every restart/deploy, cold-windowed on startup, divergent
   across replicas, and **silently ignores any grille edit**.
5. **Scores are not reproducible.** `score_final` is recomputed from *live* exam-service data, so
   editing a grille retroactively changes marks already awarded. **Unfiled.** For a real exam this is
   a correctness requirement, not a nicety: a grade must mean what it meant on exam day.
6. **Duplicated, already-diverged logic.** `NotationReajustementService:130-144` is an acknowledged
   copy whose javadoc (:127) insists the two "MUST stay identical". They are not — :137 guards a null
   `valeur`, `EvaluateurDashboardService:495` unboxes it directly (**NPE on the grading path**).

### Live proof — one outage, three simultaneous failures

Reproduced 2026-07-20 by stopping `epos-exam-service` and calling `/api/v1/evaluateur/dashboard`
as eval3 (then restarting; recovery was clean in ~10 s):

| | exam-service UP | exam-service DOWN |
|---|---|---|
| `stationNom` | real name | **`Station 5` / `Station 9` / `Station 26`** |
| `statut` | `EN_COURS` | **ALL `TERMINEE`** |
| session list | 4 (eval3 / exam 2) | **sessions from other exams leak in** |

All three fail **silently**. Recovery is automatic, so **the evidence evaporates** — which is why
this reproduces on demand only when forced, and why probing "does it work?" returns green (6/6
dashboard calls returned the true name minutes earlier).

## Decision

### 1. The exam definition is MATERIALISED into `scoring_db`, write-once

- `exam_station_snapshot(examen_id, station_id, nom)`
- `exam_item_snapshot(examen_id, grille_id, item_id, type, ponderation)`

Written **once**, immutable thereafter. Scoring reads **only** the snapshot for names and grading
arithmetic — never the network. Implemented as `V8__exam_definition_snapshot.sql`.

**No `parent_id` / `ordre` — corrected 2026-07-20 after checking the upstream sources.** The only
available feeds are `GET /api/stations/{id}` → `{nom, examenId}` and
`GET /api/grilles/{grilleId}/items/feuilles` → `ItemInfo(id, ponderation, type)`
(`ExamServiceClient:62`). The latter returns **only leaves** — *"Aplatit l'arbre : ne renvoie que les
feuilles"* (`GrilleService:55`). So `parent_id` is not obtainable and would have been a dead column.

It is also **not needed**: **the snapshot IS the notable-item set.** An item absent from
`exam_item_snapshot` is by definition not notable. This is strictly better than today's guard —
`saisirNotation:227` skips itself when the remote lookup returns empty, whereas a local set makes the
leaf check **unconditional**. Corollary that must be implemented: `recalculerScoreFinal` must **fail
on an unknown item**, never score it raw — scoring it raw is precisely how a parent row double-counts.

#### Where it is written — corrected 2026-07-20 after verifying the code

An earlier draft said "in the launch transaction". **That is not implementable as stated**, verified:

- Launch lives in **exam-service** (`ExamenServiceImpl.changerStatut:164-191`). For it to write into
  `scoring_db` it would have to call scoring — **inverting the only established client direction**
  (scoring→exam via `ExamServiceClient`), which this same ADR rejects under *Alternatives*.
- The obvious scoring-side hook does not exist: rotation generation is
  `POST /lots/{lotId}/generer` (`RotationGenerationController:35`) — **per-lot, manual, day-of**, and
  it may never be called at all (the known "launch without generating rotations" gap). It is also
  per-**lot**, whereas the snapshot is per-**exam**.

**Decision: materialise on first successful use ("persist-on-first-touch"), write-once.**
The first scoring operation that needs the definition fetches it, **persists it**, and every
subsequent read is local.

This is **not** a cache, on all four counts that made `grilleItemsCache` fail:
it is in the **database** (survives restart/deploy, shared across replicas), **write-once** (so
grades stay reproducible), **never silently refreshed** — and since edits are already blocked from
`EN_COURS` (`isGrilleModifiable`), never refreshing is *correct*, not a compromise.

**Failure rule — the part that must not be softened:** if the fetch fails, **persist nothing and
fabricate nothing**. Fail that request loudly. A missing snapshot must never degrade into a
placeholder or an unweighted score. Cost: a narrow window right after launch where, if exam-service
is down, grading cannot start — but it stops **visibly** instead of silently recording wrong marks.
After the first success, exam-service may stay down indefinitely with no effect on grading.

> ⚠️ **LIMITE CONNUE — cette promesse ne vaut pas encore de bout en bout (#244).** Vérifié en E2E le
> 2026-07-20, en pilotant l'app réelle contre un exam-service arrêté : l'évaluateur atteint bien son
> tableau de bord dégradé (4 sessions figées gardent leur vrai intitulé, 8 non figées affichent
> `Intitulé indisponible`, aucune n'est retirée), **puis ne peut ouvrir aucune station**. Le mobile
> lit la grille **directement dans exam-service** (`api_constants.dart:74`, sans cache) et
> `Future.wait` (`grading_bloc.dart:347`) fait échouer tout le chargement sur cette seule branche.
>
> Autrement dit : la promesse tient pour **les lectures faites par scoring**, et le point de
> défaillance s'est **déplacé** du tableau de bord vers l'écran de notation plutôt que de disparaître.
> L'écriture reste immunisée **au niveau API** — mais **inatteignable depuis l'app**. Deux voies
> possibles, non exclusives : étendre le snapshot à la structure de grille (tient la promesse telle
> qu'écrite ici), ou cacher la grille sur l'appareil (réponse offline-first, ADR-0002).

**Optional warm-up (optimisation, not correctness):** an explicit
`POST /api/v1/exams/{id}/snapshot` on scoring, callable from the launch UI right after launch, closes
even that window. The correctness guarantee is persist-on-first-touch; this only moves the moment.

### 2. Control plane stays live, but never fabricates and never fails closed

Exam **statut** and **pause** genuinely change during an exam; they stay a live read. Rules:

- **Never invent a value.** `ExamTiming.neutral()` synthesising a status is what manufactured the
  all-`TERMINEE` board above. Unknown must be representable as *unknown*, distinct from any real state.
- **Fail OPEN.** Per the guard's own comment (`EvaluateurDashboardService:91-92`): *"Montrer un
  examen de trop est gênant ; n'en montrer aucun est fatal."*
- **Last-known-good + visible staleness** ("état à 10:42") — the évaluateur keeps working and knows
  what they are looking at. Silence and a hard error are both worse.

### 3. The fallbacks are then DELETED, not improved

With Decision 1, `STATION_FALLBACK_PREFIX` and the empty-map path become unreachable for grading.
Delete them. A fallback that cannot be reached cannot fire silently. The leaf guard (:227) loses its
`isEmpty()` escape hatch and becomes unconditional. The duplicate `recalculerScoreFinal` is unified
(#68) or, until then, fixed in **both** copies.

### 4. ⛔ GUARDRAIL — this ADR introduces NO clock authority. It must not become a ceiling.

The snapshot stores **definition** — names, types, pondérations, parent/child structure. It stores
**no timing, no status, no derived state**. Explicitly:

- `debutCreneau` remains an **indicative PLAN** (ADR-0014-A), never a control input. It is *not*
  snapshotted as an authority over anything.
- Nothing here permits computing a session's state from `now`, `debutCreneau`, or a countdown.
  **House rule stands: if you derive state from the clock, stop — that's the stopwatch.**
- The clock may impose a **FLOOR** (a student gets their full time) but never a **CEILING**
  (retiring a session because it "expired"). Session 19's dead ends and the frozen timer were all
  ceilings. *(Floor/ceiling is a session-19 proposal refining ADR-0014, not yet ratified — recorded
  here as the constraint this ADR is designed against, not as settled doctrine.)*
- **`dureeStationMin` is snapshot data, not a deadline.** It sizes the countdown the évaluateur
  sees; it must never retire a session, hide a card, or flip a statut.
- Advance stays **explicit évaluateur action** (PACE), never elapsed time.

Removing the live fetch removes an *input to* the clock-derived status path — it must not be taken
as licence to reintroduce one.

## Consequences

**Positive**

- Placeholder names become **impossible** (#200 / finding #7 closed at the root, not patched).
- Unweighted grades and the parent-row double-count become **impossible**.
- The dashboard's per-rotation N+1 collapses to a local read — latency and coupling both drop.
- **Grading survives an exam-service outage entirely.** Only *launching* a new exam needs it.
- **Grades become reproducible**: a mark reflects the grille as it was at launch.
- One of #238's two causes is removed (the outage-induced one). The clock-drift cause is #207's, and
  this ADR deliberately does not touch it.

**Negative / cost**

- A Flyway migration + a write path in the launch transaction.
- **#183 "dé-lancer" (`EN_COURS → CONFIGURE`) must invalidate the snapshot** so a relaunch re-copies
  it — otherwise an edited grille silently grades against the stale copy. This is the one sharp edge.
- Storage duplication in `scoring_db` (small: stations + items per exam).
- Legacy exams launched before this ADR have no snapshot ⇒ need a backfill, or a read path that
  tolerates absence **loudly** (explicit "definition unavailable", never a fabricated placeholder).

## Alternatives rejected

- **"Fail loudly" only.** Turns a silent wrong answer into a visible stoppage — better, but it still
  stops grading during an exam for data that never needed fetching. Treats the symptom.
- **A proper cache (TTL / Caffeine / Redis).** A cache is a **latency** optimisation; it is
  probabilistic by nature, so it always needs a miss path — i.e. a fallback — i.e. this bug again.
  The current accidental cache is the proof. A snapshot is deterministic: the data is in
  `scoring_db` or the exam never launched.
- **Event/messaging (Kafka etc.).** Correct shape at larger scale, disproportionate here: one
  transactional copy at a single well-defined transition, with no broker to operate before a
  2026-09-01 defense.
- **Move grading maths into exam-service.** Inverts an established direction — the only existing
  client is scoring→exam (`ExamServiceClient`), and `validerGroupe:367-372` already reaches upward
  this way. Would also put grade computation outside the service that owns grades.
