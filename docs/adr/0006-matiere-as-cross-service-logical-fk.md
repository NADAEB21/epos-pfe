# ADR 0006: `Matiere` as cross-service reference data via logical FK

- **Date:** 2026-05-24
- **Status:** Accepted
- **Deciders:** Nada (lead architect)
- **Related:** issue #81 (this work), issue #58 / ADR 0005 (scoped JWT authorities),
  issue #86 (scoring per-matiere enforcement), issue #68 (`epos-common`)

## Context

Before #81, `Examen.matiere` was a free-text `VARCHAR(100)` and `UserRole.matiere_id`
was a `BIGINT` pointing at nothing. The scoped JWT authority
`ROLE_RESPONSABLE_MATIERE:5` (ADR 0005) cannot be compared against the string
`"Chimie"` — so the per-matiere 403 enforcement promised by the RBAC design
was defeated at the data-model level. A Responsable scoped to one matière had
effective access to every exam.

#81 introduces a real `Matiere` reference table. The question this ADR records:
**where does it live, and how do other services reference it?**

## Decision

`Matiere` lives in **auth-service**, since `user_roles.matiere_id` already
points at it conceptually. auth-service owns the reference table, the seed
data, and the `GET /api/v1/matieres` read endpoint used by frontend pickers.

Other services that need a matière reference store **`matiere_id BIGINT`**
(no JPA association, no DB-level FK across databases). This is a "logical FK":
identical pattern to `station_evaluateurs.evaluateur_id` which references
auth-service `users.id` (see `microservices/exam-service/src/main/resources/db/migration/V1__init_exam_schema.sql:61-69`).
The cross-database FK is enforced by application logic, not DDL.

## Why not the alternatives

**Why not a real DB FK?** Microservices live in different PostgreSQL databases
(`auth_db`, `exam_db`, `scoring_db`). PostgreSQL FKs cannot cross databases.
A single shared database breaks service isolation and was rejected for the
auth/exam/scoring split in earlier ADRs.

**Why not duplicate Matiere into each service?** Reference data drift —
the moment two services disagree on what matière 5 is, the scoped JWT
authority comparison is wrong. Single source of truth in auth-service
guarantees one definition.

**Why not extract a shared `epos-common` Maven module for the Matiere
entity?** That work is tracked as #68 and scheduled for Sprint 3. For #81
the cross-service consumer (exam-service) only needs the `Long matiere_id`
on its `Examen` row plus the scoped authority comparison — no Matiere
entity, no shared DTO. The frontend's matière picker hits
`GET /api/v1/matieres` directly; no service-to-service call is required.
When #68 lands, `MatiereResponse` can move into the shared module without
breaking any of #81's contracts.

## Consequences

- auth-service is the single owner of the matière catalog. Adding a new
  matière is a `INSERT INTO matieres` in auth_db — no schema change in
  exam/scoring.
- exam-service's `Examen.matiere_id` is **not** validated against `matieres`
  at write time. A malformed `matiere_id` in an exam create request will be
  accepted by the database but will fail the scoped-authority comparison.
  This is acceptable because (a) the frontend picker only surfaces valid
  IDs, and (b) the SpEL `@PreAuthorize` on `POST /api/examens` already
  rejects mismatches between the request body's `matiereId` and the caller's
  JWT scope.
- scoring-service inherits the same `matiere_id` indirectly through
  `examen_id` (see issue #86). The matière-scope check in scoring will call
  exam-service or denormalize `matiere_id` onto its own `notations` /
  `rotations` rows — that design decision is deferred to #86.
- `user_roles.matiere_id` gains a real DB-level FK to `matieres(id)` in
  auth_db. The existing `matiere_id = 1` placeholder seed remains valid
  because the `matieres` seed inserts id=1 = 'Chimie thérapeutique' first.

## Migration

- auth-service: new `matieres` table seeded in `infrastructure/init-db/init.sql`
  with 5 pharmacy subjects; FK added to `user_roles.matiere_id` (idempotent).
- exam-service: Flyway `V2__examen_matiere_id_fk.sql` adds `matiere_id BIGINT NOT NULL`,
  backfills existing dev rows to `1`, drops the old `matiere VARCHAR(100)` column,
  indexes the new FK column.
- scoring-service: untouched in #81. Issue #86 (Sprint 4) wires the symmetric
  enforcement using either denormalized `matiere_id` on scoring tables or a
  read call to exam-service.
