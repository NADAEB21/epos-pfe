# ADR 0014-A: Lot autonomy — PLAN vs PACE, two-level advance, multi-day as stored plan

- **Date:** 2026-07-16
- **Status:** Accepted (addendum to ADR-0014; extends the constitution from passages up to lots).
- **Deciders:** Nada (lead architect).
- **Related:** **ADR-0014** (évaluateur-paced station lifecycle — this addendum extends it), #206
  (reframe epic) and #207 (scoring child — gains the "Groupe suivant" repair proven here), #147 /
  **ADR-0011** (multi-day — this addendum gives it its real, minimal data model), ADR-0009
  (pause/resume), ADR-0010 (launch timestamp + zoned time), ADR-0012 (inter-créneau buffer).
  Supersedes the **pause-as-multi-day** hack for the *status* path (`Examen.java:84-88`).
  Code in scope: `RotationGenerationService`, `EvaluateurDashboardService`, `Examen`, `Lot`,
  `Etudiant`, `SessionResponse`, `lancement.component.ts`, `exam-status.ts`, `convocations.component.ts`,
  `grading_bloc.dart`, `home_screen.dart`.

## Context

ADR-0014 ratified "évaluateur-paced, never clock-driven" **at the passage level**. During its
implementation Nada raised a correction it did not cover: **a lot is not a passage.** A teacher/
évaluateur must keep as much autonomy at the **lot** boundary as at the passage boundary —
a break **between lots**, and lots examined **across different days** (multi-day) — all **without
rushing and without losing progress**. The earlier framing "lots run in sequence" was wrong.

### Two things the code conflates today (verified 2026-07-16, `file:line`)

1. **The generator bakes a same-day, back-to-back lot timeline into the schedule.** For lot `m`,
   `waveStart = examStart + (m−1)·K·slot` (`RotationGenerationService.java:146-147`), and
   `examStart` is always launch-day (`:142-144`). Every rotation's `debutCreneau` is stamped from
   that offset (`:180`, `:190`). This is the ADR-0014 clock-as-control disease, one level up: the
   *plan* of which day/time a lot runs is **computed from a formula**, not stored as a decision.
2. **Multi-day is faked, not modelled.** exam-service has a single `dateExamen`
   (`Examen.java:39`), **no venue field anywhere** (verified `Examen.java`, `Station.java`), and
   `Lot` has **no day field** (`Lot.java:20-27`). "Multi-day" exists only as a comment justifying
   the pause-subtraction machinery — treat the overnight gap as accumulated `totalPauseSec` so the
   fake back-to-back timeline stays aligned (`Examen.java:84-88`). That is clock arithmetic, not a
   plan.

### What the OSCE actually is (FPHM planning documents, `docs/data-samples/`)

- An **EPOS is one matière = one exam**, each with **one date + one venue**, set **in advance** by
  the administration (Directrice des stages). Different matières fall on different days.
- The **venue is the matière's own lab** — "TP Biochimie", "TP Parasitologie", "Labo Immuno CHU
  Sousse", "PAQ Amphi B", or "différents centres" (clinical). It is **intrinsic and fixed**, never
  allocated. There is **no room-management task**; the venue is a display string on the convocation.
- The **roster is published separately/later** as `N°, Nom, Prénom, Lieu de stage, Salle`. "Lieu de
  stage" is internship metadata about the student, **not** the exam venue. The uploaded roster
  carries **no email** (`Etudiant.java:15-27`, `ImportEtudiantRequest.java:10-14`).
- Cohorts are small (3–34) and **mostly single-day**; multi-day is a **rare** case (large cohort),
  but is **required** (supervisor-mandated).

### The gap this addendum also repairs (proven live)

Because the intra-lot advance never existed, the évaluateur app can only ever reach **group 1** of
a lot at a station: `getLotDetail` does `.findFirst()` (`EvaluateurDashboardService.java:147-155`).
Live on the running stack (exam 2, lot 28, eval 3 @ station 5): the station has 4 rotations / 4
groups / **8 student-passages**, and `GET /api/evaluateur/stations/5/lots/1` returned **2 students**
(group 1). The remaining 6 have no path in the app. This is the concrete proof that "no clock
control" must also mean "the flow actually lets the évaluateur walk **all K groups**." The repair
lives in **#207** (see that issue).

## The doctrine extension (Nada's model, ratified 2026-07-16)

ADR-0014's seven points hold. This addendum adds the **PLAN vs PACE** separation and lifts autonomy
from passages up to lots:

- **PLAN** — *which day and which venue* a lot runs in. Decided **in advance**, by the responsable,
  **stored**, and **emailed** to students. This is a human commitment (a student assigned to day 2
  shows up day 2), not the clock controlling anything. Legitimate structure.
- **PACE** — *when, within that day*, each group and each lot actually advances. **Never
  clock-gated.** Emergent from explicit évaluateur action. A teacher who wants a rest simply does
  not advance; nothing decays because state is stored, not computed from `now`.

