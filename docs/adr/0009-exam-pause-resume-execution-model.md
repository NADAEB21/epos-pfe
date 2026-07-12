# ADR 0009: Exam pause/resume — execution-time model

- **Date:** 2026-06-12
- **Status:** Accepted
- **Deciders:** Nada (lead architect), with supervisor direction (2026-06-12).
- **Related:** the parked **A1 per-lot scheduling** question, issue #139
  (real-time Suivi), the two-phase lot/rotation workflow
  (`RotationGenerationService`), ADR 0002 (per-actor offline contract),
  ADR 0007 (évaluateur global scoping).

## Context

The OSCE workflow as built assumes the exam runs as **one unbroken block**:
`RotationGenerationService` stamps every `Rotation.debutCreneau` deterministically
as back-to-back waves — `examStart + (numeroLot−1)·K·dureeStationMin`, then
`+ t·dureeStationMin` per créneau, anchored to the exam's single `heureDebut` on
its single `dateExamen`. Nothing records when a lot *actually* started, and the
`StatutExamen` machine is strictly linear
(`BROUILLON → CONFIGURE → EN_COURS → TERMINE → ARCHIVE`) with **no pause state**.

The supervisor described the real operational reality (2026-06-12):

- **300+ students**, partitioned into lots of a configurable size (~16).
- An exam can span **multiple days** when the cohort is large.
- Within a day, evaluators take **breaks** between lots (lunch, toilet, etc.)
  and resume afterwards.
- An exam can be **paused** at any moment for unforeseen reasons (a student
  faints; an evaluator or the responsable must step out).
- After any interruption, **all progress/data must be conserved**.

The stated core question: *can an exam be paused and resumed without losing
data or progress?*

This invalidates the "fixed contiguous timeline" assumption. A live Suivi screen
that counts down from `debutCreneau` against wall-clock time would drift the
instant anyone takes a break — precisely the scenario the supervisor led with.

## Decision

Model interruption as an **orthogonal pause state on the exam**, tracked in
**effective exam time** (wall-clock minus accumulated paused time). The exam's
`statut` is **not** touched — pause is not a lifecycle transition; a paused exam
is still `EN_COURS`.

**exam-service `Examen` gains three fields** (Flyway `V4`):

- `en_pause BOOLEAN` (default `false`) — currently paused?
- `paused_at TIMESTAMP` (nullable) — when the current pause began; `null` unless
  `en_pause`.
- `total_pause_sec INTEGER` (default `0`) — accumulated seconds across all
  *completed* pause intervals.

**Two endpoints**, valid only while `EN_COURS`, matière-scoped like
`changerStatut`:

- `PATCH /api/examens/{id}/pause` — requires not already paused; sets
  `en_pause = true`, `paused_at = now`.
- `PATCH /api/examens/{id}/reprendre` — requires paused; adds `now − paused_at`
  to `total_pause_sec`, clears `paused_at`, sets `en_pause = false`.

**Effective time** is computed by the consumer (the Suivi screen), not stored:

```
effectiveElapsed = (now − examStart)
                 − total_pause_sec
                 − (en_pause ? (now − paused_at) : 0)
```

Each planned `debutCreneau` offset is compared against `effectiveElapsed`, so the
pre-computed back-to-back schedule **stays valid through any number of
interruptions** without per-lot timestamps.

## Rationale

1. **One mechanism covers breaks, pauses, and multi-day.**
   - A **break** is a pause→resume.
   - A **multi-day** gap is just a long (overnight) pause: because the generated
     plan assumed no gaps and effective-time removes the gap, late lots line up
     in effective time exactly as if they had run contiguously. No multi-date
     schema is needed for *execution*.
2. **Progress is conserved by construction.** Notations are persisted rows in
   `scoring_db`, independent of the clock. Pause governs the timeline and
   availability, never the data — so the supervisor's core question is answered
   yes, structurally, with no extra work.
3. **Keeps the status machine intact.** Pause is orthogonal boolean state, so
   `validerTransitionStatut` and every existing gate (`isModifiable`,
   generation's `EN_COURS` check) are untouched. Smallest blast radius.
4. **Consumer-side effective-time math keeps the backend minimal.** Three fields
   + two endpoints; no new entity, no scheduler, no per-rotation actual-start
   bookkeeping.

## Consequences

- The #139 **Suivi en direct** screen (next deliverable) computes its countdown
  in effective time and reads `en_pause` to freeze the clock + surface a
  "Examen en pause" state, with responsable pause/resume controls.
- **Mobile pause is advisory, not a hard lock** (pushback on the supervisor's
  "freeze the evaluator side" suggestion). ADR 0002 mandates deep offline-first
  scoring; an offline tablet cannot be remotely frozen reliably. The exam's
  pause state is published, and the mobile app should show an "Examen en pause"
  banner + soft-disable *new* scoring **when online** — never promise a
  guaranteed remote kill-switch against an offline client.
- `dateExamen` keeps its meaning as the exam's **start** date; spanning later
  days is handled by execution-time pause, not by storing multiple dates.

## Deferred (still part of A1, not in this ADR's scope)

- **Explicit per-lot / per-day scheduling** — letting the responsable assign a
  lot to a specific day + time so students are *told* which day to attend. The
  pause model handles execution; publishing a multi-day arrival plan to students
  is a separate planning feature.
- **Direct lot-size configuration.** Lot size is currently derived
  (`K stations × nbEtudiantsParStation`, e.g. 4×4 = 16 — already configurable in
  effect). Setting lot size independently of K×capacité is a follow-up.

## Alternatives considered

- **Add a `PAUSE` status to the lifecycle.** Rejected: pause is re-entrant
  (pause→resume→pause…) and returns to the same `EN_COURS`; encoding it as a
  status pollutes the linear machine and every gate that switches on `statut`.
- **Record actual per-lot/per-rotation start timestamps and drive the live view
  purely from them.** Rejected for now as heavier: it duplicates the generated
  plan and needs an explicit "start lot" event. Effective-time accounting reuses
  the existing deterministic plan and needs only exam-level pause state. The
  per-lot-timestamp approach remains the natural upgrade path if real per-lot
  start drift (beyond pauses) ever needs to be captured.
