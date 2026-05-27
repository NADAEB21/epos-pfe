## Verification Discipline (read every session)

Before ANY high-stakes claim, output the verification block below. A "high-stakes claim" includes:
- Assigning severity (CRITICAL / HIGH / MEDIUM / LOW) to an issue
- Asserting code is broken, vulnerable, or incorrect
- Stating that a PR closes or fixes an issue
- Filing or updating a GitHub issue
- Recommending sprint priority changes
- Flagging anything as a security risk

### Required output format

For every high-stakes claim, output this block BEFORE the claim:

[VERIFICATION]
Files opened this session: <list every file path you have actually opened in
THIS conversation. Not files remembered from training or prior sessions.>
Code evidence: <quoted lines with file:line prefixes that support the claim>
Reasoning: <how the quoted code leads to the conclusion>

If you cannot fill this block — if you have not opened the relevant file in
this session — you MUST say:
"I have not verified this against source. Treat this as UNVERIFIED.
Should I open the file now?"

No substitutes allowed. "Based on the ticket description..." or
"matching the pattern of..." is NOT verification. Open the file.

### Severity rule

Never assign CRITICAL or HIGH severity without:
1. Reading the affected file in this session
2. Quoting the exact failing line(s) or missing code
3. Stating concrete blast radius (what breaks, for whom, when)

If any of the three is missing, severity is UNVERIFIED until they are.

### Sub-agent caveat

If you used the Task tool or any sub-agent, its summaries are UNVERIFIED
until YOU personally open the cited files. Sub-agent output is a starting
point for your verification, never the source of truth.

### Default posture

When uncertain, output UNVERIFIED + a clarifying question. The cost of
asking is small. The cost of a falsely-severe ticket propagating into
Notion and sprint planning is large.

### Snapshot decay rule

The "Project Snapshot" table and any "Current State" / "Status" / "Open
Issues" counts in this file are point-in-time. They decay between sessions
as PRs ship to `develop` and `main`. They are NOT ground truth.

Before making any architecture / env / port / URL / dependency / scope
decision based on a snapshot row in this file:

1. Open the relevant code file(s) in THIS session and verify current state.
2. `git log --oneline -20 -- <relevant/path>` to see what shipped since.
3. For any cited issue, `gh issue view <N>` — "open" can mean "fixed on
   develop, awaiting main merge" per the Notion Review-vs-Done convention.
4. For any architecture claim, `gh pr list --state closed --search "<topic>"`
   to find work the snapshot may not reflect.

If the snapshot conflicts with what you observe, trust what you observe and
flag the staleness in your reply. Do not write code or PR bodies that bake
in a snapshot-derived assumption without first re-verifying.

