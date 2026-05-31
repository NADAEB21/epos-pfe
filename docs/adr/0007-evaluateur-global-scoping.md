# ADR 0007: `EVALUATEUR` is globally scoped; ownership is by rotation, not by matière

- **Date:** 2026-05-31
- **Status:** Accepted
- **Deciders:** Nada (lead architect)
- **Related:** issue #85 (évaluateur lock authz hole — this work), issue #91
  (évaluateur read scope — this work), ADR 0005 (scoped JWT authorities),
  ADR 0006 (matière as cross-service logical FK), issue #86 (scoring
  per-matière enforcement — separate, still open)

## Context

EPOS has three roles (`RoleType.java`, auth-service): `SUPER_ADMIN`,
`RESPONSABLE_MATIERE`, `EVALUATEUR`. `RESPONSABLE_MATIERE` carries a
`matiere_id` and is enforced per-matière through the scoped JWT authority
`ROLE_RESPONSABLE_MATIERE:<id>` (ADR 0005, ADR 0006).

The open question this ADR settles: **what is an évaluateur scoped to?**

Two candidate models were on the table:

- **Option A — scope évaluateurs by matière** (give `ROLE_EVALUATEUR` a
  `matiere_id` like the responsable). Rejected: an évaluateur is invited to
  grade a *specific exam's specific stations*, not "everything in a subject".
  A subject can run several exams a semester with disjoint examiner panels.
  Matière is too coarse and would let an évaluateur read/modify notations of
  an exam they were never assigned to.
- **Option C — keep évaluateurs global at the role level; derive authority
  from assignment.** Adopted. The authoritative binding of an évaluateur to
  work is `Rotation.evaluateurId` in scoring-service (and, upstream,
  `station_evaluateurs.evaluateur_id` in exam-service, which is how a
  responsable binds an examiner to a station pre-exam). An évaluateur's JWT
  carries the bare authority `ROLE_EVALUATEUR` with no scope suffix.

## Decision

1. **`ROLE_EVALUATEUR` has no `matiere_id` and no scope suffix.** It is a
   global role. `RoleType.EVALUATEUR` keeps `matiere_id = null` (already
   enforced in auth-service `UserRole` `@PrePersist`).

2. **An évaluateur's effective scope is the set of rotations whose
   `Rotation.evaluateurId` equals the évaluateur's user id.** Ownership is
   resolved per request from the JWT `userId` claim (auth-service
   `JwtService.generateAccessToken` emits `claim("userId", user.getId())`),
   not from any authority string.

3. **scoring-service enforces this at the service layer** via a new
   `EvaluateurScopeChecker`:
   - Writes (`NotationService.save / update / verrouiller`): when the caller
     is a pure `EVALUATEUR`, the notation's owning évaluateur is resolved
     through `Notation → RotationAssignment → Rotation.evaluateurId` and must
     equal the caller's `userId`, else `403`.
   - Reads (`NotationService.findAll / findByStation / findByGrille`,
     `RotationService.findAll / findByGroup / findByStation`,
     `RotationAssignmentService.findAll / findByRotation`): when the caller is
     a pure `EVALUATEUR`, the returned collection is filtered to rotations the
     caller owns.
   - `SUPER_ADMIN` and `RESPONSABLE_MATIERE` short-circuit to unrestricted
     (legitimate cross-examiner corrections and oversight).

4. **Binding an évaluateur to a station stays in exam-service**
   (`PATCH /api/stations/{id}/evaluateurs`), done pre-exam by the responsable.
   This ADR does not change how the binding is created; it only governs how
   scoring-service reads ownership from the rotation that a binding produces.

## Why not enforce in the controller with SpEL?

`@PreAuthorize` on a path like `PATCH /api/notations/{id}/verrouiller` cannot
see the notation's owning évaluateur without first loading the entity and
walking the rotation chain — SpEL has only the opaque `{id}`. Same constraint
that put `MatiereAccessChecker` at the service layer in exam-service. The
controller `@PreAuthorize` still does the coarse role gate
(`hasAnyRole(...)`); the checker does the fine per-rotation gate.

## Consequences

- The évaluateur lock hole (#85) closes: an évaluateur can only lock/modify a
  notation attached to one of their own rotations. A second évaluateur on a
  different station gets `403`.
- Read leakage (#91) closes for the list endpoints above: an évaluateur sees
  only their own rotations' data.
- **Create-path correction (folded into this work):** `NotationController.create`
  previously built a `Notation` without linking its `RotationAssignment`, so
  the rotation chain did not exist at write time and notations were created
  orphaned. Create now consumes `NotationDTO.assignmentId`, links the
  assignment, and is scope-checked through the same chain. This is a
  prerequisite for enforcing scope on create, and fixes a latent data-integrity
  bug independent of authz.
- **Known gap, explicitly NOT closed here:** scoring-service has no matière
  awareness, so the `RESPONSABLE_MATIERE` short-circuit is unconditional — a
  responsable of matière A can currently touch matière B's notations. That is
  the subject of #86 (denormalize `matiere_id` onto scoring rows, or resolve
  via exam-service) and is out of scope for #85/#91. Recorded so the bypass is
  not mistaken for a closed control.
- `EVALUATEUR` cannot reach `DELETE /api/notations/{id}` (SUPER_ADMIN only) or
  the responsable-only oversight endpoints — unchanged.

## Alternatives considered

- **Matière-scoped évaluateur (Option A):** rejected, see Context.
- **Checker resolves the chain itself via `INotationRepository`** (the shape
  sketched in the Phase B plan, `isAccessibleByCaller(notationId)`): rejected.
  It couples the auth component to a repository, only fits notations (not the
  rotation/assignment read endpoints), and forces repo mocking in unit tests.
  Instead the checker is pure auth logic (`isUnrestricted`, `getCallerUserId`,
  `isCaller`, `checkOwnership`) and each service performs its own entity-chain
  navigation, since it already holds the loaded entities.
