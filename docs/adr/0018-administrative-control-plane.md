# ADR 0018: Administrative control plane — faculty scope, matière scope, and the visibility contract

- **Date:** 2026-07-30
- **Status:** **Proposed**
- **Deciders:** Nada (lead architect).
- **Related:** ADR-0007 (évaluateur global scoping), ADR-0006 (matière as cross-service logical FK),
  ADR-0013 (audited réajustement), ADR-0015 (definition snapshot at launch), ADR-0017 (substitution),
  #86 (per-matière 403 not enforced in scoring), #134 (matière catalogue CRUD), #64 (no audit trail
  outside auth), #259 (deleted — no UI to create an évaluateur). Code in scope:
  `EvaluateurScopeChecker`, `MatiereController`, `UserController`, `SecurityConfig` (scoring),
  `user_roles`.

## Context — what the platform actually enforces today

Three findings, each verified in code, and together they say the control plane does not exist yet.

### 1. Co-responsability is ALREADY representable — and therefore already live

```sql
-- V1__init_auth_schema.sql:62
CREATE UNIQUE INDEX ux_user_roles_user_role_matiere
    ON user_roles (user_id, role, COALESCE(matiere_id, -1));
```

Unique per **(user, role, matière)** — so **two different users may both hold
`RESPONSABLE_MATIERE` on matière 1.** No new role is required, and none should be added: a
`CO_RESPONSABLE` enum value would duplicate a capability the data model already has and force every
authorization site to learn a second concept.

**Consequence that matters:** the "shared material problem" is not a future feature. It is a latent
defect *today*, hidden only because `equipe/co-responsables` is a stub route
(`app.routes.ts:143`) and nobody has been given a second responsable yet.

### 2. `isUnrestricted()` is FACULTY-wide, not matière-wide

```java
// EvaluateurScopeChecker:43-54 — true for SUPER_ADMIN *or* RESPONSABLE_MATIERE,
// with no comparison against the caller's matiere_id
```

So in scoring-service:

