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
| **Faculty** | `SUPER_ADMIN` | matière catalogue, user accounts, role grants, global config, **read access to every matière** | **author, launch, grade or close** an exam — see **D5** |
| **Matière** | `RESPONSABLE_MATIERE` (1..n per matière) | everything inside their matière's exams | reach another matière's data |

⚠️ **D5 is the operative elaboration of this row's "must NOT" column** and takes precedence over any
looser phrasing elsewhere in this ADR.

**Co-responsables are simply the `n`** in "1..n `RESPONSABLE_MATIERE` per matière". No new role.

### D2 — `isUnrestricted()` is split into two questions, because it currently answers the wrong one

It conflates *"is this caller above the évaluateur ownership rule?"* with *"may this caller touch
this matière?"*. Those separate:

- `isUnrestricted()` — keeps its current meaning (skip the **évaluateur** ownership check). Correct
  for a responsable acting inside their own matière.
- **new** `checkMatiereAccess(examenId)` — the matière predicate that scoring has never had. Resolves
  the exam's `matiereId` and compares it against the caller's scoped authorities.

**`SUPER_ADMIN` passes `checkMatiereAccess` unconditionally *on READS*.** Per D1 the faculty scope
owns no matière, so a matière comparison is meaningless for it — and a predicate that fails closed
would lock the faculty out of its own platform.

⚠️ **Corrigé le 2026-07-31 (Nada).** The sentence above originally said "unconditionally", full stop,
without distinguishing reads from writes. That is too wide and it contradicts D1. **On WRITES the
faculty scope is not admitted by this predicate at all** — see **D5**, which is now the governing
rule. `checkMatiereAccess` is a *read* widener for the faculty scope, never a write authorisation.

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

This is the synchronization contract the responsable is owed.

⚠️ **Corrigé le 2026-07-31 (Nada).** This section originally opened with « a `SUPER_ADMIN` **retains
the technical ability to act** inside a matière (support, unblocking, a jury decision) ». That
phrasing describes a *standing* capability and **contradicts D1**, which forbids the faculty scope
from authoring or grading. It is replaced by **D5**: a faculty write inside a matière is an
**exception with a name**, not a retained ability. D3 is what that exception must satisfy — it is the
alarm on the door, not the reason the door is open.

So: **when** such a write is legitimately performed (per D5's narrow list), it must be:

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

### D5 — The faculty scope READS everywhere and WRITES only its own domain

**Added 2026-07-31.** Nada's framing, and it is the governing rule of this ADR:

> « CRUD utilisateurs, configuration globale, accès à toutes les données, gestion des matières. […]
> why should he be able to create or launch an exam, a super-admin is an administrator not a
> subject's professor? »

The distinction the earlier draft blurred:

| act | faculty scope (`SUPER_ADMIN`) | why |
|---|:--:|---|
| **Read** anything, any matière — exams, results, archives, aggregate analytics | ✅ | *"accès à toutes les données"*. Oversight requires sight. Costs nothing to anyone. |
| **Write** its own domain — accounts, roles, matière catalogue, global config, global grille templates | ✅ | that IS the faculty domain (D1) |
| **Author** — create/edit an exam, a station, a grille, a criterion, a barème | ❌ | **Pedagogical authorship.** Deciding what a correct answer is in *Chimie thérapeutique* requires competence a platform administrator does not have. A permission its holder is unqualified to exercise is a liability, not a capability. |
| **Launch / pause / close** an exam | ❌ | the most consequential act in the system: it freezes the definition (ADR-0015) and puts candidates in front of examiners. If a non-examiner can trigger it, the safeguard is not a safeguard. |
| **Grade / validate / lock** a notation | ❌ | reserved to the examiner who was present (ADR-0013, ADR-0007 — legitimacy comes from the rotation) |
| **Réajuster** a locked note | ❌ | ADR-0013 Part 2 is **responsable-only**; the responsable signs for the barème |

**Access to data is a READ.** Creating and launching are not access to data — they are acts of
authorship, and authorship is what makes a responsable a responsable. That single sentence resolves
every case in the table.

#### The one exception, and it is narrow

Institutional continuity: the responsable is unreachable on exam morning and candidates are waiting.
Someone must be able to act.

That is **not** "the faculty scope is also a responsable". It is a **distinct, exceptional,
individually-named act** — and D3 is exactly what it must satisfy: attributed, announced, never
silent. **One emergency door with an alarm on it, not 71 unmarked ones.**

⚠️ Scope of the gap this reveals, verified 2026-07-31: **72 write endpoints** across the services
have an effective guard naming `SUPER_ADMIN`; exactly **one** is in `auth-service` (his legitimate
domain). The other 71 are pedagogical acts. `ExamenController:36` guards the whole class with
`hasAnyRole('SUPER_ADMIN','RESPONSABLE_MATIERE')`, and `changerStatut` — *launch* — carries no
method-level guard at all, so it inherits it.

**Honest severity: LOW–MEDIUM, and it is a governance gap, not a vulnerability.** There is no
privilege escalation (the faculty scope is already the highest role), and **no screen exists** through
which it could be exercised — the admin zone (`/admin/**`) is **read-only by construction**: since
#390 (2026-09-02) `/admin/examens` and `/admin/examens/:id` render a supervision list and detail
with no write control and no link into the responsable workspace (before that they were stubs).
Reaching these endpoints requires a hand-crafted HTTP request. Per the standing lesson that *an
ungated endpoint is not a reachable one*, this must not be filed as CRITICAL. ⚠️ The one reachable
exception is a **compound** SUPER_ADMIN + RESPONSABLE account, which passes `responsableGuard` and
sees every exam in « Mes examens » with workspace links — tracked as #391.

#### Consequence for the use-case diagram

⚠️ **There is no `SUPER_ADMIN --|> RESPONSABLE_MATIERE` generalization.** A generalization on a
use-case diagram asserts « he performs all of those too », which D5 denies. The two are **peers with
different jobs**, not parent and child. The faculty scope's own use cases are the four in the D5
table's row 1–2: manage accounts and roles, manage the matière catalogue, configure the platform, and
**read the data of every matière**. That last one carries « he sees everything » without claiming he
authors anything.

## Consequences

- **#86 becomes implementable** via D2's `checkMatiereAccess`. ⚠️ **Corrigé le 2026-07-31 :** this
  bullet previously read « via D2 + `matiereId` in the snapshot, with no per-request hop » — which
  **contradicts D2's own warning**, added later, that the snapshot is written *write-once at launch*
  and therefore does not exist during authoring, precisely the window #86 leaves open. The source of
  `matiereId` remains the open choice D2 states (ask exam-service, or denormalise at enrolment). A
  reader who followed the old bullet would have implemented the option D2 rejects.
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
