# ADR 0014-B: Lot advance is responsable-owned — the wave handshake

- **Date:** 2026-07-21
- **Status:** **Accepted** (2026-07-22, Nada — addendum to ADR-0014; **supersedes ADR-0014-A §1,
  second bullet**). Ratified in her own terms: *« un lot, plusieurs évaluateurs — donc ce ne devrait
  pas être à un seul évaluateur de décider de passer au lot suivant »*, which is precisely the
  ownership argument of §Context below.
- **Deciders:** Nada (lead architect).
- **Related:** **ADR-0014** (évaluateur-paced station lifecycle — the constitution), **ADR-0014-A**
  (PLAN vs PACE — this addendum corrects its §1 lot-advance bullet and leaves §2–§7 intact),
  **ADR-0015** (exam-definition snapshot — source of the "no exam→scoring call direction"
  constraint used below), #206 (reframe epic), **#207** (scoring — gains the lot-advance action),
  **#208** (web Suivi — alert + « Lot suivant » control), **#209** (mobile — must NOT gain a
  lot control). Code in scope: `RotationGenerationService`, `LotService`, `LotController`,
  `EvaluateurDashboardService`, `suivi.component.ts`, `grading_screen.dart`.

## Context

ADR-0014-A §1 ratified **two explicit advance levels**. Its first bullet (« Groupe suivant »,
intra-lot, évaluateur-owned) is built and correct. Its second bullet is wrong on both halves:

> **Lot suivant** (*inter-lot*) — a new batch begins. Already exists in mobile
> (`GradingLotSuivantDemande`); kept, and never conflated with Groupe suivant.

1. **It does not exist in mobile.** `grading_screen.dart:645-660` renders a button labelled
   « **Groupe suivant** » whose handler fires `GradingGroupeSuivantDemande`. Only the private
   method name `_confirmerLotSuivant` (`:648`, `:701`) survives from the old naming. Grep over
   `epos_mobile/lib` finds no lot-advance event, endpoint, or call. The behaviour is already
   group-level; the name is a leftover.
2. **It assigns lot-advance to the wrong actor.** Under the model Nada settled 2026-07-20, that is
   a category error — see below.

### What a lot actually is (Nada, settled 2026-07-20)

**A lot is a WAVE of students arriving together at a set hour. ALL évaluateurs serve the same lot
at once**, each at their own station. A lot is spread across the whole circuit simultaneously; it
is **not** one évaluateur's private queue.

That single fact decides ownership. An évaluateur pacing their own station is pacing *their* work
and may do so freely (ADR-0014). An évaluateur advancing the *lot* would be moving **the entire
cohort and every colleague's station** on — so no single évaluateur may hold that action. It
belongs to the **responsable**, who is the only actor with the whole circuit in view.

### The coordination handshake

1. presence taken + exam launched → **lot 1**'s rank-1 group opens on every station;
2. évaluateurs grade, each advancing their own groups (« Groupe suivant »);
3. when **all** évaluateurs have finished the lot → the responsable is **alerted** « Lot N terminé »;
4. the responsable clicks « **Lot suivant** » when everyone is ready → lot N+1 opens → repeat.

### What the code does today (verified 2026-07-21, `file:line`)

- **The generator opens rank 1 for *every* lot it generates.**
  `RotationGenerationService.java:197` —
  `rotation.setStatut(t == 0 ? RotationStatus.EN_COURS : RotationStatus.EN_ATTENTE);`
  inside `generateForLot(lotId)`, with **no guard on any other lot's state**. Generating lot N+1
  while lot N is still running therefore opens a **second concurrent group on the same station**.
  Proven live in session 23 on a purpose-built 2-lot fixture (exam 31): rotations **173** (lot 32)
  and **177** (lot 33) both `EN_COURS` on station 54, same évaluateur. An évaluateur runs one group
  at a time; **the board lied.**
- **The group advance is built and correct.** `EvaluateurDashboardService.java:384-394` — closing a
  group opens the next `ordrePassage` **of the same station**, sequenced on rank, not on the clock.
- **No lot advance exists anywhere.** `LotService.java` is CRUD only
  (`findAll/findByExamenId/findById/save/delete/update`); `LotController` exposes REST CRUD plus
  `deplacerEtudiant`. There is no operation a responsable could call.
