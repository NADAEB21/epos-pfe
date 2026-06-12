# ADR 0010: Capture launch instant + adopt a zoned-time policy

- **Date:** 2026-06-12
- **Status:** Proposed
- **Deciders:** Nada (lead architect). Surfaced while building #139 (Suivi en
  direct, PR #143); awaiting a decision before the live board is relied on in a
  real (multi-timezone / off-schedule) run.
- **Related:** ADR 0009 (exam pause/resume execution model), issue #139,
  PR #143 (`feat/suivi-en-direct`), `RotationGenerationService`,
  exam-service `Examen` entity + `Examen.pause/reprendre`.

## Context

ADR 0009 made the live timeline a function of **effective exam time**:

```
effectiveElapsed = (now − examStart) − total_pause_sec − (en_pause ? now − paused_at : 0)
```

Building the Suivi screen (PR #143) against this exposed two latent assumptions
in the underlying data that are correct *only* under conditions we don't enforce.

### Gap 1 — there is no record of when an exam actually started

`examStart` is reconstructed from the **planned** `dateExamen + heureDebut`.
`RotationGenerationService` anchors every `Rotation.debutCreneau` to that same
planned start. But the moment the responsable actually flips the exam to
`EN_COURS` (Lancement → `changerStatut`) is **never recorded**. So:

- If the exam launches **late** (the realistic case — OSCE stations rarely start
  exactly at the scheduled minute), the board reads "X minutes already elapsed"
  the instant it opens, and the first créneaux can show as already `terminé`
  before anyone has been examined.
- The only operational mitigation today is "launch precisely at `heureDebut`, or
  pause immediately and resume when truly ready" — a workaround the UI shouldn't
  depend on.

### Gap 2 — timestamps are zone-less `LocalDateTime`, mixing two clocks

The schema uses naive `LocalDateTime` / `TIMESTAMP WITHOUT TIME ZONE` everywhere,
and it mixes two *different* clock domains in one comparison:

- `heureDebut` / `dateExamen` / `Rotation.debutCreneau` — **wall-clock the user
  entered** (the exam's local time, e.g. Africa/Tunis).
- `Examen.paused_at` — **the server's wall clock**, stamped via
  `LocalDateTime.now(clock)` (ADR 0009 / commit e15deb5), i.e. whatever timezone
  the server JVM runs in.

When the server runs UTC and the exam is local (the standard cloud-deploy case,
and exactly the dev stack: UTC containers, UTC+2 host), `paused_at` lands hours
away from the schedule it is being compared against. PR #143 hit this directly:
pausing made the live board jump to *"à venir"* because `paused_at` parsed hours
before the first créneau. The FE worked around it by capturing the pause-freeze
**on the client** (timezone-proof for a live session), but the underlying data
defect remains and still corrupts the *reload-while-paused* reconstruction.

## Decision

Two backend changes; small, independent, both shippable as one exam-service PR.

### 1. Record the actual launch instant

Add `Examen.launched_at` (nullable `TIMESTAMPTZ` — see policy below), set **once**
when `changerStatut` transitions the exam into `EN_COURS`, never overwritten.

Effective time anchors to the **actual** launch, falling back to the planned
start only when `launched_at` is null (legacy / not-yet-launched rows):

```
examStart = launched_at ?? (dateExamen + heureDebut)
```

`RotationGenerationService` keeps generating `debutCreneau` as **relative
offsets** from `examStart`; since generation already happens *after* launch
(`EN_COURS` is a precondition), it can anchor on `launched_at` directly, so the
plan and the live clock share one origin.

### 2. Adopt a single zoned-time policy

Stop comparing values from two clock domains as if they were the same. Two
acceptable shapes — **pick one and apply it consistently** (recommendation:
option A, smallest change, matches "exams are local events"):

- **Option A — pin the application timezone.** Configure the JVM / Spring to the
  exam region's zone (`TZ=Africa/Tunis`, or `spring.jackson.time-zone` +
  `app.timezone`) so `LocalDateTime.now(clock)` and the user-entered wall-clock
  agree. Keep `LocalDateTime` in the schema. Cheapest; correct as long as all
  exams are in one zone (true for this faculty).
- **Option B — make instants explicit.** Store machine-stamped moments
  (`launched_at`, `paused_at`) as `Instant` / `TIMESTAMPTZ` (UTC), keep the
  *planned* fields (`dateExamen`, `heureDebut`) as local wall-clock, and have the
  service combine them against an explicit exam zone when computing effective
  time. More robust (survives a future multi-site / DST scenario) at the cost of
  a conversion layer.

Either way, the invariant to restore is: **every timestamp compared in the
effective-time formula lives in one declared zone.**

## Rationale

1. **Correctness of the headline feature.** The live Suivi board is the
   jury-facing centerpiece of the exam-day story; a board that mis-reads the
   current créneau the moment it opens (late launch) or after a pause (zone skew)
   undermines exactly the supervisor scenario ADR 0009 set out to support.
2. **Small, contained, additive.** `launched_at` is one nullable column + one
   assignment in the existing `changerStatut` path (Flyway `V5`). The zone policy
   (option A) is configuration, not a schema migration.
3. **Removes a class of bug, not one instance.** PR #143's client-side freeze
   patches the *live-session* symptom; this ADR fixes the *data*, so any
   consumer (a reloaded Suivi tab, a future mobile read, BI/exports) gets a
   coherent clock for free.
4. **Backwards-safe.** `launched_at` is nullable with a planned-start fallback;
   existing `EN_COURS`/`TERMINE` rows keep working, no backfill required.

## Consequences

- The Suivi FE (PR #143) can drop the `pausedAt` reconstruction fallback in
  favour of trusting a now-coherent server clock; the client-captured freeze can
  stay as a belt-and-braces optimisation for the live session.
- `examStart` semantics shift from "planned start" to "actual start when known".
  Any other consumer of the planned start (e.g. the Lots tab's pre-exam arrival
  windows, which legitimately want the *planned* time) must keep reading
  `heureDebut`/`dateExamen` explicitly, not the new launch-aware origin.
- A small contract note for the FE: `launched_at` joins the `ExamenResponse` DTO
  (nullable); models must tolerate its absence on legacy rows.

## Deferred / out of scope

- **Per-lot / per-day actual-start timestamps.** Still deferred (ADR 0009's
  alternative): this ADR captures a single exam-level launch instant, not
  per-wave start drift. The per-lot path remains the upgrade if real per-lot
  drift beyond pauses ever needs capturing.
- **Multi-timezone exams.** Option A assumes one faculty zone. If the platform
  ever runs exams across zones, option B (or a per-exam `zone` column) becomes
  necessary — noted, not built.

## Alternatives considered

- **Do nothing; keep the FE client-side freeze.** Rejected: it only covers the
  live session, leaves reload-while-paused and every other consumer reading a
  skewed clock, and bakes a workaround in place of a data fix.
- **Anchor purely on `heureDebut` and forbid late launches via the UI.**
  Rejected: pushes an operational constraint (start on the exact minute) onto the
  responsable to compensate for missing data; brittle and user-hostile.
