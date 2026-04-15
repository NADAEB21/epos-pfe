# EPOS — Auth Service Implementation Brief

## Project Context
EPOS (Evaluation Platform for Operational Skills) — digitalization of pharmacy 
practical exams for Faculté de Pharmacie de Monastir.
Microservices architecture. This is the auth-service (port 8081).

## Tech Stack
- Spring Boot 3.2+, Java 17
- Spring Security + JWT
- PostgreSQL (database: auth_db)
- Spring Cloud (registers with Eureka on port 8761)

## Data Model — Auth Service

### User
- Long id
- String email (unique, used for login)
- String password_hash (bcrypt)
- String nom, prenom
- Boolean is_active (false after 3 failed login attempts)
- DateTime created_at
- NO role field — roles are in UserRole entity

### UserRole (join entity — core of RBAC)
- Long id
- Long user_id (FK → User)
- RoleType role (enum)
- Long matiere_id (nullable — FK ext to exam-service)
  - null = global scope (SUPER_ADMIN)
  - non-null = subject-scoped (RESPONSABLE_MATIERE only)

### RoleType (enum)
- SUPER_ADMIN (matiere_id must always be null)
- RESPONSABLE_MATIERE (matiere_id required)
- EVALUATEUR (matiere_id always null)

### RefreshToken
- Long id
- Long user_id (FK → User)
- String token_hash (store hash, never raw token)
- DateTime expires_at (7 days)
- Boolean revoked
- Rotated on every use

### PasswordResetToken
- Long id
- Long user_id (FK → User)
- String token_hash
- DateTime expires_at (30 minutes)
- Boolean used (single use only)

## JWT Structure
Access token: 24h expiry
Refresh token: 7 days, rotated on use

JWT claims must include:
{
  "sub": "user@email.com",
  "userId": 1,
  "authorities": [
    "ROLE_RESPONSABLE_MATIERE:5",
    "ROLE_EVALUATEUR"
  ]
}

Format: "ROLE_[ROLETYPE]:[matiere_id]" for scoped roles
Format: "ROLE_[ROLETYPE]" for global roles

## Security Rules
- Lock account (is_active = false) after 3 failed login attempts
- Password policy: min 8 chars, 1 uppercase, 1 digit
- Refresh token: if expired/revoked token is presented → revoke 
  entire family (security breach assumed)
- PasswordResetToken: single use, 30 min expiry, 
  invalidate all existing tokens for user on new request

## Delegation Rules (enforced in UserService)
- SUPER_ADMIN: can assign any role, matiere_id always null
- RESPONSABLE_MATIERE: can only assign RESPONSABLE_MATIERE 
  or EVALUATEUR within same matiere_id scope
- EVALUATEUR: no delegation rights

## API Endpoints to Implement
POST   /api/v1/auth/login
POST   /api/v1/auth/refresh
POST   /api/v1/auth/logout
POST   /api/v1/auth/password-reset/request
POST   /api/v1/auth/password-reset/confirm
GET    /api/v1/users (SUPER_ADMIN only)
POST   /api/v1/users (SUPER_ADMIN or scoped RESPONSABLE_MATIERE)
PUT    /api/v1/users/{id}/roles (with delegation constraint check)
DELETE /api/v1/users/{id} (SUPER_ADMIN only)

## Implementation Order
1. Entities (User, UserRole, RefreshToken, PasswordResetToken)
2. Repositories
3. JwtService (generate + validate tokens, build authorities list)
4. UserDetailsService (load user + roles → build authorities)
5. SecurityConfig (filter chain, public vs protected routes)
6. AuthService (login, refresh, logout logic)
7. UserService (CRUD + delegation constraint logic)
8. Controllers
9. Exception handling (custom exceptions + @ControllerAdvice)
10. application.yml configuration

