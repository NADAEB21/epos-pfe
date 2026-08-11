# ADR 0023: Account deactivation is offboarding — ejection is a different act

- **Date:** 2026-08-02
- **Status:** **Proposed**
- **Deciders:** Nada (lead architect).
- **Related:** **ADR-0018 D5** (the faculty scope reads everywhere, writes only its own domain —
  the doctrine this ADR applies to the account lifecycle), **ADR-0017** (évaluateur substitution —
  the *correct* tool for removing someone from an exam), ADR-0014 / ADR-0014-A (the clock may impose
  a floor, never a ceiling — the reasoning reused in D4), ADR-0007 (the évaluateur's authority comes
  from the rotation), #185/#280 (the pre-flight doctrine: the precondition shows BEFORE the click),
  #265 (a human cannot serve two live exams — the engagement calculation reused here), #288, #289.
  Code in scope: `UserService.deactivateUser`, `UserController:119-124`,
  `JwtAuthenticationFilter:45-77`, `ExamenServiceImpl.calculerConflitsEvaluateurs`,
  `personnes.component` (web).

## Context

The « personnes » screen (S5, merged 2026-08-02) put a **Désactiver** button in front of a human
being for the first time. The endpoint it calls is old; the act was never designed. An adversarial
audit the same day produced two findings (#288, #289) — and the first framing of both was wrong,
because it asked a security question before asking the domain question.

### What deactivation does today, verified

| fact | evidence |
|---|---|
| `is_active=false` and **all refresh tokens revoked** | `UserService.java:234-245`; live: refresh after deactivation → 401 « Token reuse detected » |
| the **access token keeps working** — up to 24 h | `JwtAuthenticationFilter.java:45-77` rebuilds the principal from the signed token alone: no DB read, no `is_active` check. Live: `GET /auth/me` → **200** after deactivation |
| **anyone** holding `SUPER_ADMIN` may deactivate **anyone**, including themselves | `UserController.java:119-124` — the only guard is `hasAuthority('ROLE_SUPER_ADMIN')`. Live: scrap admin self-`DELETE` → 200 |
| **there is no reactivation** | no endpoint exists; recovery is SQL |
| a deactivated user is still returned by `?role=EVALUATEUR` | `UserRepository.java:23-24` — no `is_active` predicate; feeds the station picker (#287) |

### The three situations that actually produce a deactivation

1. **Offboarding** — a teacher leaves the faculty; an external practitioner invited for one exam day
   no longer needs access. Administrative, future-facing, **never urgent**: nobody is waiting.
2. **Housekeeping** — a duplicate or mistaken account (see #285: a casing typo forks an identity).
   Also not urgent.
3. **Incident** — compromised credentials, or a dispute where someone must be out *now*. Rare, and
   genuinely urgent.

**#288's original framing collapsed all three into « revocation must be immediate ».** That is the
textbook answer and it is wrong here, for a reason the project has already learned once.

### Why « make it immediate » would break the exam

An évaluateur grades from a phone, at a station, with four candidates in front of them and a
half-filled grille. The remedies a generic hardening pass would reach for — a 15-minute access token,
or a deny-list checked on every request — **log that examiner out mid-station**, on faculty Wi-Fi
that already drops. That is a mechanism deciding on its own that someone's session is over: the exact
shape of the clock-as-ceiling model ADR-0014 retired.

So the 24-hour window is **not uniformly a defect**. For offboarding it is harmless (nobody is
waiting) and on exam day it is accidentally protective. It is a defect only for case 3.

**One act carrying three meanings is the flaw — not the token lifetime.**

### And the admin should not be able to do this whenever they like

Deactivating an évaluateur who is grading *right now* has direct pedagogical consequences —
ungraded candidates, a station without an examiner — that are invisible from the admin's screen.
Under **ADR-0018 D5** that is the faculty scope reaching into a matière: an act whose harm lands on
someone else's exam. « Access to data is a READ » resolved authorship; the same reasoning applies
here — *ending someone's ability to work is not administration when the work is in progress.*

### The wrong tool problem

The exam-day emergency people will reach for this button with — « this examiner didn't show up / must
be replaced » — is **not an account act at all**. It is a station reassignment, owned by the
responsable, and **ADR-0017** already specifies it (cases A/B need no feature; case C is the real
one). Today the dangerous door is the only one labelled.

## Decision

### D1 — Two named acts, not one

| act | who | when | effect on live sessions | guard |
|---|---|---|---|---|
| **« Retirer l'accès »** (offboarding) — the default | `SUPER_ADMIN` | the person is leaving / the account is a mistake | **deferred** — no reconnection possible; an open session may run out naturally | **refused while the person is engaged in a live or imminent exam** (D3) |
| **« Révoquer immédiatement »** (incident) — the exception | `SUPER_ADMIN` | credentials compromised, dispute | **immediate** — sessions die | requires a **stated reason**, is **attributed and audited** (ADR-0018 D3), and warns explicitly when it will interrupt someone mid-exam |

The second act is the *only* one #288's remedy must serve. Building it does not require shortening
anyone's token: it is a targeted revocation, not a global policy change.

### D2 — Deactivation is never the way to remove someone from an exam

Removing an évaluateur from an exam is **station reassignment / substitution (ADR-0017)**, a
responsable act. The account lifecycle and the exam roster are different concerns and must have
different doors. The « personnes » screen must not become the exam-day emergency tool — when the
refusal in D3 fires, it must *name the right door* rather than merely blocking.

### D3 — Offboarding pre-flights on live engagement, and says so BEFORE the click

A person is **engaged** when they are an évaluateur on a station of an `EN_COURS` exam, or of an exam
whose launch day is today. Offboarding such a person is **refused**, nominatively:

> « Sonia Karoui examine actuellement dans « Chimie thérapeutique — session 2 ». Retirez-la de ses
> stations (onglet Stations & grilles) ou attendez la fin de l'épreuve. »

This is the **#185/#280 pre-flight doctrine applied to the account lifecycle**: the precondition is
visible before the click, not discovered as a red error afterwards. The engagement question is
already computed for #265 (`calculerConflitsEvaluateurs` intersects a matière's évaluateurs with
every other `EN_COURS` exam) — the same query, keyed by user instead of exam.

### D4 — Where the guard lives: the open architectural choice

⚠️ **This is the part that cannot be hand-waved.** The guard needs a fact `auth-service` cannot
obtain: **auth-service calls nobody** (verified — no `RestTemplate`, `WebClient` or `FeignClient`
anywhere in it). It is the bottom layer; every service depends on it. Making auth call exam-service
would invert the dependency and couple identity to the exam plane's availability.

Three candidate placements, and **this ADR does not pick between them**:

1. **Screen-side pre-flight only.** `exam-service` exposes « is this person engaged? »; the web
   screen asks before offering the button. Honest and cheap — but leaves **no authoritative backend
   guard**, which this project explicitly dislikes (« la garde autoritaire reste `changerStatut` »).
   Acceptable *only* because the blast radius is an administrative act by the highest role, not an
   escalation path.
2. **Orchestrate above auth.** The deactivation act moves to a caller that may legitimately consult
   both planes (the gateway, or a small admin façade). Correct layering, real new surface.
3. **Event-driven engagement flag.** exam-service publishes engagement transitions; auth keeps a
   local `engaged_until` it can check without a hop — the ADR-0020 direction. No inversion, no hop;
   costs the eventing infrastructure ADR-0020 has not yet delivered.

**Whichever is chosen, the failure direction is fixed: if engagement cannot be determined, the
offboarding is REFUSED, not admitted.** Deferring an administrative act costs a day; ejecting an
examiner mid-station costs an exam. This is the same fail-closed reasoning as ADR-0018 D2, and the
opposite of the évaluateur board's deliberate fail-open (#241) — because the direction of harm is
opposite.

### D5 — Guards the faculty scope must have on itself

Independent of D4, and implementable **entirely inside auth-service today**:

- **No self-deactivation.** The acting user may not deactivate their own account.
- **No last-admin deactivation.** Refuse when the target is the only remaining active `SUPER_ADMIN`.
- **Reactivation must exist.** Offboarding without a way back is a one-way door with no handle: the
  same `is_active=false` is *also* set by the 3-strikes brute-force lockout
  (`UserRepository.lockAccount`), so today a teacher who mistypes their password three times needs
  SQL to get back in. ⚠️ The two states are **indistinguishable in the data** — reactivation is the
  point where that ambiguity must be resolved (distinct columns, or a reason on the deactivation),
  not papered over.

### D6 — Session semantics stated honestly, in the product

Until #288's incident act exists, the UI must not promise what the backend does not do. « ses
sessions en cours seront fermées » was **false**; the text now states the real behaviour. Any screen
asserting a backend guarantee must be provably true or reworded.

## Consequences

- **#288 is re-scoped**: not « shorten the token » but « build the immediate-revocation act, and
  leave offboarding deferred ». Blanket token shortening is explicitly **rejected** here — it would
  trade an exam-day failure for an administrative nicety.
- **#289 splits**: the self / last-admin guards (D5) are local, small and can ship immediately; the
  engagement guard (D3) waits on D4's placement decision.
- **#287 gains its rationale**: a deactivated évaluateur must disappear from the assignment picker,
  because D2 says the roster and the account lifecycle are separate concerns — a person who cannot
  log in must not be assignable.
- **ADR-0017 gains a pointer**: the substitution path is now also the documented answer to « remove
  this person from the exam », so the account screen can name it in its refusal.
- **The engagement endpoint is reusable**: « what is this person currently engaged in? » also serves
  the future offboarding checklist (« ses examens à venir »), and the admin's people screen.

## Alternatives considered

- **Kill sessions on deactivation, always.** Rejected: it makes the account plane able to interrupt
  an exam, and the exam is the thing the platform exists to protect.
- **Short access tokens for everyone (15–30 min).** Rejected as a response to *this* problem — it
  punishes the évaluateur on a weak network to solve an administrative concern. It may still be
  revisited on its own merits under Security Hardening Phase 2, where it belongs, with a silent
  refresh strategy designed for exam-day conditions.
- **Cascade: deactivating a person auto-removes them from their stations.** Rejected: that is a
  faculty scope performing a pedagogical write (ADR-0018 D5), silently, on someone else's exam.
  The refusal in D3 hands the decision back to the responsable, which is where it belongs.

## Explicitly NOT decided here

- **Where the engagement guard lives** (D4's three options) — needs the ADR-0020 eventing decision
  to mature, or an explicit acceptance of the screen-side-only pre-flight.
- **What distinguishes an administrative deactivation from a brute-force lockout in the data** —
  raised by D5's reactivation requirement; belongs with #255/the auth hardening pass.
- **Whether offboarding should also close the person's *future* assignments** (a leaving teacher
  assigned to next month's exam). Deliberately deferred: it is a responsable-facing cleanup flow,
  not an account act.
- **Provenance** (`created_by` on accounts, acting user in the audit trail) — the ADR-0018 D3
  dependency, still unbuilt (#64).
