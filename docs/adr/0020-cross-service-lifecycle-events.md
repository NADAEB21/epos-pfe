# ADR 0020: Cross-service lifecycle events — a transactional outbox, in the one direction that exists

- **Date:** 2026-07-30
- **Status:** **Proposed**
- **Deciders:** Nada (lead architect).
- **Related:** **ADR-0015** (snapshot at launch; establishes there is no exam→scoring call path),
  ADR-0006 (matière as logical cross-service FK), **#249** (deleted exams leave orphans;
  `invalidateExam` has no caller), #241 (unbounded fail-open on the évaluateur board), #64 (audit),
  ADR-0018 (faculty acts must be announced — needs a transport). Code in scope:
  `ExamenServiceImpl.changerStatut`, `ExamServiceClient`, `ExamDefinitionSnapshotService`,
  `StationServiceImpl.affecterEvaluateurs`.

## Context — the dependency is one-way, and that is a deliberate design, not an accident

Verified:

- **scoring → exam** exists: `ExamServiceClient` (timing, stations, items, `getExamForGeneration`).
- **exam → scoring does NOT exist.** `StationServiceImpl.affecterEvaluateurs` contains no reference to
  scoring, a WebClient, or rotations — confirmed while writing ADR-0017: reassigning a station's
  évaluateur never reaches the rotations that froze it.

ADR-0015 relies on that asymmetry. Introducing a synchronous exam→scoring call would create a cycle
between two services that already call each other's data owner, and would make **exam-service
unavailable whenever scoring is down** — including for `changerStatut`, i.e. launching an exam.

### What the missing direction actually costs

| symptom | verified |
|---|---|
| **#249** — deleting an exam leaves its lots, rotations and snapshots behind; the évaluateur board serves "ghost sessions" from exams that no longer exist | `invalidateExam` exists and has **no caller** |
| **#241** — the board's fail-open (correct in itself) has no bound, so orphaned data has nothing to prune it | `buildDashboard:102-114` keeps an exam whose status is unknown |
| **ADR-0018 D3** — a faculty act inside a matière must reach the responsable | no transport exists at all |

These are three faces of one gap: **exam-service changes lifecycle state and nothing downstream ever
hears about it.**

## Decision

### D1 — A transactional outbox in exam-service. No broker.

`exam_outbox (id, aggregate_type, aggregate_id, event_type, payload, occurred_at, consumed_at)`,
written **in the same local transaction** as the state change it describes.

That single property is the whole point: the event cannot exist without the state change, and the
state change cannot commit without the event. No distributed transaction, no two-phase commit, no
Kafka, no Redis (#137 stays out of scope), no new operational surface for a faculty PC that a
technician has to keep alive.

Events to emit (minimal, lifecycle only):

- `EXAMEN_SUPPRIME` — the #249 fix
- `EXAMEN_STATUT_CHANGE` — carries the new status
- `STATION_EVALUATEURS_MODIFIES` — the reassignment that ADR-0017 showed never propagates
- `ACTE_FACULTAIRE` — a `SUPER_ADMIN` write inside a matière (ADR-0018 D3)

### D2 — Scoring CONSUMES by polling. Eventually consistent, and that is correct here.

A scheduled reader in scoring drains unconsumed rows and applies them: invalidate the snapshot, mark
lots orphaned, drop ghost sessions, deliver the notification.

**Why polling rather than push:** it preserves the one-way dependency (scoring already calls exam,
so it keeps doing exactly that), it needs no broker, and if scoring is down the events simply queue —
nothing is lost and exam-service is never blocked.

**Why eventual consistency is right:** every consumer here is *cleanup* or *notification*. None is on
a path where a human waits. "The ghost session disappears within a minute" is a complete answer;
"deleting an exam fails because scoring is restarting" is not.

### D3 — Not every event crosses the boundary. Most should not.

The user-visible rule: **an event crosses only when another service owns data that becomes wrong
otherwise.**

- Renaming an exam, editing a description, adding a station before launch → **no event**. Scoring
  reads what it needs when it needs it, or froze it at launch by design.
- Deleting an exam, changing its status, moving an évaluateur mid-exam → **event**, because scoring
  holds derived state that silently becomes false.

⚠️ Emitting an event per mutation would rebuild a change-data-capture pipeline nobody asked for, and
would put exam-service's internal shape into scoring's contract.

### D4 — Consumption is idempotent, and orphan cleanup is a SOFT act

Each event carries its own id; a consumer that has already applied it must be able to see that and
do nothing. Polling guarantees at-least-once delivery, never exactly-once.

And cleanup **marks** rather than deletes: an orphaned lot becomes flagged, not erased. Grading data
is evidence in a pharmacy exam — an eventually-consistent background job must never be the thing that
destroys a notation. Hard deletion stays a deliberate, authenticated act.

## Consequences

- **#249 becomes fixable** by giving `invalidateExam` the caller it never had — a consumer, not a
  synchronous hook.
- **#241 gains its bound**: the fail-open stays (correct — an unknown status must not empty a board),
  but orphaned data now gets pruned by a real signal instead of accumulating forever.
- **ADR-0018 D3 becomes implementable** — `ACTE_FACULTAIRE` is its transport.
- **New operational surface in exam-service:** one table and one scheduled job in scoring. Both must
  be observable, or a silently stopped poller becomes a slow leak that looks like nothing.
- **The outbox is not an audit trail.** It is a transport with `consumed_at`. #64 remains a separate
  concern; conflating them would give us an audit log that gets drained.

## Explicitly NOT decided here

- Retention/pruning of consumed rows.
- Whether `ACTE_FACULTAIRE` notifications reach the responsable by e-mail (the #227 sender exists and
  could carry it) or in-app only. In-app is the safer default: e-mail is off by default and a
  governance notice must not depend on SMTP being configured.
- Any move to a real broker. Explicitly out of scope while the deployment target is a single faculty
  PC (see the faculty-deployment constraints); revisit only if a second consumer appears.