## Package Structure
com.epos.auth
├── config/
│   └── SecurityConfig.java
├── controller/
│   ├── AuthController.java
│   └── UserController.java
├── dto/
│   ├── LoginRequest.java
│   ├── LoginResponse.java
│   ├── RefreshRequest.java
│   └── UserCreateRequest.java
├── entity/
│   ├── User.java
│   ├── UserRole.java
│   ├── RoleType.java
│   ├── RefreshToken.java
│   └── PasswordResetToken.java
├── exception/
│   ├── AccountLockedException.java
│   ├── InvalidTokenException.java
│   ├── UnauthorizedDelegationException.java
│   └── GlobalExceptionHandler.java
├── repository/
│   ├── UserRepository.java
│   ├── UserRoleRepository.java
│   ├── RefreshTokenRepository.java
│   └── PasswordResetTokenRepository.java
└── service/
    ├── AuthService.java
    ├── JwtService.java
    ├── UserService.java
    └── UserDetailsServiceImpl.java

## Notes
- Eureka client: register as "auth-service"
- All responses wrapped in standard ApiResponse<T>
- Use @PreAuthorize with custom authority strings for scope checking
- matiere_id in JWT authority string must be validated server-side 
  on every protected request — do not trust client claims

## AuditLog Implementation

### AuditLog Entity (infrastructure layer — not domain)
- Long id
- Long user_id (who did it)
- AuditAction action (enum)
- String target_entity (e.g. "User", "UserRole")
- Long target_id (which record was affected)
- String detail (JSON string — stores before/after values)
- DateTime timestamp

### AuditAction Enum
LOGIN
LOGOUT
LOGIN_FAILED
ROLE_ASSIGNED
ROLE_REVOKED
PASSWORD_RESET
ACCOUNT_LOCKED
USER_CREATED
USER_DEACTIVATED

### How it's implemented
- AuditLog gets its own entity and repository (AuditLogRepository)
  BUT the repository is only injected into AuditService
- AuditService is the ONLY class that writes to audit log
- AuditService is called explicitly inside AuthService 
  and UserService at critical points — NOT via JPA listener
- Example in AuthService.login():
  if (loginFailed) auditService.log(userId, LOGIN_FAILED, "User", userId, null)
  if (accountLocked) auditService.log(userId, ACCOUNT_LOCKED, ...)
  if (success) auditService.log(userId, LOGIN, ...)

### Where AuditService is called
- AuthService: LOGIN, LOGOUT, LOGIN_FAILED, ACCOUNT_LOCKED
- UserService: USER_CREATED, ROLE_ASSIGNED, ROLE_REVOKED, USER_DEACTIVATED
- AuthService (password reset): PASSWORD_RESET

### Package location
com.epos.auth.audit
├── AuditLog.java (entity)
├── AuditAction.java (enum)
├── AuditLogRepository.java
└── AuditService.java  

## Current Implementation Status (last updated: 14 Apr 2026)

### Auth-Service — IN PROGRESS
✅ Entities (User, UserRole, RefreshToken, PasswordResetToken)
✅ Repositories (with REQUIRES_NEW on incrementFailedAttempts, resetFailedAttempts)
✅ JwtService (JJWT 0.12.6, opaque refresh token, familyId)
✅ UserDetailsServiceImpl
✅ SecurityConfig
✅ AuthService (login rewritten with passwordEncoder.matches())
✅ UserService (delegation logic + AuditService)
✅ Controllers (AuthController, UserController)
✅ GlobalExceptionHandler
✅ application.yml
✅ Docker init.sql (auth_db schema + seed data)
✅ Login working (tested with Postman)
✅ Account lockout triggering correctly

🔧 KNOWN BUG: is_active not persisting to false on lock
   Fix: add lockAccount() to UserRepository with 
   Propagation.REQUIRES_NEW same pattern as incrementFailedAttempts
   Replace user.setIsActive(false); userRepository.save(user);
   with userRepository.lockAccount(user.getId());

### Next Steps
1. Fix lockAccount bug (first thing tomorrow)
2. Test refresh token rotation + breach detection
3. Commit auth-service to GitHub
4. Start exam-service