- **The « lot terminé » signal already exists on the backend, and the web cannot hear it.**
  `EvaluateurDashboardService.java:398-402` derives `Lot.statut = TERMINE` when the lot's last
  rotation closes and calls `broadcastLotStatus(lot.getId(), "TERMINE")` on
  `/topic/lots/{id}/status` (`:71`, `:510-513`). But `frontend-web` has **no STOMP/SockJS
  dependency** and **zero `/topic/` references** in `src` — only mobile subscribes.
- **There is no exam→scoring call direction.** The same wall ADR-0015 hit with `invalidateExam`:
  launch lives in exam-service, and scoring is the client in the only established direction
  (scoring→exam). So "launch opens lot 1" **cannot** be implemented as a call from exam-service.

## Decision

1. **Two advance levels, two owners — the ownership is the doctrine.**
   - **Groupe → groupe (intra-lot): the ÉVALUATEUR**, per station, via « Groupe suivant »
     (`validerGroupe`). Unchanged; already built. Confirms ADR-0014-A §1 first bullet.
   - **Lot → lot (inter-lot): the RESPONSABLE**, once, for the whole circuit. **Supersedes
     ADR-0014-A §1 second bullet.** Never exposed to the évaluateur, in any client.

2. **Lot 1 opens automatically; only lot N+1 is gated.** The gate exists so that a wave does not
   start before every évaluateur is ready — a constraint that only applies to *transitions between*
   waves. Lot 1 is already gated by two human acts above it (exam launched, presence marked), so
   requiring a third click buys nothing and costs an exam-day step.
   **The defect at `RotationGenerationService:197` is therefore precisely scoped:** it is not that
   *lots* auto-open, it is that **lots other than the exam's active wave** auto-open. Do not
   "fix" it by gating lot 1 as well — that deletes correct behaviour.

3. **Opening a lot is a scoring-side operation, `ouvrirLot(lotId)`.** Given there is no
   exam→scoring call direction (§Context), the open action lives in scoring and is driven by the
   day-of flow. Generation stops setting status unconditionally and instead delegates:
   - `generateForLot` writes **all rotations `EN_ATTENTE`**, then calls `ouvrirLot` **only if this
     is the exam's first wave to become gradable**;
   - **that condition is on STATE, never on `numeroLot`**: *no rotation of any other lot of the
     same exam is `EN_COURS` or `TERMINE`*. Stating it as state rather than "lot 1" keeps it
     correct when lots are generated out of order and when a lot is **regenerated**
     (`wipeLotGroups`, `:130`) after a mistake.
   One operation, two callers — the responsable's click and the first generation both route through
   `ouvrirLot`, so there is exactly one code path that may write `EN_COURS` at lot level.

4. **« Lot suivant » flips status; it never generates.** Generation stays a separate, explicit act
   (ADR-0014-A §3: PLAN is stored, not computed). `ouvrirLot` sets the target lot's rank-1
   rotations to `EN_COURS` — one per station, as the latin square already guarantees at `t=0` — and
   nothing else. Preconditions, each a distinct refusal message:
   - the target lot's rotations **exist** (else: *générez d'abord les rotations de ce lot*);
   - the target lot has **not already started** (all its rotations `EN_ATTENTE`);
   - **every rotation of the exam's currently open lot is `TERMINE`** — this is the handshake, and
     it is the only reason the action is gated at all.
   Refusals are **loud**, never silent no-ops: opening the wrong wave misdirects a room full of
   students.

5. **`Lot.statut` stays DERIVED (ADR-0014-A §2 unchanged), and the alert is derived too.** No
   "lot terminé" flag is stored: the alert is the already-derived `Lot.statut == TERMINE` plus the
   existence of a next lot still `EN_ATTENTE`. The backend signal exists
   (`broadcastLotStatus`, `:401`); since **web has no WebSocket client**, #208 derives the alert
   from the REST Suivi read. Adding STOMP to the web app is **out of scope here** — it is a
   separate infrastructure decision, not a prerequisite for this handshake.
   ⚠️ **Suivi does not refresh its data today.** Verified `suivi.component.ts:697`: the screen's
   only interval is `setInterval(() => this.now.set(Date.now()), 1000)` — a **clock tick** feeding
   the « temps écoulé / dépassement » readout ADR-0014 retires, **not** a data poll. The board
   loads once. So #208 must **add a refresh** (poll, or reload on the responsable's own actions);
   without it the alert cannot arrive, since web neither hears the broadcast nor re-reads. Deleting
   that clock interval and adding a data refresh are the same edit, from opposite directions.

