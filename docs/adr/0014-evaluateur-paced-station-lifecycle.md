# ADR 0014: Évaluateur-paced station lifecycle

- **Date:** 2026-07-15
- **Status:** Accepted (constitution; implementation staged across scoring → web → mobile)
- **Deciders:** Nada (lead architect).
- **Related:** issue #184 (créneaux indicative, not authoritative — this ADR supersedes its
  deferred half), #182 (phantom "Terminée"), #185 (guided exam-day flow), #147 / ADR-0011
  (multi-day), ADR-0009 (pause/resume), ADR-0010 (launch timestamp + zoned time),
  ADR-0012 (inter-créneau buffer + passage warning). Supersedes the clock-as-state parts of
  ADR-0012's execution assumptions. Code touched: `EvaluateurDashboardService`,
  `RotationStatus`, `RotationGenerationService`, `suivi.component.ts`, `grading_bloc.dart`.

## Context

EPOS digitalises an **OSCE** practical exam: student groups rotate through a circuit of
stations, one évaluateur per station. Across sessions 7–12 the same "bug" was fixed **three
times as a symptom** and kept coming back, each fix quietly re-deriving state from the clock:

- #182 — stations showed a phantom "Terminée".
- #184 — the elapsed créneau was relabelled "Dépassement" (web slice shipped, PR #202).
- the mobile status strings drifted the same way.

The frame was never written down as **one** decision, so every fix re-derived the wrong one.
This ADR is that decision, ratified once, so nothing is coded against the clock again.

### What the code actually does today (verified 2026-07-15, `file:line`)

**There is no live progress state at all.** `RotationStatus` is `EN_ATTENTE | EN_COURS |
TERMINE`, but:

- `EN_COURS` is written by **zero** code paths.
- The generator hard-sets `EN_ATTENTE` only — `RotationGenerationService.java:191`.
- The only flip to `TERMINE` is the lot-wide `validerLot` — `EvaluateurDashboardService.java:272`.
- The web model says it in a comment: `models.ts:349-354` — *"PERSISTED value is always
  EN_ATTENTE … nothing on the backend ever flips it … the Suivi screen IGNORES this field and
  computes the live state from the clock instead."*

Because no progress state exists, **every tier fell back to the clock**:

- **scoring** — `resolveSessionStatut` (`EvaluateurDashboardService.java:312-319`) and
  `mapRotationStatutToPlanningStatut` (`:382-386`) derive `A_VENIR / EN_COURS / TERMINEE` from
  `debutCreneau + dureeStationMin + GRACE_PERIOD_MIN (30)` versus an effective-now clock
  (`:329-344`). The schedule, an *indicative plan*, is treated as a control.
- **web** — the entire Suivi board runs off a 1 Hz browser clock: `now = signal(Date.now())`
  (`suivi.component.ts:566`), `setInterval(…,1000)` (`:697`), `effectiveNowMs` (`:624-641`),
  `resolveLaneState` (`:91-107`), `slotState` (`:827-835`), the "fin dans mm:ss" countdown
  (`:900-904`), "Dépassement"/`enRetard` (`:106`, `:834`), overtime bar (`:678-693`).
- **mobile** — the grading countdown is a hard-coded 15-min constant minus
  `DateTime.now() − debutCreneau` (`grading_bloc.dart:262`, `:588-592`), ticked by a client
  `Timer.periodic(1s)` (`:579-586`), with `debutCreneau` fabricated from an `"HH:mm"` string
  onto today's date (`home_screen.dart:79-92`). The "dépassement" badge is the local
  `Duration` sign (`grading_screen.dart:1191-1214`).

**exam-service is already correct** and is NOT the problem: `changerStatut(EN_COURS)`
(`ExamenServiceImpl.java:164-191`) stamps `launched_at`, starts no clock, and drives no
station. The violation is purely scoring/web reinterpreting exam-service's timing **config**
(`dureeStationMin`, `tempsBattementMin`, `avertissementLeadSec`) as authoritative **state**.

### The doctrine (Nada's model, ratified 2026-07-15)

An OSCE exam is **not a timed race**:

1. The schedule (09:00, 09:15…) is an **indicative plan** so people show up — never a control,
   never a cutoff.
2. **Each station moves at its évaluateur's own pace, independently.** No shared master clock.
3. **Passages advance only when the current group is actually done** — a deliberate,
   progress-driven step. **Never an automatic timer flip**, not even for the évaluateur.
