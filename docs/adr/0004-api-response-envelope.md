# ADR 0004: Unified `ApiResponse` envelope across services

- **Date:** 2026-05-20 (option 3 realized 2026-05-25)
- **Status:** Accepted (option 3 implemented — see Update below)
- **Deciders:** Nada (lead architect), Feten, Aziz
- **Related:** issue #61, issue #68 (option 3 follow-up), ADR-0003 (API contract codegen), PR #54 (shared-CORS-helper discussion)

## Context

Each backend service shipped its own `ApiResponse<T>` wrapper, and they drifted:

| Service | Path | Fields | Statics |
|---|---|---|---|
| auth-service | `dto/ApiResponse.java` | `success, message, data` | `ok()`, `error()` |
| scoring-service | `dto/ApiResponse.java` | same as auth | same as auth |
| exam-service | `dto/response/ApiResponse.java` | `success, message, data, timestamp` | `success()`, `error()` |

Two problems:

1. **Wire drift.** exam-service emitted a 4th field (`timestamp`) and used different
   factory names (`success()` vs `ok()`). A frontend consuming all three would need
   per-service branching in its response parser.
2. **Silent re-drift.** auth and scoring were byte-for-byte identical with no shared
   module enforcing it — nothing stops the next edit to one from diverging.

## Decision

Standardize on the **auth/scoring shape** (issue #61 option 1):

- Field set is `success, message, data` — **no `timestamp`**.
- Static factories, identical in all three services:
  - `ok(T data)` / `ok(String message)` / `ok(String message, T data)`
  - `error(String message)` / `error(String message, T data)`
- `@JsonInclude(NON_NULL)` so absent fields are omitted from the body.
- exam-service's `ApiResponse` was rewritten to match; its controllers migrated
  `success(...)` → `ok(...)`. The `error(message, data)` 2-arg form (added for the
  #59 validation envelope) is now present in all three services.

## Alternatives considered

- **Option 2 — add `timestamp` to auth/scoring.** Rejected: the HTTP `Date` response
  header already conveys generation time, and `LocalDateTime.now()` inside a DTO
  constructor is a clock-injection / testability wart. Two services would change for
  no client benefit.
- **Option 3 — extract a shared `epos-common` Maven module.** This is the proper
  long-term fix and is the only thing that *structurally* prevents re-drift. Rejected
  **for now** as out of scope for a Sprint 2 head-start: it touches the parent POM,
  every service POM, and CI, and introduces a release-coordination step. Tracked as a
  follow-up issue for Sprint 3+.

## Consequences

**Positive:**
- All three services emit an identical response envelope — the frontend writes one parser.
- `error(message, data)` is uniformly available for validation 400s (field-error maps).

**Negative / cost:**
- auth and scoring remain *copies*, not a shared type — re-drift is still possible until
  the `epos-common` module lands. The follow-up issue exists to close that gap.
- Dropping `timestamp` is a wire change for any exam-service client that read it; no such
  client exists yet (frontend not started), so the cost is zero today.

## Update — 2026-05-25 (option 3 realized via #68)

The `epos-common` Maven module now exists at `microservices/epos-common/`. The
canonical `ApiResponse<T>` lives at `tn.epos.common.dto.ApiResponse`; auth/exam/
scoring all import it and have deleted their local copies. Re-drift is now
*structurally* prevented — a change to the envelope shape happens in one place.

The same PR also folded in the other duplicated cross-service classes that had
accumulated since this ADR landed: `ScopedAuthoritiesConverter` (exam + scoring,
from #58/#46) and the `BusinessException` / `ResourceNotFoundException` pair
(exam + scoring, from #63/#77). The `epos-common` module is now the home for
all genuinely cross-cutting types.
