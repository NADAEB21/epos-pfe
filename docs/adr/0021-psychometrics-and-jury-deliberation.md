# ADR 0021: Psychometrics data plane and jury deliberation — what ships before the AI, and what rater severity may claim

- **Date:** 2026-07-30
- **Status:** **Proposed**
- **Deciders:** Nada (lead architect).
- **Related:** **ADR-0008** (AI pivot to psychometrics — Accepted; this ADR sequences it and extends
  its index list once, explicitly), **ADR-0013** (audited réajustement — the only sanctioned change
  path), #90 (results aggregation, shipped), #135 (post-exam barème/station réajustement), #136
  (réclamation), #213/V15 (`notations.saisi_par` — the enabler). Code in scope:
  `NotationController.getResultsByExamen`, `NotationAdjustment`, `notations`.

## Context

ADR-0008 is **Accepted** and fixes the analytics direction: item difficulty (p-value), discrimination,
point-biserial, Cronbach's α, score distributions, station-failure analysis — computed from the
platform's own data, and explicitly *"load-bearing for the defense"*. Anomaly detection and NLP
feedback are named **optional stretch**.

What exists: `GET /notations/examen/{examenId}/results` (#90) — per-exam aggregation, one exam at a
time. Nothing cross-matière, nothing per-item, no rater dimension.

**What changed on 2026-07-29 and unlocks this:** `notations.saisi_par` (V15). Before it, "who graded
this student" was *derived* from the station's owner and was wrong whenever anyone else wrote. **Rater
analytics were not merely unbuilt — they were impossible, and any figure computed then would have been
confidently mis-attributed.**

## Decision

### D1 — Rater severity/leniency is admitted to the index list, ONCE and explicitly

ADR-0008's primary list has no rater dimension; the nearest item is anomaly detection (stretch). This
ADR adds one index, on its own merits:

**Rater severity is standard OSCE psychometrics** (rater harshness/leniency, the classical precursor
to many-facet Rasch). It sits with Cronbach's α as *instrument quality*, not with XGBoost as
*prediction*. It therefore extends ADR-0008 rather than reversing it — and this paragraph exists so
that nobody later reads it as scope creep back toward the abandoned generic-ML framing.

### D2 — ⚠️ The comparison is WITHIN-STATION, never a global average. This is the load-bearing constraint.

A naive "average grade per évaluateur, ranked" is **statistically invalid and professionally unsafe**.
Severity is confounded with the cohort: an examiner who happened to receive a weaker group scores
lower and looks harsh. Publishing that inside a faculty would defame a colleague with a number.

Valid comparison requires **overlap**: the same grille, the same station, comparable cohorts —
i.e. compare évaluateurs who graded *the same station of the same exam*, which the OSCE circuit
structure produces naturally (several évaluateurs, one station each, the same cohort rotating).

**Refusal is part of the contract:** where overlap is insufficient, the platform must display
*"comparaison non concluante — effectif insuffisant"*, never a rank. An index that declines to answer
is the credible one.

### D3 — Descriptive, never punitive, and never automatic

The output is a **signal to a human deliberation**, not a correction. No score is ever adjusted by a
severity index automatically. Any resulting change goes through **ADR-0013's audited réajustement**
with a `motif` — the single sanctioned path, responsable-only (Nada, 2026-07-29). No new write path.

### D4 — The jury deliberation screen ships BEFORE any statistics

This is the sequencing answer. A deliberation screen needs, in order:

1. per-station score distributions — computable now from `notations` + `notation_items`;
2. failure concentration per station — same data;
3. the per-critère breakdown — already exposed (`/notation-items/notation/{id}`);
4. the réajustement action with a motif — already shipped (ADR-0013 Part 2);
5. *then* the psychometric indices, as extra columns.

So **steps 1–4 have zero AI dependency and zero new write surface.** A jury can deliberate on
distributions and failure rates — which is what juries did before psychometrics existed. The indices
make the same screen sharper; they are not its prerequisite.

⚠️ Corollary: **do not block the deliberation UI on the analytics module.** That coupling is what made
the AI track look like a bolt-on in the first place (ADR-0008 §Rationale 2).

### D5 — Cross-matière analytics belong to the FACULTY scope, and inherit its boundary

Comparing across matières is a `SUPER_ADMIN` capability (ADR-0018 D1). It must be **aggregate-first**:
a responsable of matière 1 must not gain a per-student view of matière 2 through an analytics screen.
Given ADR-0018's finding that **scoring has no matière predicate at all** (#86), analytics endpoints
must not ship before that predicate exists — otherwise the BI layer becomes the widest data leak in
the platform.

## Consequences

- A read model is needed. Computing α or point-biserial by walking `notations` per request will not
  hold; a materialised per-exam statistics table (recomputed on closure, or on demand) is the shape.
  Deliberately **not** designed here.
- **The indices are only as honest as `saisi_par` coverage.** Notations written before V15 carry
  `NULL` — deliberately not back-filled (ADR/PR #269), because inventing an author would poison
  exactly this analysis. Rater analytics must therefore **exclude** unattributed notations and say so.
- #135 acquires its justification: a station is dropped or re-weighted *because* an index says so,
  recorded with a motif.
- Sample sizes in a single pharmacy cohort (tens, not thousands) mean wide confidence intervals.
  **Indices must ship with their uncertainty**, or a jury will over-read a difference that is noise.

## Explicitly NOT decided here

- Where the computation runs (Python/FastAPI service per the AI epic, or in scoring). The read-model
  contract matters more than the runtime, and the runtime choice can follow.
- Whether item-level authorship (`NotationItem`) is needed. Currently `saisi_par` is per-notation and
  records the **last** writer (ADR-0017 consequence); after a substitution a shared notation names
  only the substitute. Acceptable for now; revisit if per-critère rater analysis is ever required.
