# ADR 0016: Exam closure is derived from the work, not declared by a button

- **Date:** 2026-07-29
- **Status:** **Proposed** — the direction is Nada's (2026-07-17, restated 2026-07-29: *« yeah I
  guess so »* to closing automatically once grading is done). §Decision 3 and §5 need her explicit
  yes before implementation; everything else follows from ADR-0014-B's existing rule.
- **Deciders:** Nada (lead architect).
- **Related:** **ADR-0014** (évaluateur-paced lifecycle — the constitution), **ADR-0014-B**
  (lot open/closed is read from rotation state — the precedent this extends), **ADR-0013**
  (notation lock + audited réajustement — the sanctioned post-closure change path),
  **#235** (closure strands évaluateurs), **#236** (closure enforces nothing), **#257**
  (« Terminer » is irreversible), #206 (reframe epic). Code in scope:
  `ExamenServiceImpl.changerStatut`, `validerTransitionStatut`,
  `EvaluateurDashboardService.buildDashboard`, `suivi.component.ts`.

## ⚠️ Correction (2026-07-29, Nada) — le web APPLIQUE DÉJÀ cette règle. Danger surestimé.

Nada : *« doesn't the current code already justify : the close exam option only shows after all
teachers have finished grading ? »* — **oui, et ça change le diagnostic.**

`suivi.component.html:109` place tout le bloc « Terminer l'examen » **à l'intérieur** de
`@if (toutesVaguesTerminees())` : le bouton n'est même pas rendu avant, et une confirmation en deux
temps s'y ajoute. `toutesVaguesTerminees` (`suivi.component.ts:534-538`) vaut
`lotSuivant == null && lotOuvert.ecouleSec == null` — plus aucun lot à faire tourner, et la vague
ouverte est terminée.

**C'est exactement la règle proposée en §1 ci-dessous**, subtilité multi-lots comprise. Le web
l'avait déjà.

Deux conséquences, à assumer :

1. **Le scénario alarmant de cette ADR — « un clic malheureux à 11h, trois étudiants non notés, gèle
   l'examen » — n'est PAS atteignable par l'interface.** Un responsable réel ne peut pas clôturer
   trop tôt. Il fallait le dire.
2. **Mes preuves de #235/#236 passent par l'API en direct** (`PATCH /examens/53/statut`), un chemin
   qu'un responsable n'emprunte jamais. Elles établissent que **le backend ne garde rien** — pas que
   le produit est cassé. Frapper les endpoints reste la bonne discipline, mais cela démontre l'état
   de l'API, pas celui du parcours réel.

**Ce qui reste ouvert est donc étroit** : la règle vit dans le composant Angular et nulle part côté
serveur. C'est un défaut de défense en profondeur — un second client (le mobile), un script, ou une
régression du gabarit la contournent. Réel, à corriger, **mais pas critique pour faire passer un
examen** : d'où le classement en phase de raffinement (décision Nada, 2026-07-29).

## Context

### Today, closure is a hand-set flag — the last one with authority

`ExamenServiceImpl.changerStatut:165` sets `Examen.statut` on demand, and
`validerTransitionStatut:420-425` allows `EN_COURS → TERMINE` and then only
`TERMINE → ARCHIVE`. There is **no way back**: #257, which Nada hit herself mid-session.

Nothing consults the actual work. An exam can be declared finished while students are
still ungraded.

### Two live-proven consequences, and they point in opposite directions

Reproduced on exam 53 (2 stations, 4 students, one lot started, then closed):

**#235 — closing locks out the person who still has work.**
`buildDashboard:101-108` keeps only exams whose status is `EN_COURS`. After closure the
owning évaluateur's board returns **0 sessions**, including two students never graded.
Their work is unreachable through the application.

**#236 — but the door stays open for everyone else.**
No status gate exists on the write path. After closure:

```
POST /evaluateur/notations/saisir                   → 200 « Notation enregistrée »
POST /evaluateur/etudiants/304/stations/87/valider  → 200 « Notes verrouillées »
```

So closure **shuts out the honest actor and admits everyone else**. It is precisely backwards.

### Why the obvious fix is the wrong one

The tempting repair is "block writes when `statut == TERMINE`". That would make the situation
worse in a way the project has already ruled against. ADR-0014-B:160-166, binding:

> *open/closed is read from rotation state only — started = ∃ rotation not `EN_ATTENTE`;
> finished = ∀ rotations `TERMINE`. This also keeps the handshake honest: "all évaluateurs are
> done" is a fact about rotations, **never about a lot flag someone might set by hand**.*

Lot closure is already derived. Exam closure is the **last hand-set flag left with authority**.
Gating writes on it would hand that flag the power to stop grading — reintroducing at exam level
exactly what ADR-0014-B removed at lot level. A mis-click at 11:00, with three students ungraded,
would freeze the exam permanently (`TERMINE` has no way back).

### A delay was considered and rejected

Nada's first instinct was a grace period — *« after a while maybe not immediately, just in case »*.
Rejected for two reasons:

