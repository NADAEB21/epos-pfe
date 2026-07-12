# ADR 0012: Inter-créneau buffer + real-time passage warning

- **Date:** 2026-06-28 (proposed) · 2026-06-28 (accepted)
- **Status:** **Accepted.** §0 (dashboard-clock reconciliation) ships first as its
  own PR; the buffer + warning feature (§1–§3) follows. Single-day implemented
  first; multi-day (ADR-0011) slots in when ratified.
- **Deciders:** Nada (lead architect). Raised by the professor (~2026-06-24):
  at launch the student passages start back-to-back with no warning; the prof
  wants a **transition gap + a warning before each passage**.
- **Related:** ADR-0009 (pause/resume execution model), ADR-0010 (launch instant
  + zoned time), ADR-0011 (multi-day scheduling — *Proposed*), ADR-0002
  (per-actor offline contract), `RotationGenerationService`,
  `EvaluateurDashboardService` (Feten's mobile dashboard, merged via #151),
  exam-management gaps #5 (planning tab) and #6 (évaluateur coverage).

## Context

The OSCE circuit is generated with **zero gap** between créneaux. Verified in
source (2026-06-28):

- `Rotation` (scoring) carries only `debutCreneau` (start), `ordrePassage`,
  `statut` — **no end time, no transition/buffer field** (`Rotation.java:24`).
- `RotationGenerationService.generateForLot` schedules créneau `t` at
  `waveStart + t·duree` (`:162`) and staggers lot `m` at
  `examStart + (m−1)·K·duree` (`:133`), where `duree = dureeStationMin`
  (default 15). So every passage abuts the previous one — no changeover time,
  no heads-up.
- The évaluateur mobile app (`EvaluateurDashboardService`, merged with #151)
  derives live session state by comparing
  `LocalDateTime.now(ZoneId.of("Africa/Tunis"))` directly to `debutCreneau`
  (`:320`, `:385-396`). It is a **poll** surface — Flutter calls
  `GET /api/evaluateur/dashboard` (`EvaluateurDashboardController.java:52`);
  there is no SSE/WebSocket and no server-time endpoint anywhere.

The professor's request is small on the surface (insert a gap; warn before the
next passage) but lands on the project's hardest seam: **real-time clock
coordination between the server-generated plan and an offline-capable Flutter
client**, across two prior decisions that the mobile dashboard *currently
ignores*.

### The clock problem (the "no room for mistakes" core)

There are today **four partially-disagreeing time notions**, and the warning
must reconcile all of them or it will fire on the wrong student:

1. **`launched_at` anchor** (ADR-0010) — the plan's origin, pinned to
   `Africa/Tunis` via the injectable `Clock`.
2. **The mobile dashboard's `now(Africa/Tunis)`** — raw server wall-clock
   (`EvaluateurDashboardService:320`).
3. **Pause-adjusted effective time** (ADR-0009) —
   `effectiveElapsed = (now − examStart) − total_pause_sec − (en_pause ? now − paused_at : 0)`.
   **The mobile dashboard does not apply this at all today** — it reads raw
   wall-clock. So during a pause, the dashboard already mis-reads the current
   créneau, and a naïve warning bolted on top would count down to a passage
   that the pause has pushed later.
4. **Multi-day per-day origin** (ADR-0011, *Proposed*) — the overnight gap is
   wall-time that is not exam time. The dashboard does not account for it.

A countdown that is wrong by even seconds (let alone the minutes a pause or an
overnight gap introduces) is unacceptable for an exam. So this ADR cannot just
add a config field — it must declare **one authoritative clock** and make the
warning a function of it.

## Decision

Three coupled decisions: (1) where the durations live, (2) the one
authoritative clock, (3) push vs poll. Plus one **prerequisite** without which
the feature is unsafe.

### 0. Prerequisite — reconcile the dashboard clock first, in its own PR

The warning feature is **blocked** until `EvaluateurDashboardService` stops
comparing raw `now()` to `debutCreneau` and instead computes session state in
**effective time** (ADR-0009) anchored on `launched_at` (ADR-0010), per-day
when ADR-0011 ships. This is not optional polish: the warning is derived from
the same comparison, so if the underlying state is pause-blind, the warning is
pause-blind. This ADR makes that reconciliation the first implementation step
(see §2). It also closes a pre-existing correctness bug, independent of the
warning.

**Sequencing (decided):** ship §0 as its **own standalone PR** before the
buffer+warning PR. It is a real bug fix on its own (the dashboard mis-reads the
current créneau during any pause today), it isolates the riskiest change — the
four-clock reconciliation — in a reviewable diff, and it lets the warning PR
build on a clock already proven in production. Bundling the clock fix with the
feature was rejected: it would put the subtlest correctness change and a new
feature in one review.

### 1. Durations live on `Examen` (exam-service), default 0 = back-compat

Two new nullable config fields on `Examen`, set at CONFIGURE time, surfaced in
the create/edit form (overlaps gap #5):

- **`temps_battement_min`** (`Integer`, default `0`) — the inter-créneau
  **transition/changeover gap** in minutes. The physical pause between
  passages: students move, the évaluateur resets the station.
- **`avertissement_lead_sec`** (`Integer`, default `0`) — how many seconds
  **before** the next passage starts the warning fires on the évaluateur's
  device. `0` = warnings off (opt-in).

These are distinct durations: `temps_battement_min` reshapes the *schedule*;
`avertissement_lead_sec` only governs *when the heads-up appears* and stores no
schedule state.

**Generation math changes** (`RotationGenerationService`): the slot unit
becomes `duree + battement`.

```
slot       = dureeStationMin + tempsBattementMin
creneau(t) = waveStart + t · slot                 // was t · duree
waveStart(m) = examStart + (m−1) · K · slot        // was (m−1)·K·duree
```

Because `battement` defaults to 0, every existing exam regenerates **identically
to today** — and existing already-generated `debutCreneau` rows stay valid
(they simply have `battement = 0` baked in). `ExamGenerationView` /
`ExamServiceClient` carry the two fields to scoring (same pattern as
`dureeStationMin`, `launchedAt`).

> **Multi-day coupling (ADR-0011) — decided: design for it, build single-day.**
> ADR-0011 is still *Proposed*; the prof's request must not block on it. So the
> server-side reconciliation that produces `debutPrevu` (§2) is **written once,
> structured to take a per-day origin**, but the **single-day path is
> implemented first** — multi-day fills in `lotsParJour` / per-day origin when
> 0011 ships, with no re-architecture of the clock. When 0011 ships, its
> `lotsParJour = floor((heureFin − heureDebut) / (K · slot))` must use the
> buffered `slot`, not raw `duree`. The two ADRs share the slot definition;
> whichever migration ships first owns exam-service **V6**, the other takes V7.
> This ADR claims **V6** provisionally (`examens.temps_battement_min`,
> `examens.avertissement_lead_sec`); renumber if 0011's `heure_fin` ships first.

### 2. One authoritative clock: the **server**, projected as absolute instants

The server is the single source of truth for time. The mobile **never
reconstructs** effective-time math (it does not today, and asking an
offline-first client to redo the ADR-0009/0010/0011 reconciliation is exactly
the error surface we must avoid). Instead:

On every `GET /api/evaluateur/dashboard` poll, the server returns, per upcoming
rotation owned by the évaluateur:

- **`debutPrevu`** — the **absolute predicted wall-clock start instant** of that
  passage, already reconciled: planned offset shifted by `launched_at`,
  by `total_pause_sec`, by the live `(now − paused_at)` if `en_pause`, and (with
  0011) onto the correct day. This is the *one* place the four clocks are
  combined — server-side, once.
- **`avertissementLeadSec`** — echoed from exam config so the client knows the
  threshold.
- **`enPause`** (boolean) and a top-level **`serverNow`** timestamp on the
  response envelope — so the client can (a) freeze its countdown when paused and
  (b) correct its local clock offset against the server.

The mobile computes, locally and per-second between polls:

```
offset            = serverNow − deviceClockAtFetch          // one subtraction per poll
secondsUntilNext  = debutPrevu − (deviceNow + offset)
fireWarning       = !enPause && secondsUntilNext ∈ (0, avertissementLeadSec]
```

This gives a smooth per-second countdown and a precise warning **without** the
client knowing anything about pauses or days — it only does a monotonic
subtraction off a server-supplied absolute instant, re-synced each poll. When
the exam pauses, the next poll returns a later `debutPrevu` and `enPause=true`;
the client freezes immediately on `enPause` and the corrected instant lands on
the following poll. The reconciliation logic added in §0 is reused verbatim to
produce `debutPrevu` — the warning and the live status share one code path, so
they cannot disagree.

### 3. Push vs poll: **keep polling**, with adaptive cadence + local countdown

Reject SSE/WebSocket for v1:

- **ADR-0002 mandates offline-first scoring.** A push channel cannot be relied
  on against a tablet that may be offline mid-exam; correctness must not depend
  on a live socket. The warning must still fire from the last-known `debutPrevu`
  even if the next poll is late or the device is briefly offline — a
  locally-rendered countdown does exactly that; a push-only design does not.
- **The transport already exists.** The mobile already polls the dashboard; we
  add fields, not a new channel. Smallest blast radius.
- **The UI is local anyway.** A per-second countdown and the warning
  (banner + sound + vibration) render on-device off the synced absolute instant;
  per-second network traffic would be wasteful and *less* robust.

**Adaptive cadence:** poll slowly when the next passage is far
(e.g. every 30–60 s), and tighten to ~10–15 s once `secondsUntilNext` enters,
say, `2 × avertissementLeadSec` — so a pause toggle near a transition is picked
up quickly while idle stations stay cheap. The warning itself is **never gated
on a fresh poll**: it fires off the last `debutPrevu` the client holds.

### Scope of "the warning" (v1)

The only actor with a device at the station is the **évaluateur** (Flutter).
v1 warns the évaluateur ("prochain passage dans Xs — préparez la station"),
who manages the student transition. A **student-/room-facing display** (a salle
screen counting down) is **deferred** — there is no student device and no salle
entity today (ADR-0011 §Deferred notes salles are still absent).

## Rationale

1. **Correctness is load-bearing and fragile.** A warning that ignores pause or
   multi-day fires on the wrong student — worse than no warning. Forcing the
   dashboard-clock reconciliation (§0) first turns a latent bug into a fixed
   precondition, and the warning inherits a clock that is already correct.
2. **One reconciliation site.** Combining the four clocks **once on the server**
   into `debutPrevu` means the client cannot drift from the live board; both
   read the same instant. This is the entire point of "one authoritative clock."
3. **Offline-safe by construction.** Absolute instant + local countdown honours
   ADR-0002; the warning survives a missed poll. Push would couple correctness
   to connectivity.
4. **Backwards-safe and incremental.** Both durations default to 0:
   `battement=0` regenerates identical schedules and leaves existing
   `debutCreneau` rows valid; `lead=0` means no warning. A faculty opts in per
   exam. No backfill.
5. **Minimal new surface.** Two config fields + a Flyway migration + three extra
   fields on an existing poll response + the §0 clock fix. No new entity, no new
   transport, no scheduler.

## Consequences

- **§0 is a real, separately-valuable fix.** Even before the warning,
  `EvaluateurDashboardService` will correctly show A_VENIR/EN_COURS/TERMINEE
  through pauses. This also retires the hardcoded `DUREE_STATION_MIN = 15` /
  `GRACE_PERIOD_MIN` constants in favour of exam config + reconciled time.
- **The dashboard response envelope gains `serverNow`** (and per-rotation
  `debutPrevu`, `avertissementLeadSec`, `enPause`). `SessionResponse` currently
  ships `heureDebut`/`heureFin` as `HH:mm` strings (`SessionResponse.java`);
  `debutPrevu` should be a full absolute timestamp (date+time), since multi-day
  and pause can move a passage off "today". Flutter models must tolerate the new
  fields (additive; old clients ignore them).
- **Create/edit exam form (web)** gains two inputs (transition minutes, warning
  lead seconds), with validation that `slot ≥ 1` and that the buffered schedule
  still fits the day (ties into ADR-0011's `lotsParJour ≥ 1`). This overlaps the
  gap #5 planning tab, which should *display* the buffer in the timetable.
- **Convocations / planning** (gap #5/#6) now show realistic times because the
  schedule includes transitions — a side benefit for student arrival windows.
- **Generation regenerates with gaps** once `battement > 0`; any consumer that
  assumed contiguous `debutCreneau` spacing (none found today) must not.

## Migration / contract impact

- **exam-service:** Flyway **V6** — `examens.temps_battement_min INTEGER NOT
  NULL DEFAULT 0`, `examens.avertissement_lead_sec INTEGER NOT NULL DEFAULT 0`.
  `Examen` entity, `ExamenRequest`/`ExamenResponse`, `ExamGenerationView` gain
  the fields. (Renumber vs ADR-0011's `heure_fin` per §1 note.)
- **scoring-service:** no schema change — durations arrive via
  `ExamGenerationView`/`ExamServiceClient`. `RotationGenerationService` uses the
  buffered `slot`. `EvaluateurDashboardService` reworked to reconciled effective
  time (§0) and to emit `debutPrevu`/`serverNow`/`enPause`/`avertissementLeadSec`.
- **frontend-web:** create/edit form inputs + validation; planning tab shows the
  buffer (gap #5).
- **frontend-mobile (Flutter, Feten's area):** consume `debutPrevu` + `serverNow`
  + `enPause`; local countdown + warning (banner/sound/vibration); adaptive poll
  cadence. **Cross-actor coordination required** — this is the one part outside
  the backend/web owner's scope and must be specced with Feten before build.

## Deferred / out of scope

- **Student-/room-facing countdown display** — no student device, no salle
  entity (ADR-0011 §Deferred). Évaluateur-only in v1.
- **Per-station independent buffers** — one `temps_battement_min` for the whole
  exam in v1; per-station changeover times (some stations reset slower) are a
  follow-up.
- **Server push (SSE/WebSocket)** — reconsider only if a future actor needs
  sub-poll-latency *and* is reliably online; offline-first rules it out for the
  évaluateur.
- **Warning acknowledgement / audit** — recording that the évaluateur saw/ack'd
  the warning. Not needed for the prof's request.

## Alternatives considered

- **Add an `end`/`buffer` field on `Rotation` and store gaps per row.** Rejected:
  duplicates the derivable schedule (`debutCreneau` + `slot` already encode it),
  needs a backfill, and drifts from `Examen` config. The buffer is an exam-level
  policy, not per-rotation data.
- **Let the mobile reconstruct effective time + day from raw fields.** Rejected:
  it would re-implement ADR-0009/0010/0011 on an offline client in Dart — four
  clocks, three of which the dashboard ignores today. Maximum error surface,
  exactly the "no room for mistakes" failure mode. Reconcile once on the server.
- **SSE/WebSocket push of "next passage in Xs".** Rejected for v1 (ADR-0002
  offline-first; correctness must not depend on a live socket; existing
  transport is poll).
- **Compute the warning purely on the server and push a boolean "warn now".**
  Rejected: needs sub-second push to be smooth, dies the moment the device is
  offline, and gives a janky UI. Absolute instant + local countdown is smoother
  and offline-safe.
- **Do nothing / document a manual "call out the next group" habit.** Rejected:
  the prof asked for a built-in transition + warning; a verbal workaround is the
  status quo they flagged as inconvenient.
```
