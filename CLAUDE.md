# EPOS — Auth Service Implementation Brief

## Project Context
EPOS (Evaluation Platform for Operational Skills) — digitalization 
of pharmacy practical exams for Faculté de Pharmacie de Monastir.
Microservices architecture. This is the auth-service (port 8081).

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
- Long matiere_id (nullable)
  - null = global scope (SUPER_ADMIN, EVALUATEUR)
  - non-null = subject-scoped (RESPONSABLE_MATIERE only)
- @PrePersist: throws IllegalStateException if 
  RESPONSABLE_MATIERE has null matiere_id

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
GET    /api/v1/users                   (SUPER_ADMIN only)
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
│   └── SecurityConfig.java
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

## Current State (April 2026)
### FULLY IMPLEMENTED AND TESTED ✅
- All entities, repositories, services, controllers
- Complete login flow with account lockout
- Refresh token rotation with breach detection
- Logout with full token revocation
- Password reset (request + confirm)
- JWT with scoped authorities
- Delegation constraints in UserService
- Async audit logging
- GlobalExceptionHandler with correct HTTP codes
- AccountLockedException extends RuntimeException (not AuthenticationException)
- Docker init.sql with schema + seed data
- CI/CD: GitHub Actions green, SonarCloud connected
- Branch strategy: feature/* → develop → main

### NEXT PRIORITIES
1. Unit tests (JUnit 5 + Mockito) — Phase 1
2. Security hardening (rate limiting, JWT blacklist, 
   security headers, password history) — Phase 2
3. Integration tests (Testcontainers) — Phase 3
4. Then: exam-service review + merge coordination

## Test Credentials (in auth_db)
- admin@epos.tn / Admin@1234 → SUPER_ADMIN
- resp@epos.tn / Resp@1234 → RESPONSABLE_MATIERE (matiere_id=1)
- eval@epos.tn / Eval@1234 → EVALUATEUR