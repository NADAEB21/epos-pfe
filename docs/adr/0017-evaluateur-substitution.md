# ADR 0017: Évaluateur substitution — one real case, and it is responsable-owned

- **Date:** 2026-07-29
- **Status:** **Accepted** (2026-07-29, Nada — §3 ratified: the substitute inherits the group
  **currently in progress**, on the reasoning that someone must finish a half-graded group).
- **Deciders:** Nada (lead architect).
- **Related:** **ADR-0014-B** (organisational acts belong to the responsable — the ownership
  argument reused here), **ADR-0016** (derived closure — substitution must not make an exam
  uncloseable), **#213** (the write-guard this unblocks), **#265** (an évaluateur cannot serve two
  live exams), #212/#215 (blank-entity writes — the back door closed in §6). Code in scope:
  `RotationGenerationService`, `RotationController`, `EvaluateurDashboardService`,
  `StationServiceImpl.affecterEvaluateurs`.

## Context

Nothing in the project defines what happens when an évaluateur cannot serve. `grep -i
"substitut|remplac"` over `docs/adr/` returns **nothing**. Yet it is an ordinary event, and Nada
named the three shapes it takes (2026-07-29):

> *« the teacher calls in sick day of or pre day of exam, or could even go home due to an emergency
> mid exam with groups or lots still ungraded »*

She then asked whether they are really three different problems. **They are not — they are two, and
only one needs building.**

### Cases A and B — sick before the day, or that morning — already work

An évaluateur is not bound to anything until the wave actually starts. `RotationGenerationService`
stamps `rotation.setEvaluateurId(...)` at generation (`:194`), reading whoever is assigned to the
station **at that moment** (`:239-240`, from the exam-service view). Generation happens per lot, at
`presence-et-demarrer`.

So before the wave starts there is nothing to repair: reassign the station
(`PATCH /stations/{id}/evaluateurs`), then start. Measured: exam 51 holds **2 lots and 0 rotations**;
exam 53's lot 266 had 0 until it was started.

**No feature is needed for A and B.** This ADR records that on purpose — so nobody builds one, and
so the day-of answer is written down instead of rediscovered under pressure.

### Case C — leaves mid-exam — is the only real one, and today it is unreachable

Once the wave is running, rotations carry a frozen `evaluateurId`, and:

- `StationServiceImpl.affecterEvaluateurs` **never notifies scoring** — no rotation, WebClient or
  scoring reference anywhere in it. Reassigning the station in exam-service leaves scoring's
  rotations pointing at the person who left. Verified live: rotations 268/271 still on évaluateur 3.
- The board is built from `rotation.evaluateurId`, so the substitute sees **nothing** and the
  absent colleague still "owns" the work.

### The back door that does exist is worse than none

`RotationController:77` and `:99` accept `evaluateurId` on the generic rotation write. So the field
*is* mutable — through an ungated, per-rotation CRUD endpoint of the blank-entity family (#215),
with no check on who the substitute is, no distinction between finished and unfinished work, and no
trace. **An undesigned capability is not a substitution mechanism; it is an accident waiting for a
bad day.**

### Why this blocks #213

The #213 write-guard must refuse an évaluateur writing outside their station. Shipped **without**
substitution, that guard refuses every grade a covering colleague enters, on exam day, with no
in-app recovery. The guard and this ADR must land together.

## Decision

1. **Substitution is an organisational act, therefore responsable-owned.** Same argument as
   ADR-0014-B: a station serves a wave that belongs to the whole exam, so who staffs it is not an
   évaluateur's decision. An évaluateur cannot hand their station to someone else, and cannot claim
   someone else's.

2. **Before the wave starts, there is no substitution — only assignment.** Reassign the station and
   start. No endpoint, no new state. (Cases A and B.)

3. **Mid-exam, one explicit action transfers the REMAINING work.**
   `POST /lots/{lotId}/stations/{stationId}/remplacer-evaluateur { nouvelEvaluateurId, motif }`.
   Rotations of that station that are **not `TERMINE`** move to the substitute. Rotations already
   `TERMINE` **keep their original évaluateur** — history is not rewritten.

   **Ratified 2026-07-29 (Nada):** the substitute **inherits the group currently in progress**
   (`EN_COURS`), not merely the ones not yet started. A teacher who leaves mid-group leaves a
   half-graded group behind and someone must finish it; not inheriting would strand exactly the
   students the substitution exists to serve.

   **Amended 2026-08-19 (#347) — the handover must be COMPLETE.** The validate/advance
   decoupling (#209) creates a seam this ADR had not seen: an évaluateur who validated their
   last group **without clicking « Groupe suivant »** leaves the station with no `EN_COURS`
   rotation, and the only act that opens the next rank (`avancerGroupe`) is guarded by
   ownership of that `TERMINE` rotation — which §3 deliberately leaves with the departed
   évaluateur. The substitute was locked out (no current session, advance refused); the only
   recovery was the departed évaluateur's own phone, which the emergency-departure scenario
   (case C) excludes by definition. Reproduced live (exam 77, station 101, 2026-08-18).

   Therefore the substitution act itself opens the lowest-rank transferred rotation, **iff**
   (a) no rotation of the station is `EN_COURS` — a working évaluateur is never paced from
   outside (#248) — **and** (b) at least one rotation of the station is `TERMINE` — proof the
   wave has started there, so a generated-but-unopened lot is never opened ahead of its wave
   (`LotOuvertureService` remains the only wave-level opener, ADR-0014-B). Status only, like
   `ouvrirRangInitial`: `debutReel` stays null until the substitute first opens the screen
   (#209), so the students' time floor is anchored on an observed fact, not on the handover.

4. **Grades already entered keep their author.** They carry `notations.saisi_par` (V15, #213), so a
   handover cannot repaint the departed évaluateur's work as the substitute's. **This is why
   authorship shipped first**; substitution without it would have silently rewritten who graded whom.

5. **The substitute must be free.** The #265 rule applies unchanged: someone already engaged in
   another live exam cannot take a station here. Reuse the existing conflict computation rather than
   writing a second, divergent one.

6. **Close the back door.** `evaluateurId` stops being writable through the generic rotation
   endpoints (`RotationController:77`, `:99`). After this ADR there is exactly one way to change who
   serves a station, and it is auditable.

## Consequences

- **#213's guard becomes safe to build**, and consistent by construction: it keys on the rotation's
  *current* évaluateur, which is precisely what §3 updates.
- **ADR-0016 stays satisfiable.** A departed évaluateur can no longer make an exam uncloseable:
  their unfinished rotations move to someone who can finish them.
- **The action carries a `motif`**, like réajustement (ADR-0013). A staffing change on exam day is
  exactly the kind of thing that must be explainable afterwards.
- ⚠️ **Known limitation, stated rather than discovered later.** `saisi_par` records the **last**
  writer on a notation. A student graded half by the departing évaluateur and half by the substitute
  will show only the substitute. Acceptable — the signer of record is whoever completed it — but if
  per-criterion authorship is ever needed, it belongs on `NotationItem`, not here.
- The web needs a discreet control on Suivi (« remplacer l'évaluateur de cette station ») —
  deliberately not on the évaluateur's own screen, per §1.

## Explicitly NOT decided here

- What happens if **no substitute exists** (a station simply stops). That is ADR-0016's
  `NON_EVALUE` question, not this one.
- Whether the departed évaluateur keeps read access to what they graded — covered by ADR-0016 §5.
