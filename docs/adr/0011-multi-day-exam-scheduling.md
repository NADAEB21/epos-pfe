# ADR 0011: Multi-day exam scheduling (lot distribution across days)

- **Date:** 2026-06-14 (proposed)
- **Status:** ⚠️ **PARTIALLY SUPERSEDED — read the reconciliation below before implementing
  anything from this ADR.** §1 (distribute lots across days) is **shipped**. §2 (revise the
  effective-time model to per-day) is **void** — ADR-0014 removed the clock it repairs.
- **Deciders:** Nada (lead architect).
- **Related:** ADR 0009 (pause/resume execution model), ADR 0010 (launch instant +
  zoned time), **ADR-0014 / 0014-A / 0014-B (which supersede §2)**, `RotationGenerationService`,
  exam-service `Examen`, scoring-service `Lot`, the Suivi en direct board (`suivi.component.ts`).

---

## ⚠️ Reconciliation with ADR-0014 — added 2026-07-31

This ADR is dated **2026-06-14**. **ADR-0014 is dated 2026-07-15**, a month later, and this
document contained **zero references to it** until this note. Re-verified on Nada's instruction
(« one wrong ADR could set us back to old models »), and the concern was justified — this was the
one live trap in the corpus.

### What is still valid — §1

**Distributing lots across days is correct and remains the decision.** It shipped:
`Lot.jour` (`LocalDate`, `Lot.java:31`) + `PATCH /api/lots/{id}/jour` (#147). Multi-day is a
**stored plan**, not clock arithmetic — consistent with ADR-0014-A.

⚠️ Naming drift: this ADR says `Lot.date_jour`; the shipped field is **`jour`**.

### What is void — §2 « Revise the effective-time model to per-day »

That section, and every clock passage supporting it, rests on a premise ADR-0014 **deleted**:
that the board derives a station's *state* from elapsed time. It no longer does. State comes from
**progress** — the évaluateur advances a passage (ADR-0014), the responsable closes a wave
(ADR-0014-B). Time is an *indication*, never a state.

So the overnight-gap problem this ADR treats as its « core reason to be an ADR » **is moot**: there
is no continuous clock left to break. Per the standing rule *an absurd duration means DELETE the
measurement, never smarter arithmetic* (#243, #252), the answer to "16 h elapsed on day 2" is that
the board must not display elapsed exam time at all — not that it needs a per-day origin.

⛔ **Do not implement §2.** Building a per-day clock origin, a per-day « temps effectif écoulé », or
a day-scoped ADR-0010 launch shift would **reinstate clock-as-state** — the exact model
ADR-0014 was written to retire.

### Also void: the Status line's « Track A »

The original status announced « Track A (**end-exam + overtime**) ships first ». Overtime is a
clock-derived notion; ADR-0016 replaced declared end-of-exam with **derived closure**. Ignore it.

### What actually remains of #147

The **planning UI** (`planning` route is still a stub) so the responsable can *see and set* which
lot runs on which day, and the convocation already derives « Jour 2 · 09:00 » server-side (#227).
Data and endpoint exist; the screen does not. Nothing about clocks.

---

## Context

A practical exam (OSCE) processes students in **lots** (waves). Today every wave is
laid **back-to-back on a single day** from one origin:

```
waveStart(m) = examStart + (m − 1) · K · dureeStationMin          // K = #stations
```

(`RotationGenerationService:133`, anchored on `launched_at ?? dateExamen+heureDebut`
per ADR-0010.) There is **no day dimension**: `Lot` carries only
`examenId, evaluateurId, numeroLot, tailleLot, statut` — no date, no start, no
duration. `Examen` has a single `dateExamen` + `heureDebut` and `dureeStationMin`
(the only duration; **no overall exam duration**).

With a realistic cohort (e.g. 120 students, 4 stations, 15 min/station ⇒ 8 students
per wave ⇒ 15 waves ⇒ 15 h) the current model piles every wave onto one date and the
schedule runs past midnight — physically impossible. The faculty genuinely runs
large exams **across two or more days**. So lots must be **distributed across days**.

### The decision is gated by a clock subtlety, not just data

The live board derives effective time as a **single continuous clock**
(ADR-0009/0010):

```
effectiveNow = launched_at + (wall_now − launched_at) − total_pause_sec
```

For a multi-day exam the **overnight gap between day 1's end and day 2's start is
wall-time that is not exam time** (~16 h). A continuous clock would make day 2 open
showing "16 h elapsed, everything terminé". So multi-day is not merely "spread the
waves" — it **revises the effective-time model**. That is the core reason this is an
ADR and not a patch.

## Decision

### 1. Distribute lots across days (derived OSCE timing kept)

Lot duration stays **derived** — a lot lasts `K · dureeStationMin` (no fixed per-lot
duration field; decided 2026-06-14). Add a **daily working window** so the generator
knows how many waves fit in a day:

- New `Examen.heure_fin` (`LocalTime`, the daily end-of-work time; `heureDebut` is
  the daily start, reused for every day).
- `lotsParJour = floor((heureFin − heureDebut) / (K · dureeStationMin))` — validate
  `≥ 1` at CONFIGURE (else: "add stations / widen the day / shorten stations").
- For lot *m* (1-based): `day = (m − 1) / lotsParJour`, `slot = (m − 1) % lotsParJour`.
- `waveStart = (dateExamen + day days) at heureDebut + slot · K · dureeStationMin`.

Persist the computed `Lot.date_jour` (`LocalDate`) at répartition so the board and
the convocation emails can show "Lot 3 · Jour 2 · 09:00" **before** exam day.

### 2. ⛔ Revise the effective-time model to per-day — **VOID, see the reconciliation at the top of this file. Do not implement.**

Each wave's `Rotation.debutCreneau` is already an **absolute** datetime; once
generation stamps it on the correct day, the board can compare **wall-clock now**
to `debutCreneau` directly for per-slot live state — no continuous offset from a
single `launched_at`.

- The board's clock origin becomes **the active day's** start, not the global
  `launched_at`. "Temps effectif écoulé" is **per-day** (or shown per active wave),
  never spanning the overnight gap.
- The ADR-0010 late-launch shift applies **only within the launching day**; day *d+1*
  starts at its planned `heureDebut` regardless of how day *d* ran. (Open sub-question:
  do we record a per-day `launched_at`? See Deferred.)
- Pause/resume (ADR-0009) stays **exam-level and same-day**; the overnight gap is a
  scheduled boundary, not a pause, and must **not** fold into `total_pause_sec`.

### 3. Capacity / "Terminer" interplay

The Track-A scheduled end (`lastSlotEndMs`) already takes the max over all rotations,
so it naturally becomes the **last day's** last créneau once generation is multi-day —
Track A composes without change. Per-day "all waves of today done" can later flip a
day-complete indicator (not required for v1).

## Migration / contract impact

- exam-service: Flyway `V6` `examens.heure_fin` (nullable; null ⇒ single-day legacy
  behaviour — fully backwards-safe). `ExamenResponse` + `ExamenRequest` gain
  `heureFin`; create/edit form adds the daily-end input + the `lotsParJour ≥ 1`
  validation.
- scoring-service: Flyway adds `lot.date_jour`; `RotationGenerationService` packs by
  day; `ExamGenerationView` carries `heureFin`.
- frontend: create/edit form (daily window), Suivi board (per-day clock + day
  switcher / "Jour N" labelling), Lots tab (day grouping).

## Rationale

1. **Correctness.** A schedule that runs past midnight is wrong on its face; the live
   board built on a continuous clock is wrong on day 2. Both are load-bearing for the
   exam-day story.
2. **Smallest honest change.** Derived lot timing means the only new *input* is one
   daily-end time; days fall out of arithmetic. No per-lot duration UI.
3. **Backwards-safe.** `heure_fin` null ⇒ today's single-day behaviour; existing
   exams keep working with no backfill.

## Consequences

- The Suivi board stops being a single continuous timeline; it becomes day-scoped.
  This is the largest FE change and must be designed with the per-day clock, not
  retrofitted onto the continuous one.
- "Effective elapsed since launch" loses meaning across days — replaced by per-day
  elapsed. Any consumer of a single global elapsed must move to per-day.

## Deferred / out of scope

- **Per-day `launched_at`.** v1 assumes each day starts at planned `heureDebut`. If
  real per-day start drift matters, capture a per-day launch instant (extends
  ADR-0010's single launch instant to a per-day vector). Noted, not built.
- **Per-day / per-lot évaluateur reassignment** across days (an évaluateur may differ
  day 1 vs day 2). Current model binds one évaluateur per station for the whole exam.
- **Rooms / salles per day** (still absent; tracked separately).
- **Multi-timezone** — inherits ADR-0010's single-zone assumption.

## Alternatives considered

- **Treat the overnight gap as an auto-pause.** Rejected: explodes `total_pause_sec`,
  conflates a scheduled boundary with a responsable action, and still leaves the board
  on a fragile continuous clock.
- **Fixed per-lot duration ("1 hour per lot").** Rejected 2026-06-14: the exam is
  station-rotation based; a lot's real length is `K · dureeStationMin`. A fixed
  duration would double-model time and drift from the actual circuit.
- **Cap exam size to one day.** Rejected: the faculty's real cohorts exceed a day.
