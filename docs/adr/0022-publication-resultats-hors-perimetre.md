# ADR 0022: Résultats — publication to students is OUT OF SCOPE; the system's boundary is closure + procès-verbal

- **Date:** 2026-08-01 (decision taken by Nada on 2026-07-31, during the global use-case
  diagram review)
- **Status:** Accepted
- **Deciders:** Nada (lead architect)
- **Related:** `docs/besoins-et-cas-utilisation.md` §6.2 (the blind spot this ADR closes),
  UC-71 (clôturer), UC-76 (publier — **not retained**), UC-77 (procès-verbal),
  ADR-0016 (closure is derived), ADR-0018 (administrative control plane)

## Context

Nothing in EPOS communicates a result to a candidate. `GET /api/notations/examen/{id}/results`
is guarded `SUPER_ADMIN | RESPONSABLE_MATIERE`; the Étudiant is a **domain actor without an
account** (no endpoint accepts one as principal — catalogue §2.2). The use-case catalogue
flagged this as blind spot §6.2: *« il faut le décider »* — no code, no ADR, no issue.

An early draft of the global use-case diagram carried a « Publier les résultats » bubble
attributed to the responsable de matière. Nada rejected it on domain grounds:

> « the responsable sends the results to the administration and the administration posts the
> results, this is how the normal déroulement would go […] since this is something we have
> never discussed even with the supervisors I assume just leave it out »

Two facts support that reading:

1. **In the faculty's real process, the responsable does not post results.** They *arrêtent*
   their marks and transmit them to the scolarité, which publishes through official channels
   (noticeboard, administrative portal). EPOS has **no scolarité/administration actor**, and
   the super-administrateur is a *technical* platform admin, not the registrar (ADR-0018 D1).
2. **The publication circuit was never arbitrated** with either supervisor. Drawing it on a
   report figure would assert a workflow nobody has agreed to.

## Decision

**Publication of results to students is NOT RETAINED.** The system's downstream boundary is:

- **UC-71 — Clôturer l'épreuve et geler les notes** : the responsable's terminal act inside
  EPOS (closure itself remains *derived*, per ADR-0016).
- **UC-77 — Produire un procès-verbal archivable** : the transmissible artefact. THIS is the
  hand-off to the administration — exactly the document the responsable would carry to the
  scolarité in the paper process. (Still unbuilt; tracked as its own gap, unchanged by this
  ADR.)

The faculty keeps its official publication channels. EPOS records, freezes and hands over;
it does not announce.

Consequently:

- The global use-case diagram carries **no « Publier les résultats » bubble** — the bubble is
  « Clôturer l'épreuve », and the Étudiant's dashed links are limited to « est convoqué » and
  « est évalué » (no « destinataire » link).
- The Étudiant remains **account-less**. This ADR removes the last pending reason to give him
  one.
- UC-76 moves from « ❌ non couvert » to **« non retenu (ADR-0022) »** — a decided exclusion,
  not an omission.
- FN-52 (« les résultats doivent pouvoir être communiqués aux étudiants ») is satisfied
  **outside the system**, via UC-77's artefact.

## What this ADR does NOT decide

- **UC-46 convocations are unaffected.** Sending convocations by e-mail is *pre-exam*
  logistics, already built (PR #266), and stays in scope.
- **UC-77's implementation** (PV format, signature, archival medium) — still open, still ❌.
- Whether a *future* iteration adds a student-facing consultation portal. If the faculty ever
  asks for one, this ADR is the document to supersede; the report may cite it as a
  perspective.

## Consequences

- **Jury defence:** the question « pourquoi l'étudiant ne voit-il pas sa note ? » now has a
  written, positive answer — *choix assumé aligné sur le circuit administratif réel de la
  faculté* — instead of a silence.
- The report's perspectives section may mention a student portal as future work **citing this
  ADR**, never as an oversight.
- Any future issue proposing a « notify students of results » feature must supersede this ADR
  first.