The current code fails on both axes at once: it **fakes PLAN** (computes a same-day schedule instead
of storing the responsable's real day/venue) **and** uses that fake schedule as **PACE control**.

## Decision

1. **Two explicit advance levels, neither driven by the clock.**
   - **Groupe suivant** (*intra-lot*) — the évaluateur walks the K groups/créneaux at their station.
     Built in **#207**; `getLotDetail` returns the current `EN_COURS` group, not `findFirst`.
   - ~~**Lot suivant** (*inter-lot*) — a new batch begins. Already exists in mobile
     (`GradingLotSuivantDemande`); kept, and never conflated with Groupe suivant.~~
     ⛔ **SUPERSEDED by ADR-0014-B (2026-07-22).** Wrong on both halves. It **does not exist in
     mobile** — `grading_screen.dart` renders « Groupe suivant » and fires
     `GradingGroupeSuivantDemande`; only a stale private method name survived. And it assigns the
     inter-lot advance to the **évaluateur**, which the wave model rules out: a lot is served by
     **all** évaluateurs at once, so moving it on moves the whole cohort and every colleague's
     station. That advance belongs to the **responsable**, gated on every rotation of the current
     lot being `TERMINE`. See **ADR-0014-B**. The first bullet (« Groupe suivant », évaluateur-owned)
     is unaffected and stands.

2. **Lot status is DERIVED from rotation progress, never from a stored time offset.** A lot is
   `TERMINE` only when **every** rotation of the lot is `TERMINE`; it is "started" when **any**
   rotation is no longer `EN_ATTENTE`. `Rotation.statut` = per-`(station, évaluateur)` progress;
   `Lot.statut` = a consequence of the whole batch. **Break-between-lots and multi-day are
   therefore free**: an un-advanced lot's rotations stay `EN_ATTENTE` on whatever day they run, and
   nothing decays. (The `validerLot` cascade that violated this — force-writing every rotation
   `TERMINE` — was **neutralized in #211**, and the per-station note clobber in **#212**; both shipped
   in **PR #229**, verified live on lot 28. `validerLot` now DERIVES `Lot.statut` from stored rotation
   states and writes no rotation status. The remaining #207 piece is the `EN_COURS` progress model.)

3. **PLAN is stored, not computed.** The `waveIndex·K·slot` same-day anchor
   (`RotationGenerationService.java:147`) stops being a **control**. `debutCreneau` may survive only
   as a **faint informational hint** whose date comes from the lot's assigned day — it must never
   again drive **status** (ADR-0014 §3) or **visibility** (see the launch/planning gate below).

4. **No venue field. Out of scope.** An earlier draft proposed a `lieu`/`salle` String on `Examen`.
   **Supervisor decision (2026-07-16, via Nada): the convocation email carries the lot + the exam
   date ONLY — never the venue** (see §6). With the convocation no longer needing it, venue has **no
   consumer**, so it is **not introduced**. The `SessionResponse.salle` field that ships `null` today
   (`SessionResponse.java:58`, never set in `buildSessions`) simply stays unused — a cosmetic null,
   not a gap worth a schema change. If a genuine display need for venue ever appears, it is a new,
   separate decision — not part of this ADR.

5. **Multi-day = an optional `jour` (LocalDate) on `Lot`, defaulting to `Examen.dateExamen`.** A lot
   diverges from the exam date only when the responsable splits a cohort across days. The launch
   day-gate — today `dateExamen === todayStr` (`lancement.component.ts:275`, `exam-status.ts:34-36`)
   — must accept **"today is one of the exam's lot-days,"** not the single exam date. The
   **pause-as-multi-day** subtraction (`Examen.java:84-88`) is retired from the *status* path; pause
   remains only ADR-0009 event-level oversight.

6. **Convocations/email is a separate child — email = LOT + exam DATE only.** Supervisor simplification
   (2026-07-16, via Nada): students are told **their lot number + the exam day**, nothing else — **not
   the venue/salle.** So the only data prerequisites are **`Etudiant.email`** (absent today,
   `Etudiant.java:15-27`, `ImportEtudiantRequest.java:10-14`) and the lot's **`jour`** (§5). **No venue
   (§4).** Wire the already-built but data-blocked web convocation screen
   (`convocations.component.ts`) once email + day exist. "Emailed in advance" is the real FPHM
   convocation. **Not part of #207.** Tracked as **#227**.

7. **The intra-lot gap is a REPAIR under #207** (proven live, §Context). Recorded here so the
   doctrine is explicit: évaluateur-paced also means the flow must expose every group, not just the
   first.

## Consequences

- **The work splits into three separable streams.** (a) **#207** — clock→progress + Groupe-suivant
  repair (backend, then #208 web / #209 mobile); unchanged in spirit, now known to be a functional
  repair, not only hygiene. (b) **PLAN data** — optional `jour` on `Lot` + the launch day-gate fix;
  this is the real, minimal content of #147 / ADR-0011 multi-day (no venue). (c) **A new convocation
  child** — `Etudiant.email` + day + the existing web screen. Only (a) and the launch-gate part of (b)
  are mutually prerequisite; (c) is independent.
- **`Lot.statut` becomes a derived consequence, adding no new stored lot lifecycle fields** beyond
  the optional `jour`. "Break between lots" and "teacher wants a rest" need **no** pause entity —
  they are the natural result of never gating on time.
- **The pause-as-multi-day hack is superseded** for status. Multi-day stops being clock arithmetic
  and becomes a stored plan a student is told about in advance.
- **Migration is staged and verified live per slice** (curl the scoring contract, Playwright the web
  board, `flutter run -d chrome` mobile) — same discipline as ADR-0014; one issue to a merged PR
  before the next.
