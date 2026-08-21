# ADR 0021: Psychometrics data plane and jury deliberation — what ships before the AI, and what rater severity may claim

- **Date:** 2026-07-30
- **Status:** **Accepted** (2026-08-21 — accepted together with **ADR-0029** and **ADR-0030**, which
  close the two questions this ADR explicitly left open: where the computation runs, and the shape of
  the versioned deliberation barème)
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

---

# Part 2 — what "changing the barème after the exam" actually means

Added 2026-07-30, from a working session with Nada. She recalled the supervisor's case — *a cohort
does badly, so the responsable changes the barème afterwards; not the results, not any individual
score* — and then challenged her own premise:

> *« a criterion worth 10 points where many students scored 1s and 0s — what would change if we made
> it worth 5? … it's literally like vacuuming the sea: changes nothing »*

**She is right, and the code makes it stronger than she suspected.**

## The scoring model, stated precisely (it was not written down anywhere)

- **`ponderation`** = the criterion's **points budget** — its share of the station's `noteMax`.
- **`valeurMax`** = the **scale the examiner marks on**, constrained to `≤ ponderation`
  (`ItemEvaluation.isValide():99`, `GrilleServiceImpl:333`).
- **Contribution to the score** (`ExamItemSnapshot.weigh:76-79`):
  - `NUMERIQUE` → **the raw value entered**. `ponderation` does **not** appear in the arithmetic.
  - `BINAIRE` → `valeur × ponderation` (0 or 1 × budget).

Verified live: entered 5 → `score_final` 5; entered 7 → 7, with `ponderation = 10` inert throughout.

So for numeric criteria — the ordinary case — **re-weighting is a literal no-op.** `ponderation`
still matters, but only as the *ceiling* that `valeurMax ≤ ponderation` enforces. It is not
decorative (an earlier draft of this ADR said so; that was wrong), and it is not a multiplier either.

⚠️ **Consequence for #276:** a barème can be declared valid and still be unreachable —
`Σ ponderation == noteMax` while `Σ valeurMax < noteMax`. Verified (grille 76: budgets 10+10 = 20 ✓
valid, marked out of 5+5, best possible mark **10/20**). The launch gate must test the **achievable
maximum**, not the sum of budgets.

## D6 — Two layers, and only one of them may ever change

| layer | what it is | may it change? |
|---|---|---|
| **Judgement** | *"this student scored 5 of 6 on hand hygiene"* — an examiner watched them | **Never.** |
| **Aggregation** | how those judgements become a mark out of 20 | **Yes** — uniformly, with a reason |

This is the distinction that dissolves the confusion, and Nada had already drawn the line herself
(*"not modify results … just change the barème"*). A re-barème touches the **second layer only**.
No raw value is ever rewritten.

## D7 — ⚠️ CORRECTED (Nada, 2026-07-30). The scoring model is sound; only a re-barème needs a scale.

An earlier draft of this section claimed *"nothing normalises against `noteMax`, so an aggregation
layer must be built first"*. **That was wrong, and Nada rejected it on the right grounds:**

> *« why do we need that — 20 is the max mark, not something to divide by. Saying 10/20 is saying I
> got 10 points of 20. I don't need to make it 0.5. »*

Exactly so. A station worth 20 with criteria of 12 + 8, each marked out of its own weight, yields
`9 + 6 = 15` — **already out of 20**. The sum *is* the mark. No division exists because none is
needed.

The earlier claim was a leftover from **before #276 was gated**: when criteria could sum to 10 on a
20-point station, the total genuinely meant nothing. **PR #277 removes that at the source** — the
achievable maximum must equal `noteMax` to launch — so the sum is out of `noteMax` *by construction*.

**What actually remains is much smaller.** Division appears in exactly one situation: a re-barème that
**removes** something. Drop a 5-point criterion from a 20-point station and the exam is now out of 15.
Two honest options, both cheap:

- **report out of 15** — no arithmetic at all, only a different denominator;
- **convert to 20** — one multiplication (`× 20/15`) at presentation.

Note that even option 1 helps the cohort, without touching a single raw value: a student who scored 8
including a 0 on the dropped criterion goes from 8/20 (40%) to 8/15 (53%). **The judgement is
untouched; only what the exam is out of has changed** — which is precisely the D6 split.

**So #276's remaining half is NOT "build an aggregation layer".** It is the much narrower D9 need: a
place to record *what this exam is scored out of after deliberation, and which criteria are excluded*.
A versioned barème, not a rewrite of scoring.