**Past failure this rule exists to prevent (2026-05-26):** scaffolded the
Angular dev env URL at `http://localhost:8081` (auth-service direct) based
on a snapshot row saying api-gateway was an "Empty shell." Reality at the
time: api-gateway had been live on `:8080` for two weeks (PRs #69 + #70,
closed #13 awaiting main-merge auto-close). The frontend would have failed
immediately under the intended `docker compose up` dev flow.

# EPOS — Project Brief (auth-service is primary focus)

## Project Context
EPOS (Evaluation Platform for Operational Skills) — digitalization 
of pharmacy practical exams for Faculté de Pharmacie de Monastir.
Microservices architecture. This file documents the whole project,
with auth-service detailed because it is the most complete service.

## Project Snapshot

**Last refreshed: 2026-05-26.** Per the Snapshot decay rule above, this
table decays — verify any row before relying on it for an architecture
decision. The authoritative source for service ports is
`infrastructure/docker-compose.yml`; for routing, `microservices/api-gateway/src/main/resources/application.yml`.

**Externally reachable from the host: ONLY `api-gateway` on `:8080`.** The
application services have no host port mapping under `docker compose up` —
their internal ports below are listed for context but you cannot hit them
from `localhost` directly. The frontend dev env URL must point at the
gateway.

| Service | Internal port | Status |
|---|---|---|
| discovery-server | 8761 | Dev/prod profiles + actuator health (closed #72 via PR #73). Eureka dashboard exposed on `127.0.0.1:8761` in dev only. |
| api-gateway | 8080 | **Live.** `/api/v1/**` routes for auth, exam, scoring; JWT validation filter; Bucket4j login rate limiting; `X-User-Id`/`X-User-Authorities` propagation (closed #13 via PRs #69 + #70). Only service exposed to the host. |
| auth-service | 8081 | Implemented + tested. CORS env-driven (closed #17 via PR #52). Matiere reference table + matiere_id FK (closed #81 via PR #94). RESPONSABLE_MATIERE can list évaluateurs (closed #80 via PR #93). |
| exam-service | 8082 | Implemented + tested. Per-matière authz on station/grille/template (closed #96 via PR #97). List filter by JWT scope (closed #95 via PR #98). Grille templates + station↔évaluateur link (PR #79). CORS bean (closed #55 via PR #56). |
| scoring-service | 8083 | Implemented + tested. Auth + `@PreAuthorize` (closed #11 via PR #51). GEH + typed exceptions (closed #22 + #63 via PRs #51 + #77). CORS bean (closed #12 via PR #54). Cross-service station/grille IDs on Rotation/Notation (closed #82 + #83 via PR #100). Cross-grille NotationItem validation (closed #84 via PR #101). |
| ai-service | 8084 | Not started (`ai-modules/` exists as empty placeholder). |
| frontend-web | — | Greenfield scaffold in progress as of 2026-05-26 (PR #103 open against develop — Angular 18 + Tailwind 3.4 + PWA + auth flow + app shell + 5 stub routes). |
| frontend-mobile | — | Flutter, owned by Feten (Sprint 3 scaffold work in flight outside this repo Claude's purview). |

**Open issue count is volatile** — run `gh issue list --state open` for the current count; do not trust any number cached here.

**Backlog of record:** Notion workspace "EPOS — Product Backlog" (each entry links back to its GitHub issue). Per the Notion Review-vs-Done convention, GitHub issues stay "open" until `develop → main` auto-closes them, even when the work is shipped on `develop`.

## Tech Stack
- Spring Boot 3.2+, Java 17
- Spring Security + JWT (JJWT 0.12.6)
- PostgreSQL (database: auth_db)
- Spring Cloud (Eureka client, registers as "auth-service")
- Docker Compose for infrastructure

## Data Model — Auth Service

### User
- Long id
- String email (unique, login identifier)
- String password_hash (bcrypt)
- String nom, prenom
- Boolean is_active (false after 3 failed attempts — account locked)
- Integer failed_login_attempts (counter, reset on success)
- DateTime created_at
- NO role field — roles live in UserRole entity

### UserRole (join entity — core of RBAC)
- Long id
- Long user_id (FK → User)
- RoleType role (enum)
- Long matiere_id (nullable, FK → Matiere — DB-level constraint since #81)
  - null = global scope (SUPER_ADMIN, EVALUATEUR)
  - non-null = subject-scoped (RESPONSABLE_MATIERE only)
- @PrePersist: throws IllegalStateException if 
  RESPONSABLE_MATIERE has null matiere_id

### Matiere (reference table, added #81)
- Long id
- String code (unique, e.g. "CHIM_THER")
- String libelle (e.g. "Chimie thérapeutique")
- DateTime created_at
- Single source of truth for the pharmacy-subject catalog.
- exam-service stores `Examen.matiere_id BIGINT` (logical FK across DBs —
  no JPA association, no SQL FK). Same precedent as `station_evaluateurs.evaluateur_id`.
- See ADR-0006 for the cross-service ownership rationale.

### RoleType (enum)
- SUPER_ADMIN (matiere_id always null)
- RESPONSABLE_MATIERE (matiere_id required)
- EVALUATEUR (matiere_id always null)

### RefreshToken
- Long id, Long user_id FK
- String token_hash (SHA-256 of raw opaque token)
- DateTime expires_at (7 days)
- Boolean revoked
- String family_id (UUID — ties rotation chain for breach detection)
- Rotated on every use — old token immediately revoked

### PasswordResetToken
- Long id, Long user_id FK
- String token_hash (SHA-256)
- DateTime expires_at (30 minutes)
- Boolean used (single use only)

## JWT Structure
Access token: 24h expiry
Payload:
{
  "sub": "user@email.com",
  "userId": 1,
  "authorities": ["ROLE_RESPONSABLE_MATIERE:5", "ROLE_EVALUATEUR"]
}
Scoped authority format: "ROLE_[ROLETYPE]:[matiere_id]"
Global authority format: "ROLE_[ROLETYPE]"

## Security Rules (ALL IMPLEMENTED)
- Account locked after 3 failed attempts
  - incrementFailedAttempts() uses REQUIRES_NEW transaction
  - lockAccount() uses REQUIRES_NEW transaction
  - getFailedLoginAttempts() uses scalar JPQL to bypass JPA cache
- Opaque refresh tokens stored as SHA-256 hash
- Token rotation: revoke on use, issue new in same family
- Breach detection: revoked token reused → revokeAllByFamilyId
- Logout: revokeAllByUserId
- Password policy: min 8 chars, 1 uppercase, 1 digit (validated in DTO)
- Delegation: RESPONSABLE_MATIERE can only assign within same matiere_id

## Delegation Rules (enforced in UserService)
- SUPER_ADMIN: can assign any role globally
- RESPONSABLE_MATIERE: can assign RESPONSABLE_MATIERE (same scope) 
  or EVALUATEUR (global) — cannot assign SUPER_ADMIN
- EVALUATEUR: no delegation rights at all

## API Endpoints
POST   /api/v1/auth/login              (public)
POST   /api/v1/auth/refresh            (public)
POST   /api/v1/auth/logout             (authenticated)
POST   /api/v1/auth/password-reset/request  (public)
POST   /api/v1/auth/password-reset/confirm  (public)
GET    /api/v1/matieres                (any authenticated — frontend picker)
GET    /api/v1/users                   (SUPER_ADMIN or RESPONSABLE_MATIERE)
POST   /api/v1/users                   (SUPER_ADMIN or RESPONSABLE_MATIERE)
PUT    /api/v1/users/{id}/roles        (SUPER_ADMIN or RESPONSABLE_MATIERE)
DELETE /api/v1/users/{id}              (SUPER_ADMIN only)

## Package Structure
com.epos.auth_service
├── audit/
│   ├── AuditLog.java
│   ├── AuditAction.java (LOGIN_SUCCESS, LOGIN_FAILURE, LOGOUT,
│   │                     ACCOUNT_LOCKED, TOKEN_REFRESHED,
│   │                     TOKEN_REUSE_DETECTED, PASSWORD_RESET_REQUESTED,
│   │                     PASSWORD_RESET_CONFIRMED, USER_CREATED,
│   │                     ROLE_ASSIGNED, ROLE_REVOKED, USER_DEACTIVATED)
│   ├── AuditLogRepository.java
│   └── AuditService.java (@Async, bounded thread pool)
├── config/
│   ├── SecurityConfig.java
│   ├── JwtAuthenticationFilter.java
│   └── JwtAuthenticationDetails.java
├── controller/
│   ├── AuthController.java
│   └── UserController.java
├── dto/
│   ├── LoginRequest.java
│   ├── LoginResponse.java (accessToken, refreshToken, tokenType)
│   ├── RefreshRequest.java
│   ├── PasswordResetRequestDto.java
│   ├── PasswordResetConfirmDto.java
│   ├── UserCreateRequest.java (password validated by regex)
│   ├── UserResponse.java
│   ├── RoleAssignmentDto.java
│   └── ApiResponse.java (success, data, message wrapper)
├── entity/
│   ├── User.java
│   ├── UserRole.java
│   ├── RoleType.java (enum)
│   ├── RefreshToken.java
│   └── PasswordResetToken.java
├── exception/
│   ├── AccountLockedException.java (extends RuntimeException)
│   ├── InvalidTokenException.java (extends RuntimeException)
│   ├── UnauthorizedDelegationException.java
│   ├── EmailAlreadyExistsException.java
│   ├── UserNotFoundException.java
│   └── GlobalExceptionHandler.java (@RestControllerAdvice)
│       400: MethodArgumentNotValidException
│       401: BadCredentialsException, UsernameNotFoundException,
│            InvalidTokenException
│       403: AccountLockedException, UnauthorizedDelegationException,
│            AccessDeniedException
│       404: UserNotFoundException
│       409: EmailAlreadyExistsException
│       500: Exception (generic, logged at ERROR)
├── repository/
│   ├── UserRepository.java
│   │   - findByEmail, existsByEmail
│   │   - incrementFailedAttempts (REQUIRES_NEW)
│   │   - resetFailedAttempts (REQUIRES_NEW)
│   │   - lockAccount (REQUIRES_NEW)
│   │   - getFailedLoginAttempts (scalar JPQL, cache BYPASS hint)
│   ├── UserRoleRepository.java
│   │   - findByUserId
│   │   - deleteByUserId
│   ├── RefreshTokenRepository.java
│   │   - findByTokenHash
│   │   - revokeAllByUserId
│   │   - revokeAllByFamilyId
│   └── PasswordResetTokenRepository.java
│       - findByTokenHash
│       - invalidateAllByUserId
└── service/
    ├── AuthService.java
    │   login(): find → isActive check → passwordEncoder.matches()
    │           → incrementFailedAttempts → getFailedLoginAttempts
    │           → lockAccount if >=3 → issueTokenPair
    │   refresh(): findByTokenHash → revoked? → breach → rotate
    │   logout(): revokeAllByUserId
    │   requestPasswordReset(): enumeration-safe, invalidates old tokens
    │   confirmPasswordReset(): validates hash/expiry/used → encode → save
    ├── JwtService.java
    │   - generateAccessToken(user, roles) → builds authorities claim
    │   - generateRefreshTokenValue() → SecureRandom 256-bit opaque
    │   - hashToken(raw) → SHA-256 hex
    │   - generateFamilyId() → UUID
    │   - validateToken(token) → boolean
    │   - extractClaims(token) → Claims
    ├── UserService.java
    │   - getAllUsers() (readOnly)
    │   - createUser(request, authentication) → delegation check
    │   - assignRoles(id, roles, authentication) → delegation check
    │   - deactivateUser(id) → soft delete + revoke tokens
    │   - validateDelegation(actingAuth, targetRoles)
    │   - getActingUserScope(auth) → Set<Long> matiereIds
    └── UserDetailsServiceImpl.java
        - loadUserByUsername → load User + UserRole → build authorities

## Important Transaction Notes
- login() is @Transactional — throws unchecked exceptions
- incrementFailedAttempts, resetFailedAttempts, lockAccount 
  all use Propagation.REQUIRES_NEW so they commit independently
  even when the outer login() transaction rolls back on exception
- getFailedLoginAttempts uses scalar JPQL + cache BYPASS hint 
  to avoid reading stale JPA first-level cache after REQUIRES_NEW commit

## Current State (May 2026)

### Auth-service — fully implemented & unit-tested ✅
- All entities, repositories, services, controllers
- Complete login flow with account lockout
- Refresh token rotation with breach detection
- Logout with full token revocation
- Password reset (request + confirm)
- JWT with scoped authorities + JwtAuthenticationFilter wired
- Delegation constraints in UserService
- Async audit logging
- GlobalExceptionHandler with correct HTTP codes
- AccountLockedException extends RuntimeException (not AuthenticationException)
- BCrypt cost factor pinned to 12 (closed #30)

### Infrastructure & CI ✅
- Docker init.sql with schema + seed data for auth_db (exam_db / scoring_db schemas still empty — see #15)
- GitHub Actions matrix green for auth/exam/scoring
- SonarCloud connected (org `nadaeb21`, project `NADAEB21_epos-pfe`)
- Sonar duplicate-report race fixed (closed #27)

### Branch strategy
- Documented flow: `feature/* → develop → main`
- Hotfixes may target `main` directly (e.g. #27, #30 were merged this way to save time)
- After any direct-to-main merge, **resync `develop` from `main`** so the integration
  branch never lags behind production. This is the contract the team must follow
  before the jury looks at `git log --graph --all`.

### NEXT PRIORITIES
The Notion backlog ("EPOS — Product Backlog") is the live source of truth.
Top-level epics, in priority order:
1. **Backend Stabilization** — work the 27 open issues; critical/high first
2. **Infrastructure & Service Mesh** — implement api-gateway routes + JWT filter (#13),
   service-to-service auth, complete docker-compose (#26), populate exam/scoring schemas (#15)
3. **Security Hardening Phase 2** — rate limiting (#16), JWT blacklist on logout,
   security headers (#17 CORS), password history
4. **Frontend Web (Responsable)** — Angular 17+ PWA dashboard
5. **Mobile (Évaluateur)** — Flutter app, Android primary (offline-first scoring, cahier de charge mandate). See `docs/adr/0001-mobile-stack.md`.
6. **AI/ML Module** — XGBoost anomalies + BART/T5 feedback (Python FastAPI)
7. **DevOps & Observability** — Testcontainers integration tests (#28), structured logs, metrics
8. **Documentation & Jury Deliverables** — README (#34), architecture diagrams, methodology trail

## Architecture Decisions
Canonical ADRs live in `docs/adr/`:
- `0001-mobile-stack.md` — Flutter for évaluateur mobile (cahier de charge mandate)
- `0002-offline-contract-per-actor.md` — Per-actor offline depth (deep for Flutter mobile, shallow for Angular PWA web)
- `0003-api-contract-codegen.md` — OpenAPI single-source + Dart/TS codegen (Proposed — ratify end of Sprint 2)

## Test Credentials (in auth_db)
- admin@epos.tn / Admin@1234 → SUPER_ADMIN
- resp@epos.tn / Resp@1234 → RESPONSABLE_MATIERE (matiere_id=1)
- eval@epos.tn / Eval@1234 → EVALUATEUR