6. **Mobile must NOT gain a lot-level control (#209).** The évaluateur advances groups only. The
   work is a rename (`_confirmerLotSuivant` → `_confirmerGroupeSuivant`) and cleanup, not a
   feature. Coordinate with Feten, who owns mobile implementation and merge.

## Consequences

- **#207 gains a lot-level advance** (`ouvrirLot` in `LotService`/`LotController`, responsable-
  authorized) **and the `:197` fix falls out of it** as the conditional in §3. The two commits
  currently on `feat/207-stored-rotation-progress` are a **partial spine with a known bug** — they
  must not be pushed as "Closes #207".
- **#208 (web Suivi)** carries the « Lot N terminé » alert and the « Lot suivant » control, both
  REST-derived per §5.
- **#209 (mobile)** shrinks to rename + cleanup, and gains a **negative** acceptance criterion: no
  lot-advance control may exist in the évaluateur app.
- **The concurrency bug becomes structurally unreachable**, not merely fixed: `EN_COURS` at lot
  level has exactly one writer (`ouvrirLot`), and its precondition is the previous wave being
  complete.
- **The ergonomics half is now specified enough to build** (`project_exam_day_flow_ergonomics`,
  #185): launch → presence → lot 1 opens → grade → « lot terminé » alert → « Lot suivant » → lot 2.
  Today the responsable must guess it across two tabs (Lancement → generate lots, then Lots →
  generate rotations).

### Left to implementation (decisions, not accidents)

- **Which lot « Lot suivant » targets.** Lowest `numeroLot` still `EN_ATTENTE`, or an explicit id
  from the responsable's click? Prefer the **explicit id** — the responsable sees the board, and an
  implicit "next" silently picks for them when lots are generated out of order.
- **`Lot.statut` must NOT be used to ask "is this wave open?"** — corrected 2026-07-21 after
  reading the writers. The column is **overloaded and already means something else**:
  `LotAssignmentService:113` creates lots `EN_ATTENTE`; **marking presence** flips them to
  `EN_COURS` (`LotAssignmentService:229`); `validerGroupe:399` flips them to `TERMINE`. The web
  reads that meaning too — the generate button's tooltip is « Enregistrez d'abord la présence »
  (`lots.component.ts:220`), and it **refuses to generate while `EN_ATTENTE`** (`:570`, `:584`).
  So a lot is `EN_COURS` **before it is ever opened**.
  Consequences, binding on #207 and #208: `ouvrirLot` **does not write `Lot.statut`** (it would be
  a no-op that further blurs the field), and **open/closed is read from rotation state only** —
  *started* = ∃ rotation not `EN_ATTENTE`; *finished* = ∀ rotations `TERMINE`. This also keeps the
  handshake honest: "all évaluateurs are done" is a fact about rotations, never about a lot flag
  someone might set by hand through `PUT /api/lots/{id}` (`LotService:42`).

### Explicitly NOT decided here

- **Créneaux are still clock-authored.** `debutCreneau` is precomputed at generation from
  `launched_at` (`RotationGenerationService:142-147`, `:180`), assuming zero drift and no real
  break. This ADR removes the *auto-open* ceiling at lot level; it does **not** resolve
  real inter-lot breaks or drift. **Do not conclude floor/ceiling is settled.**
  The clock keeps its FLOOR role (the student's assigned duration, the mobile countdown) and must
  never regain a CEILING role (ADR-0014 §3, `feedback_dont_fix_the_clock_delete_it`).
  **2026-07-23 update — one slice of "manual station start" IS now decided (Nada, #209):** the
  floor countdown anchors on `rotation.debut_reel` (V10), stamped when the **évaluateur** first
  opens the group — never on the planned créneau (which showed « 12:51 » remaining on a 2-minute
  station) and never on the responsable's lot opening. In the same decision, **valider was
  decoupled from advancing**: valider locks, only the explicit « Groupe suivant »
  (`avancerGroupe`, POST) opens the next rank. `debutCreneau` survives as PLAN display only.
- **Web WebSocket support** (§5) — separate decision if real-time push is ever wanted on Suivi.
- **`Lot.jour` / multi-day** — unchanged from ADR-0014-A §5; a lot's *day* is PLAN, its *opening*
  is the responsable act decided here. The two compose without interaction.