- a responsable of matière 1 passes every ownership check on matière 2's grading data;
- there is **no matière predicate anywhere in scoring** — authorization is `hasAnyRole(...)` only
  (#86, verified: the only files mentioning "matiere" are `SecurityConfig` and a DTO).

The scoped-authority format `ROLE_RESPONSABLE_MATIERE:5` (ADR-0005) is *carried* in the token and
**never read** by scoring.

### 3. A responsable is an évaluateur, in the default seed

```
resp@epos.tn | RESPONSABLE_MATIERE | 1
resp@epos.tn | EVALUATEUR          | (global)
```

That is the **shipped configuration**, not a test artefact. Two consequences: a responsable can grade
in their own exam, and because `isUnrestricted()` sees `RESPONSABLE_MATIERE`, they bypass the #213/#218
ownership guards entirely. The guard shipped on 2026-07-29 constrains *pure* évaluateurs only.

### 4. The faculty has no write surface at all

`MatiereController` exposes exactly one endpoint — `@GetMapping` / `isAuthenticated()`. There is no
matière creation (#134), and no UI to create a user (#259, deleted with the gap noted). A super-admin
today can do strictly less than a responsable.

## Decision

### D1 — Two scopes, named, with one of them currently fictional

| scope | actor | owns | must NOT |
|---|---|---|---|
| **Faculty** | `SUPER_ADMIN` | matière catalogue, user accounts, role grants, cross-matière analytics | author or grade an exam |
| **Matière** | `RESPONSABLE_MATIERE` (1..n per matière) | everything inside their matière's exams | reach another matière's data |

**Co-responsables are simply the `n`** in "1..n `RESPONSABLE_MATIERE` per matière". No new role.

### D2 — `isUnrestricted()` is split into two questions, because it currently answers the wrong one

It conflates *"is this caller above the évaluateur ownership rule?"* with *"may this caller touch
this matière?"*. Those separate:

- `isUnrestricted()` — keeps its current meaning (skip the **évaluateur** ownership check). Correct
  for a responsable acting inside their own matière.
- **new** `checkMatiereAccess(examenId)` — the matière predicate that scoring has never had. Resolves
  the exam's `matiereId` and compares it against the caller's scoped authorities.

**`SUPER_ADMIN` passes `checkMatiereAccess` unconditionally.** Per D1 the faculty scope owns no
matière, so a matière comparison is meaningless for it — and a predicate that fails closed would lock
the faculty out of its own platform. Its counterweight is D3 (attributed + announced), not a scope
check.

⚠️ **Where `matiereId` comes from — and the snapshot is NOT the answer.**
An earlier draft of this ADR proposed freezing `matiereId` in `exam_station_snapshot` so the check
would cost no network call. **That is wrong, and ADR-0019 is why:** the snapshot is written
*write-once at launch*. A `BROUILLON`/`CONFIGURE` exam has no snapshot — which is precisely when a
responsable is authoring, and precisely the window #86 leaves open. #86's exposure was never limited
to live exams.

So the resolution needs a source that exists before launch. Two candidates, and this ADR does not
pick between them:

1. **Ask exam-service** (`ExamServiceClient`, the direction that already exists) and cache per
   request. Always correct, costs a hop, and inherits the outage behaviour ADR-0015 was written to
   avoid.
2. **Denormalise `matiereId` onto scoring's own rows at enrolment** — stamped when a participation or
   lot is created, the same precedent as `rotation.stationId`/`evaluateurId` (logical FKs, ADR-0006).
   No hop, no outage coupling; costs a migration and a backfill.

⚠️ Whichever is chosen, it must **fail closed for a constrained caller and never fail open**: an
unresolvable `matiereId` must deny, not admit. That is the opposite of the évaluateur board's
deliberate fail-open (#241) — and the difference is the direction of harm. Showing an évaluateur one
exam too many is awkward; letting a responsable read another matière's grades is the breach.

### D3 — A faculty act inside a matière is ATTRIBUTED and ANNOUNCED, never silent

This is the synchronization contract the responsable is owed. A `SUPER_ADMIN` retains the technical
ability to act inside a matière (support, unblocking, a jury decision), but:

1. **Attributed** — the acting user id is persisted on the artefact. `notations.saisi_par` (V15)
   established the pattern; it generalises.
2. **Announced** — the responsable of that matière receives a durable notification. Not a toast: a
   record they will still see tomorrow.
3. **Never a silent overwrite** — a faculty write that alters matière-owned data without either of
   the above is a defect, not a privilege.

Rationale: today a super-admin can rewrite a grille and the responsable has no way to learn it
happened. In a pharmacy exam that is unacceptable — the responsable signs for the barème.

### D4 — Role separation of duties: a responsable grading their own exam must be visible

Not forbidden (a small faculty genuinely needs it — the responsable *is* often a station examiner),
but it must be **explicit**: when the caller holds both roles for the exam they are grading, the
notation records it, and the exam's results view flags which grades were entered by the responsable.

⚠️ **And the ownership guards must stop being bypassed by role alone.** `isUnrestricted()` should not
exempt a responsable who is acting *as an évaluateur* on a station they hold. Otherwise #213/#218 are
closed for colleagues and open for the person with the most authority.

## Consequences

- **#86 becomes implementable** via D2 + `matiereId` in the snapshot, with no per-request hop.
- **#134 (matière CRUD) and the user-creation UI become the faculty plane's minimum viable surface** —
  without them a fresh faculty install cannot be operated by its intended users (verified on the
  faculty PC: creating "Sonia" was API-only).
- **#64 (audit trail) is upgraded from hygiene to load-bearing**: D3 is unimplementable without a
  place to record who did what.
- Co-responsable UI stops being a "new feature" and becomes **surfacing a capability that already
  exists** — plus the concurrency work of ADR-0019, which it makes urgent rather than theoretical.

## Explicitly NOT decided here

- The concurrency strategy for two responsables editing at once → **ADR-0019**.
- How a lifecycle event reaches scoring without orphans → **ADR-0020**.
- Cross-matière analytics and jury deliberation → **ADR-0021**.
- Whether a matière may have a *primary* responsable (tie-break for irreversible acts). Deliberately
  deferred: it only matters once co-responsables are surfaced, and it may prove unnecessary.
