# ADR 0013: Notation lock integrity + audited réajustement

- **Date:** 2026-07-03 (Part 2 shipped 2026-07-04)
- **Status:** Accepted (Part 1 + Part 2 shipped)
- **Deciders:** Nada (lead architect).
- **Related:** issue #23 (lock bypass), #135 (réajustement), #136 (réclamation),
  #64 (audit trail), #85 / ADR 0007 (évaluateur scoping — who may lock),
  `NotationService.verrouiller`, `NotationItemService`.

## Context

A `Notation` carries a `verouillee` flag; once an évaluateur (or admin) locks it
via `PATCH /api/notations/{id}/verrouiller`, the score is meant to be final
("verrouillée définitivement").

But the lock was only **half enforced**:

- `NotationService.update` **does** reject edits to a locked notation
  (`NotationService.java:162-163`), and the dashboard path does too
  (`EvaluateurDashboardService.java:219-221`).
- `NotationItemService.save / update / delete` (`:45-63`) **never** loaded the
  parent notation's `verouillee` — so the per-critère item endpoints
  (`POST/PUT/DELETE /api/notation-items/**`, open to ÉVAL/RESP/ADMIN) were a
  silent back door: a "locked" score could be changed critère-by-critère with no
  check, no reason, no audit (#23).

At the same time, the supervisor requires that a **responsable can change a
student's note when the student files a verification request** (réclamation,
#136/#135). Taken naively, "make the lock absolute" and "let the responsable
adjust a locked note" look contradictory.

They are not — but resolving them forces an explicit rule, because today there is
**no unlock and no adjustment path at all** (`verrouiller` is one-way; grep for
`deverrouill` = none). So a pure hard-block, shipped alone, would make a
legitimate réclamation adjustment *impossible* ("frozen forever").

## Decision

**A locked notation is final on every ordinary path. It may be changed only
through one explicit, authorized, audited *réajustement* channel.**

Two parts, shipped as a pair (Part 1 first, Part 2 immediately after).

### Part 1 — Lock integrity (#23, shipped)

`NotationItemService` now rejects any write touching a locked parent notation
(`BusinessException` → HTTP 400), across all three paths:

- `save` — checks the (authoritative, re-fetched) parent before insert.
- `update` — checks the **original** parent (before reassignment) *and* the
  target parent (guards moving a critère into a locked notation).
- `delete` — loads the critère and checks its parent before removing it.

The parent is always re-fetched from the repository (not trusted from the
client-supplied object), so a forged `verouillee=false` in the request body
cannot bypass it.

### Part 2 — Audited réajustement channel (#135, shipped)

One dedicated, atomic, always-logged endpoint — **not** an unlock→edit→relock
dance (which would leave an abusable "unlocked window"):

- `POST /api/notations/{id}/reajustement` body `{ itemId?, nouvelleValeur, motif }`.
- In one transaction: record `ancienneValeur → nouvelleValeur`, `ancienScore →
  nouveauScore`, `motif`, `adjustedByUserId`, `adjustedAt` in a new
  `NotationAdjustment` audit entity (scoring-service has no audit table today),
  then apply the change — the notation is **never left unlocked**.
- **Authorization:** `RESPONSABLE_MATIERE` (matière-scoped) + `SUPER_ADMIN`.
  **Not** the évaluateur — oversight/complaints are the responsable's role, and
  the évaluateur already had their grading pass. This makes the responsable a
  *new* score-mutation actor (today they cannot even lock), which is the
  deliberate dashboard-separation decision here.
- A responsable may adjust directly **with a required `motif`** — the adjustment
  does not hard-depend on a formal complaint record (#136) existing first; the
  motif captures the réclamation reason. #136 (complaint intake) can later
  reference the same adjustment records.
- Évaluateur notification on adjustment is **deferred** (recorded in the audit
  now; active notification later, given the limited notification infra).

**Implementation notes (as shipped):**

- Endpoints: `POST /api/notations/{id}/reajustement` `{itemId?, nouvelleValeur,
  motif}` and `GET /api/notations/{id}/reajustements` (history), both
  `@PreAuthorize hasAnyRole('SUPER_ADMIN','RESPONSABLE_MATIERE')`. New
  `NotationReajustementService` writes the item/score **directly** (never through
  the item endpoints, which reject a locked parent) so the lock is never lifted.
- The audit write is **synchronous** inside the same `@Transactional` as the
  mutation — deliberately *unlike* auth-service's `@Async` `AuditLog`. Here the
  row is the integrity record for a privileged change; it must commit atomically
  with the change or not at all (an `@Async` failure would leave a silent edit,
  the exact thing #23 forbids).
- An item-level réajustement recomputes `score_final` with the **same weighted
  formula** as évaluateur grading (`BINAIRE → valeur×pondération`), so a corrected
  critère yields the total grading would have produced. Formula currently
  duplicated from `EvaluateurDashboardService.recalculerScoreFinal`; unify under
  #68.
- **Scope caveat:** authorization is role-only (RESP + ADMIN). Per-matière
  narrowing of the responsable rides on **#86** (per-matière 403 is unbuilt
  everywhere in scoring today) — not faked here.

## Consequences

- The lock now means the same thing on every path; the #23 back door is closed
  (unit-covered: locked parent → save/update/delete all rejected; unlocked →
  unchanged behaviour).
- Nothing can edit a locked score **silently** anymore. The only way through is
  the audited channel, which records who/why/old→new — turning the lock into a
  real integrity boundary the réclamation flow (#135/#136) can safely sit on.
- Part 1 and Part 2 must land close together: until Part 2 ships, a locked note
  genuinely cannot be adjusted. This is acceptable only briefly and is the reason
  the two are treated as one decision.
- `NotationAdjustment` gives #64 (audit trail) a concrete first home in
  scoring-service and can generalize later.