## D8 — Three permitted operations, ranked by how defensible they are

1. **Drop a faulty criterion, then renormalise to `noteMax`.** *Strongest* — "nobody could score on
   it" is an **observation** (p-value ≈ 0, or discrimination ≈ 0). The students it sank recover most,
   which is right, because the fault was the instrument's.
2. **Drop a whole station.** The supervisor's case, and cleanest: a station is a coherent unit — one
   examiner, one task, one thing that can be judged broken.
3. **Re-weight with proportional rescaling.** Permitted, *weakest*. Moving budget alone changes
   nothing (see above); only rescaling each performance to the new budget moves marks. And "criterion
   1 should have counted for more" is a **judgement**, not an observation — it invites the one
   question to avoid in front of a jury: *did you tune it until the pass rate looked acceptable?*

**The line between measurement correction and grade inflation is not the arithmetic.** It is whether
the change follows from a property of the item and applies to everyone identically:

- uniform + reasoned → **measurement correction** ✓
- **per-student → réclamation** (ADR-0013 / #136), a different process requiring proof ✓
- uniform + unreasoned → grade inflation in a lab coat ✗

## D9 — A re-barème is ADDITIVE. ADR-0015 is not weakened.

The frozen snapshot is the record of **how the exam was actually graded**. It stays frozen and
untouched.

A re-barème is a **second artefact** — a deliberation barème applied on top, producing adjusted
results while the original remains auditable. The jury can see both, and the motif for the change.

That is how post-exam adjustment coexists with ADR-0015 instead of fighting it: **nothing is
overwritten, so the write-once promise holds.** A re-barème that edited the snapshot would destroy the
only evidence of what the examiners were actually working from.

Every re-barème carries a `motif`, like réajustement (ADR-0013). Responsable-only.

## D10 — The AI proposes; the human decides

ADR-0008 §Rationale 2 already says the indices exist *to justify* #135. This makes the shape explicit:

> *« Criterion 3 — discrimination 0.02, p-value 0.05: it separated nobody. Suggested: drop and
> renormalise. Projected effect: median 11.2 → 12.8, pass rate 54% → 71%. »*

The responsable **accepts or refuses, with a motif**. The analytics never write a score, never apply a
barème, and never act unattended.

That division is what makes it defensible: it is the difference between *"we computed some
statistics"* and *"the statistics changed a decision, and here is the audit trail."* It also keeps the
projected effect visible **before** the decision — a responsable must not discover the consequence
after committing to it.

⚠️ Per D4, the deliberation screen ships **before** any of this. The proposal engine is an
enhancement to a working screen, never its prerequisite.

## Consequences

- A read model is needed. Computing α or point-biserial by walking `notations` per request will not
  hold; a materialised per-exam statistics table (recomputed on closure, or on demand) is the shape.
  Deliberately **not** designed here.
- **The indices are only as honest as `saisi_par` coverage.** Notations written before V15 carry
  `NULL` — deliberately not back-filled (ADR/PR #269), because inventing an author would poison
  exactly this analysis. Rater analytics must therefore **exclude** unattributed notations and say so.
- #135 acquires its justification: a station is dropped or re-weighted *because* an index says so,
  recorded with a motif.
- **#276's first half is shipped** (PR #277: launch refuses an unreachable barème), and with it the
  sum is out of `noteMax` by construction. What remains is D9's versioned barème — *not* a rewrite of
  the score computation, which works.
- **#135 finally has a definition.** It read "post-exam barème/station réajustement" without ever
  saying what a barème change *does*. D6–D9 supply it — including the answer that plain re-weighting of
  numeric criteria does **nothing at all**, which is why the ticket could never have been implemented
  as written.
- **A barème needs versions.** D9 implies an artefact the schema has no room for: the frozen snapshot,
  plus zero or more deliberation barèmes, each with its motif and author. Deliberately **not** designed
  here — but nothing in D6–D10 is buildable without it.
- Sample sizes in a single pharmacy cohort (tens, not thousands) mean wide confidence intervals.
  **Indices must ship with their uncertainty**, or a jury will over-read a difference that is noise.

## Explicitly NOT decided here

- Where the computation runs (Python/FastAPI service per the AI epic, or in scoring). The read-model
  contract matters more than the runtime, and the runtime choice can follow.
- Whether item-level authorship (`NotationItem`) is needed. Currently `saisi_par` is per-notation and
  records the **last** writer (ADR-0017 consequence); after a substitution a shared notation names
  only the substitute. Acceptable for now; revisit if per-critère rater analysis is ever required.