1. **It only moves the wall.** After the delay you are trapped exactly as before, with the same
   unease.
2. **It permits silent change.** During the window anyone could alter a grade with no trace.

The need behind the instinct is already met, and met better: **ADR-0013 Part 2's audited
réajustement channel** (`POST /api/notations/{id}/reajustement`, body carries a `motif`) is a
deliberate, recorded, responsable-only correction path. It answers *"something came up later"*
while writing down **why** — which a delay does not. Nada confirmed on 2026-07-29 that this channel
stays responsable-only.

## Decision

1. **An exam is finished when its work is finished.** Derived, no human declares it:

   > **every lot has run** (its rotations exist) **AND every rotation is terminal.**

   ⚠️ **The naive form of this rule is wrong, and the trap is not obvious.** "∀ rotations
   `TERMINE`" — the lot rule lifted verbatim — **closes a multi-lot exam halfway through**.
   Rotations are generated *per lot, at lot start* (`presence-et-demarrer`), not for the whole exam
   up front. So between waves, every rotation that *exists* is `TERMINE` and the condition is
   **vacuously true**. Measured: exam 51 carries **2 lots with 0 rotations each**; exam 53's lot 266
   had 0 until it was started.

   Hence the first clause. A rule that is right on a one-lot fixture and wrong on two is exactly the
   class of bug that produced the `ordre_import` collision — correct until someone runs a real
   cohort.

2. **`EN_COURS → TERMINE` stops being a free-hand transition.** The responsable no longer *declares*
   the end; the system observes it. What remains of the button is covered by §3.

3. **A student who was never evaluated is recorded as such — `NON_EVALUE`.** ⚠️ **Needs Nada's
   explicit yes.**
   Without this, derived closure has a hole: one un-graded student would keep an exam open forever,
   and the responsable would have no honest way out. The escape must stay *a statement of fact*
   ("this student was not evaluated"), never *an override* ("declare the exam finished anyway").
   The responsable may mark a remaining rotation `NON_EVALUE`; closure still derives from the rule
   in §1, now satisfiable. This keeps humans recording facts and the system deriving state.

4. **Writes are gated on the derived fact, not on the flag.** Once §1 holds, grading and locking are
   refused — because there is genuinely nothing left to grade, not because someone pressed
   something. This is what closes #236 without recreating the problem.

5. **The évaluateur keeps READ access to their OWN past exams after closure.**
   `buildDashboard`'s `EN_COURS`-only filter is right for *"what must I do today"* and wrong for
   *"what did I do"*. Closing an exam must never make a teacher's own record vanish — that is the
   half of #235 that §1 does not fix. Scope is deliberately narrow: exams they served, nothing else.

   ⚠️ **Sequencing constraint, not a preference:** §5 *widens* what the board returns, and the write
   path is currently ungated. Shipping §5 before §4 would make #236 reachable from more screens than
   it is today. **§4 lands first, or they land together — never §5 alone.**

6. **Corrections after closure go through ADR-0013's audited réajustement channel**, responsable-only.
   No reopening, no grace window, no silent edit.

## Consequences

- **#236 closes** — writes are refused, on a fact.
- **#235 closes** — nothing ungraded remains at closure (§1), and past work stays readable (§5).
- **#257 dissolves** — there is no premature manual closure left to regret. Irreversibility stops
  being a trap because closure can no longer arrive before the work does.
- **Suivi's « Terminer l'examen » changes meaning** and probably disappears as an action. The screen
  should *report* that the exam is finished, and offer archiving. Web work, tracked under #208's
  successor.
- **The write-guard for #213 can now be built**, keyed on derived state — one pass over
  `saisirNotation` / `validerEtudiant` / `validerLot`.
- **Existing `TERMINE` exams** were closed under the old rule and may hold ungraded rotations.
  They are grandfathered: derived closure applies going forward; no retro-computation, which would
  silently reopen historical exams.
- ⚠️ **The paused-exam guard needs a new home, or it is silently lost.**
  `ExamenServiceImpl:193` currently refuses `TERMINE` while `enPause` is true (ADR-0009). It works
  by intercepting the *manual* transition — which §2 removes. Under derived closure, a paused exam
  whose rotations are all terminal would close **while frozen**. Either §1 gains "and the exam is
  not paused", or the guard moves onto the derivation. Not decided here, but it must not be
  forgotten in implementation — an intercepted path that no longer exists fails silently.

## Explicitly NOT decided here

- **How an évaluateur is replaced** (sick before the day, mid-exam handover). Verified: no ADR
  defines substitution, rotations freeze `evaluateur_id` at generation, and exam-service's
  `affecterEvaluateurs` never notifies scoring. The #213 write-guard depends on that answer as much
  as on this ADR — a guard with no substitution path is an exam-day lockout. **Separate ADR.**
- Whether an évaluateur may *request* a correction on their own locked grade (#251). Nada settled
  the current behaviour on 2026-07-29: correction stays responsable-only.
