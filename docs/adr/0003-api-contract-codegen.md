# ADR 0003: API contract — OpenAPI single-source + codegen

- **Date:** 2026-05-13
- **Status:** ⛔ **LAPSED — never adopted** (marked 2026-08-01; original status read
  « Proposed — ratify end of Sprint 2 once gateway lands »)
- **Deciders:** Nada (lead architect), Feten, Aziz
- **Related:** ADR-0001 (mobile stack), issue #13 (gateway)

## §0 — ⚠️ Ce qui s'est réellement passé (ajouté le 2026-08-01)

**Rien de cet ADR n'a été construit.** Vérifié contre le dépôt : pas de
`docs/openapi/epos.yaml`, pas de répertoire `generated/` dans `frontend-web` ni dans
`epos_mobile`, aucune étape openapi-generator en CI, pas de `springdoc` dans le pom du
gateway. Le point de ratification (« end of Sprint 2 ») est passé depuis deux mois et
les deux clients ont été écrits **à la main**.

**La dérive que cet ADR prédisait s'est produite**, et a été payée au prix qu'il annonçait :
les DTO snake_case de scoring-service ont imposé des modèles frontend calqués à la main, et
le champ `ouvertA` → colonne `ouverta` a coûté une session de débogage (PR #258). Ces
corrections ont été faites manuellement, au cas par cas.

Cet ADR reste dans le corpus comme trace du compromis assumé : à l'échelle d'un PFE à deux
clients et un seul développeur backend, le coût d'installation du codegen n'a jamais trouvé
sa fenêtre. **Ne pas le « reprendre » sans re-décider** — si le besoin revient (équipe
élargie, contrat qui casse en production), écrire un ADR successeur qui cite les incidents
ci-dessus comme données d'entrée.

## Context
EPOS now has two frontend clients (Flutter mobile, Angular PWA web) consuming the same backend microservices (auth, exam, scoring). Hand-maintaining DTOs in both **Dart** and **TypeScript** is a silent-drift hazard: a backend field rename can ship green if neither client validates against a contract.

The api-gateway is still empty (#13). Sprint 2 will implement routing + JWT filter — that's the natural point to add a consolidated OpenAPI surface.

## Decision

- The api-gateway exposes a consolidated **OpenAPI 3.0** spec at `/v3/api-docs/all`, aggregated from each backend service's `springdoc-openapi` output.
- The spec is committed to `docs/openapi/epos.yaml` and is the **source of truth** for client contracts.
- Codegen runs in CI on every PR:
  - `openapi-generator-cli` with the **dart-dio** template → `frontend-mobile/lib/api/generated/`
  - `openapi-typescript` → `frontend-web/src/api/generated/`
- CI fails if generated code is stale (regen produces a diff).
- Generated code is **committed** so reviewers see contract changes inline in the same PR that changed the backend annotation.

## Alternatives considered

- **Hand-written types in both clients** — cheaper to set up, free to drift. Rejected.
- **gRPC / Protobuf** — better contract guarantees but adds gateway complexity not justified at PFE scale.
- **Per-service OpenAPI specs without aggregation** — works but forces each frontend to import 3+ packages and manually wire base URLs. Reject for cohesion.

## Consequences

**Positive:**
- Backend field renames break the client build at PR-review time, not at exam day.
- Generated diffs make contract changes visible in code review.
- Eliminates 4 future bug categories: type mismatch, missing field, enum value drift, status code drift.

**Negative / cost:**
- One-time setup: spec aggregation in gateway (~2 sprint points) + CI codegen step (~1 point per client).
- Backend devs must keep `springdoc` annotations honest. (Already implied by issue #6 — documentation pass.)
- Slight friction when prototyping: backend change → regen → review the diff. Worth the cost.

## Status — why "Proposed" not "Accepted"
The gateway doesn't exist yet (Sprint 2 work). Ratify this ADR once the gateway story lands and we've prototyped springdoc aggregation. If aggregation turns out to need a different approach (e.g. per-service specs imported separately), revise this ADR instead of working around it.
