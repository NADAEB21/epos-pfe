package tn.epos.auth_service.audit;

public enum AuditAction {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    ACCOUNT_LOCKED,
    LOGOUT,
    TOKEN_REFRESHED,
    TOKEN_REUSE_DETECTED,
    PASSWORD_RESET_REQUESTED,
    PASSWORD_RESET_CONFIRMED,
    USER_CREATED,
    USER_DEACTIVATED,
    /** #289 — la desactivation cesse d'etre a sens unique. */
    USER_REACTIVATED,
    ROLE_ASSIGNED,
    ROLE_REVOKED
}
