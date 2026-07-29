# ADR 0019: Concurrent authoring of shared material — optimistic locking is the floor, presence is courtesy

- **Date:** 2026-07-30
- **Status:** **Proposed**
- **Deciders:** Nada (lead architect).
- **Related:** **ADR-0018** (the scopes that make this urgent), **ADR-0015** (definition frozen at
  launch — the sharpest hazard below), ADR-0013 (locked notations change only via réajustement),
  #92 (`@Version` on Notation), #133 (`@Version` on Examen/Station/Grille + 409 UX), #215
  (blank-entity PUT). Code in scope: `Examen`, `Station`, `Grille`, `GrilleItem`, `Notation`,
  `WebSocketConfig`, `WebSocketSecurityConfig`.

## Context

### The platform has NO concurrency control whatsoever

```
grep -rln "@Version" microservices/  →  no match
```

Every write is last-writer-wins, silently. Two responsables of the same matière (which ADR-0018
shows is already representable) editing one grille means one of them loses work and **neither is
told**.

### The sharpest hazard is not a lost edit — it is a frozen half-built grille

ADR-0015 materialises the exam definition into `scoring_db` **write-once at launch**
(`ExamDefinitionSnapshotService` — *"materialises the immutable exam definition … write-once"*).

So: co-responsable A is mid-edit on a station's grille. Co-responsable B, elsewhere in the building,
launches the exam. The snapshot captures the grille **as it is at that instant** — half-built,
possibly with pondérations that do not sum — and it is **immutable for the life of the exam**.
Évaluateurs then grade against it.

That is a permanent, silent, pedagogically material defect produced by two people acting reasonably.
No amount of optimistic locking on the grille prevents it, because **B did not edit the grille** — B
launched.

### Realtime exists, but not where the shared material lives

- STOMP is configured in **scoring-service only** — `enableSimpleBroker("/topic")`, three topics
  (`/topic/stations/{id}/scores`, `/topic/lots/{id}/status`, `/topic/examens/{id}/dashboard`).
- **exam-service has no WebSocket at all** — and exam-service owns stations and grilles, i.e. the
  shared material.
- `WebSocketSecurityConfig` authenticates only the **CONNECT** frame, and deliberately lets
  unauthenticated clients read `/topic/**` because the payloads are *"non sensibles car
  pseudonymisées par etudiantId, pas par nom"*.

**Presence data is the opposite of pseudonymised** — its entire content is *"Professeur X is editing
station Y"*. It cannot ride on the existing topics.

## Decision

### D1 — Optimistic locking (`@Version`) is adopted as the FLOOR. Not optional, not deferrable.

On `Examen`, `Station`, `Grille`, `GrilleItem`, `Notation`.

It is the only measure that converts **silent data loss** into **a question the user can answer**:
a stale write becomes `409 Conflict` + "this was changed while you were editing; here is what changed".

Cheap, local, no infrastructure, no new failure mode. Everything else in this ADR is comfort; this is
correctness.

### D2 — Pessimistic locking is REJECTED

Authoring a grille is a **human-length session** — a responsable builds a barème over an afternoon,
across coffee breaks and a closed laptop lid. A pessimistic DB lock held for that duration means:

- a transaction held open across UI think-time (unacceptable at the DB level), or
- an application-level lease, which is a distributed lock with a TTL — i.e. we would be writing a
  lock manager, plus a "steal the lock" escape hatch for the abandoned-tab case, plus its own UI.

We would inherit every hard problem of pessimistic locking to solve a problem `@Version` already
solves. **Rejected on cost, not on principle.**

### D3 — Presence awareness is adopted as ADVISORY COURTESY, and must never be presented as a lock

*"Professeur Ahmed is editing this grille"* prevents most collisions by making people wait
voluntarily — which is how colleagues actually behave.

⚠️ **It is not a guarantee and must not be worded as one.** A closed laptop, a dropped socket or a
network partition all produce stale presence. If the UI says **« verrouillé par Ahmed »**, people
will trust it and be shocked by a 409. It must say **« Ahmed regarde cette grille en ce moment »** —
information, not permission. The 409 from D1 remains the actual authority.

**Therefore presence ships AFTER `@Version`, never instead of it, and never before it.**

### D4 — Presence lives in exam-service, on an authenticated per-matière destination

Two rejected alternatives:

- **Route presence through scoring** — scoring would have to know about exam *authoring*, inverting
  the ownership that ADR-0006/0015 established. Rejected.
- **Reuse the existing `/topic/**`** — readable unauthenticated by design. Broadcasting who is
  editing what would leak faculty activity to anyone who can open a socket. Rejected.

So: STOMP in exam-service, on a destination scoped to the matière, requiring an authenticated
principal, e.g. `/topic/matieres/{matiereId}/presence`. Duplicating a Spring WebSocket config is
cheap; cross-service coupling and an authorization hole are not.

### D5 — Launch is guarded against the ADR-0015 hazard, and NOT by locking

Because the hazard is B launching while A edits, the guard belongs on **launch**, and it must be a
*fact*, not a lock:

- **Refuse launch while any grille of the exam is incomplete** — pondérations not summing is already
  computed (`ponderationValide`). Launching on an invalid barème should never have been possible.
- **Warn on launch when another responsable is present in this exam** (D3's presence), naming them.
  A warning, not a veto — the responsable may legitimately know their colleague is done.

⚠️ This must **not** become "no launch while someone holds a lock" — that would recreate D2 and hand
a hung browser tab the power to block an exam. Same failure shape as the flag-authority we removed in
ADR-0016.

## Consequences

- **#92 and #133 stop being "nice to have"** and become the prerequisite for surfacing
  co-responsables at all (ADR-0018).
- **A 409 needs a real UX**, not a toast — the user must see *what* changed and choose. Half-built
  conflict UI is worse than none, because people click through it.
- Presence adds a WebSocket dependency to exam-service (new infra there) and an authenticated STOMP
  destination — a genuine, if modest, cost.
- `ponderationValide` gains teeth: it currently informs, and will start refusing.
- **Not solved by this ADR:** two co-responsables making *semantically* incompatible but
  non-conflicting edits (A rewrites station 1's grille while B deletes station 1). `@Version` sees no
  conflict — different rows. That is a domain-invariant problem, and it belongs to whatever validates
  an exam as launchable.

## Explicitly NOT decided here

- Whether a 409 offers automatic merge. Default: **no** — a barème is not mergeable text, and a
  wrong merge is worse than a redone edit.
- Retention/TTL of presence records (they are ephemeral by nature; no persistence intended).