4. **Time is never authoritative.** No "fin dans mm:ss", no auto "Terminée", no "Dépassement",
   no countdown that gates anything. Time may appear only as a faint reference, if at all.
5. **Responsable "Lancement" = enable _suivi_ only** — makes the exam visible to monitor.
   Starts no clock, drives no station.
6. **Responsable pause/end = event-level oversight** (a real incident), not pace control.
7. **Smooth & ergonomic for both actors** — the évaluateur just evaluates and moves on when
   ready; the responsable just watches progress. Neither fights a clock.

## Decision

**Station and passage lifecycle state is driven by explicit grading progress and an explicit
évaluateur "advance" action — never derived from the clock. Time is a faint hint, never a
gate. Completion is modelled per `(évaluateur, station)`, never lot-wide.**

Concretely:

1. **Introduce an explicit per-station progress state.** "Which group is currently at this
   station, and which groups are done" becomes stored state, set by the évaluateur's advance
   action — not computed from `now − debutCreneau`. `RotationStatus` gains a real, written
   `EN_COURS` and a per-station notion of "current passage"; the generator's `EN_ATTENTE` is a
   genuine starting state that something advances.

2. **The évaluateur advances passages explicitly.** "This group is done → next passage" is a
   deliberate action (its own endpoint/state transition). No timer ever flips a passage.
   Marking the current group done + advancing is the single progress signal; the exam-day
   flow is built around it (ties into #185).

3. **Retire time-as-source-of-truth in scoring.** `resolveSessionStatut` and
   `mapRotationStatutToPlanningStatut` stop deriving status from `debutCreneau + duree +
   GRACE`; status is read from the explicit progress state. `GRACE_PERIOD_MIN` and the
   effective-now status machinery are removed from the *status* path (effective-now may still
   serve a purely informational elapsed label, if kept at all).

4. **Completion is per-station, not lot-wide.** `validerLot`'s current behaviour — one
   évaluateur flipping **every** rotation of **every** group in the lot to `TERMINE`
   (`EvaluateurDashboardService.java:265-277`), across other stations and other évaluateurs —
   is wrong under this doctrine and is a live defect (tracked separately, HIGH). Completion is
   recorded for the calling évaluateur's own station only.

5. **Web Suivi shows progress, not a clock.** The board shows **X/Y groups done + the current
   group per station**, sourced from the explicit progress state. Countdowns, "fin dans",
   "Dépassement"/`enRetard`, and the overtime gate are removed; any time shown is a faint,
   non-gating reference. This revisits and folds in #184's web slice (PR #202).

6. **Mobile shows progress, not a countdown.** The grading screen's status comes from the
   explicit progress state; "next passage" is the évaluateur's explicit action. The clock-
   derived countdown/dépassement badge is removed. This absorbs the deferred mobile half of
   #184 (referenced, not re-filed).

7. **Responsable Lancement / pause / end keep their doctrine meaning.** Lancement = make the
   exam visible to _suivi_ and available to évaluateurs (already true in exam-service);
   pause/end = event-level oversight. No new clock is introduced by any of these.

## Consequences

- **One epic, one child per tier.** A reframe epic tracks four children — scoring (explicit
  progress state + advance, retire GRACE/`resolveSessionStatut`), web (progress board), mobile
  (explicit advance, progress status), exam (assert Lancement=suivi-only + oversight). Each
  links this ADR.
- **#184 is superseded, not closed.** Its deferred half (mobile) and its web "Dépassement"
  slice are both re-derived-from-the-clock and are folded into the epic; #184 stays open as-is
  and is referenced.
- **The clock stops being load-bearing.** Once explicit progress state exists, dev-host zone
  skew (ADR-0010), pause-time accounting for *status* (ADR-0009/0012), and the 30-minute grace
  window stop being able to hide or fake a station's state. They remain relevant only to any
  purely-informational time hint that survives.
- **Migration is staged and verified live per slice** (curl the scoring contract, Playwright
  the web board, `flutter run -d chrome` the mobile screen) — backend first, then web, then
  mobile; one issue to a merged-to-develop PR before the next.
- **Concurrency/integrity defects surfaced by the same audit are tracked as their own issues**
  (validerLot cascade HIGH, participation-row clobber HIGH, dashboard ownership gap, missing
  `(participation, station)` uniqueness) and cross-link this epic; fixing the model here is
  what makes the per-station-completion fix coherent rather than a patch.
