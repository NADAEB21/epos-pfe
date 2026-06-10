# ADR 0008: AI direction — pivot to psychometrics-grounded exam-quality analytics

- **Date:** 2026-06-11
- **Status:** Accepted
- **Deciders:** Nada (lead architect), with faculty-professor feedback (2026-06-09 meeting)
- **Related:** AI/ML Module epic, BI & Analyses Avancées epic, issue #90
  (results aggregation), issue #135 (post-exam barème/station réajustement),
  ADR 0001 (mobile stack), the synthetic-data strategic decision.

## Context

The original AI scope for EPOS was generic machine learning: **XGBoost
anomaly detection** on grading patterns and **BART/T5 NLP** for personalized
feedback generation. Two problems surfaced at the 2026-06-09 faculty meeting:

1. **One professor found the AI part academically uninteresting** — "ML for
   its own sake" with no clear pedagogical anchor.
2. **The dataset problem.** There is no real historical exam corpus (the
   faculty is small; the platform has only seed/synthetic data). Supervised ML
   trained on synthetic distributions is weak and hard to defend at a jury.

Both XGBoost-anomaly and BART/T5-feedback are **dataset-starved** — they need
volumes of real labelled exam data that do not and will not exist before the
defense.

## Decision

**Keep an AI/analytics track, but pivot its framing** from generic ML to
**exam-quality analytics grounded in psychometrics** (educational measurement
theory).

**Primary (load-bearing for the defense):**
- **Item analysis** — difficulty index (p-value) and discrimination index per
  grille criterion.
- **Point-biserial correlation** — how well each item predicts overall
  performance.
- **Grille reliability** — Cronbach's α (internal consistency of a grille).
- **Score distributions** — per station / per cohort histograms.
- **Station-failure analysis** — which stations the cohort systematically
  fails, with statistical backing.

**Optional stretch (only if time remains after the defense-critical work):**
- **Anomaly detection** (the original XGBoost idea) — light, unsupervised
  flagging of unusual grading patterns. Explicitly kept on the roadmap as a
  later enhancement, not a defense deliverable.
- **NLP feedback generation** (BART/T5) — demoted to optional stretch for the
  same dataset reason.

## Rationale

1. **Kills the dataset problem.** Psychometric indices are computed from the
   exam data the platform *itself* generates (notations, items, scores). No
   external corpus, no synthetic-distribution hand-waving. The analytics are
   correct by construction on whatever real data exists.
2. **Becomes pedagogically load-bearing.** These statistics are exactly what
   justify the new **post-exam barème/station réajustement** flow (#135 / item
   C6): a responsable drops or re-weights a station *because* the discrimination
   index / failure rate says so. The AI track stops being a bolt-on and starts
   driving a real responsable decision — directly answering the "uninteresting"
   critique.
3. **Academically legitimate and jury-defensible.** Item Response Theory,
   classical test theory, Cronbach's α, point-biserial — these are standard
   educational-measurement tools. A pharmacy faculty jury recognizes them.
4. **Honest methodology.** We compute established indices on real platform data
   rather than claiming predictive ML on data we do not have.

## Consequences

- The **Résultats / Analyses-IA** epic is now driven by this pivot: the
  aggregation endpoint (#90) feeds per-student/per-station results, and the
  psychometric layer sits on top of it.
- **#135 (post-exam réajustement)** is explicitly sequenced *after* this
  analytics layer — the responsable needs the failure/discrimination stats to
  justify a barème change.
- XGBoost-anomaly and BART/T5-NLP stories are **not deleted** — they are
  reprioritized to optional-stretch / AI Deepening (post-Sprint-5, Jul 19+),
  with anomaly detection the first stretch item if time allows.
- The synthetic-data ADR still applies to any stretch ML, but the primary track
  no longer depends on it.

## Alternatives considered

- **Drop AI entirely, swap for more UI modules.** Rejected: the AI track is
  Nada's lead workload and the supervisor's standing insistence; the pivot
  preserves it while fixing the substance.
- **Keep XGBoost/NLP as primary, train on synthetic data.** Rejected: the
  dataset problem makes it weak and hard to defend; it was the source of the
  "uninteresting" critique.
