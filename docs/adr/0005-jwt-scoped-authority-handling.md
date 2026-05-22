# ADR 0005: Resource-server handling of scoped JWT authorities

- **Date:** 2026-05-22
- **Status:** Accepted
- **Deciders:** Nada (lead architect), Feten, Aziz
- **Related:** issue #46, issue #58 (exam-service), issue #68 (`epos-common` extraction)

## Context

auth-service issues access tokens whose `authorities` claim mixes two forms:

| Form | Example | Meaning |
|---|---|---|
| Global | `ROLE_SUPER_ADMIN`, `ROLE_EVALUATEUR` | role applies workspace-wide |
| Scoped | `ROLE_RESPONSABLE_MATIERE:5` | role applies only to `matiere_id = 5` |

The `:<matiere_id>` suffix is the RBAC scope (see `CLAUDE.md` → JWT Structure).

exam-service and scoring-service are OAuth2 resource servers. Both originally
wrapped every claim string **verbatim** in a `SimpleGrantedAuthority`. Spring's
`hasRole('RESPONSABLE_MATIERE')` matches an authority named exactly
`ROLE_RESPONSABLE_MATIERE` — so the scoped `ROLE_RESPONSABLE_MATIERE:5` never
matched, and **every RESPONSABLE_MATIERE user got a 403 on both services**
(issue #46). `SUPER_ADMIN`/`EVALUATEUR` smoke tests masked it — their authorities
are global. auth-service itself was unaffected: its `UserController` already
guards with a SpEL `startsWith('ROLE_RESPONSABLE_MATIERE')` filter.

## Decision

Each resource server expands authorities at the JWT-conversion boundary, via a
`ScopedAuthoritiesConverter` wired into the `JwtAuthenticationConverter`. For a
scoped authority it grants **both**:

- the **bare role** — `ROLE_RESPONSABLE_MATIERE` — so `hasRole(...)` / `hasAnyRole(...)` work with no controller changes;
- the **scoped form** — `ROLE_RESPONSABLE_MATIERE:5` — so per-matiere checks remain possible later.

Global authorities (no `:`) pass through unchanged. The token format emitted by
auth-service is **not changed**.

## Alternatives considered

- **Option A — auth-service emits both a flat role and a `SCOPE_*` authority.**
  Rejected: changes the token wire format, so all three services must be
  upgraded in lockstep or tokens break across a rolling deploy. The scope value
  also moves into a parallel `SCOPE_MATIERE_5` authority, a second convention to
  maintain.
- **Option B — resource servers strip the `:id` suffix.** Rejected: discards the
  matiere scope at the `@PreAuthorize` layer; any future per-matiere guard would
  have to re-parse the raw JWT claim.
- **Chosen — resource-server dual-emit.** Keeps the suffix *and* makes `hasRole`
  work, with zero change to the token format or to controller annotations. The
  decision is local to each resource server.

## Consequences

**Positive:**
- RESPONSABLE_MATIERE users are authorized on exam-service and scoring-service.
- No token-format change — no cross-service deploy coordination.
- The `:matiere_id` scope is still present as an authority, ready for a future
  per-matiere enforcement story (exam-service cannot do this yet — `Examen.matiere`
  is free-text, not a numeric id; tracked separately).

**Negative / cost:**
- `ScopedAuthoritiesConverter` now exists as a **copy** in both exam-service and
  scoring-service. This is the same per-service duplication accepted in ADR-0004;
  it should be folded into the shared `epos-common` module — issue #68.
