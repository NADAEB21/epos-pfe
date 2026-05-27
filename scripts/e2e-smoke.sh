#!/usr/bin/env bash
# EPOS — end-to-end smoke test (bash)
#
# Drives the running stack at $BASE_URL (default http://localhost:8080) and
# asserts the documented happy-path + key @PreAuthorize boundaries. Exits 1
# on the first failure. Intended for local dev — CI runs the JUnit/Testcontainers
# equivalent under microservices/integration-tests.
#
# Prereqs:
#   - `docker compose up -d` is running in infrastructure/
#   - JWT_SECRET in .env is the same value the stack started with
#   - `curl` and `jq` on PATH
#
# Usage:
#   ./scripts/e2e-smoke.sh                    # against http://localhost:8080
#   BASE_URL=http://... ./scripts/e2e-smoke.sh

set -u
set -o pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"

if ! command -v jq >/dev/null 2>&1; then
    echo "ERROR: jq is required but not installed (apt-get install jq / brew install jq)" >&2
    exit 2
fi

RED=$'\033[0;31m'
GREEN=$'\033[0;32m'
RESET=$'\033[0m'

failures=0
step_number=0

# pass: $1=name; succeeds if last_status equals expected status (read from caller)
run_step() {
    local name="$1"
    shift
    step_number=$((step_number + 1))
    local label
    label=$(printf "[%02d] %s" "$step_number" "$name")
    local err
    if err=$("$@" 2>&1); then
        printf "%s  %sPASS%s\n" "$label" "$GREEN" "$RESET"
    else
        failures=$((failures + 1))
        printf "%s  %sFAIL%s\n" "$label" "$RED" "$RESET"
        printf "       %s\n" "$err"
    fi
}

# curl_status: outputs HTTP status to stdout and body to a temp file path on
# stderr, so callers can capture both. Args: METHOD PATH [AUTH_TOKEN] [JSON_BODY]
curl_status() {
    local method="$1"
    local path="$2"
    local token="${3:-}"
    local body="${4:-}"
    local args=(-sS -o /tmp/epos-smoke-body -w "%{http_code}" -X "$method" "$BASE_URL$path")
    args+=(-H "Content-Type: application/json")
    if [[ -n "$token" ]]; then
        args+=(-H "Authorization: Bearer $token")
    fi
    if [[ -n "$body" ]]; then
        args+=(-d "$body")
    fi
    curl "${args[@]}"
}

expect_status() {
    local expected="$1"
    local method="$2"
    local path="$3"
    local token="${4:-}"
    local body="${5:-}"
    local status
    status=$(curl_status "$method" "$path" "$token" "$body")
    if [[ "$status" != "$expected" ]]; then
        local body_preview
        body_preview=$(head -c 300 /tmp/epos-smoke-body 2>/dev/null || true)
        echo "HTTP $status (expected $expected) for $method $path — body: $body_preview" >&2
        return 1
    fi
}

get_access_token() {
    local email="$1"
    local password="$2"
    local body
    body=$(printf '{"email":"%s","password":"%s"}' "$email" "$password")
    local status
    status=$(curl_status POST /api/v1/auth/login "" "$body")
    if [[ "$status" != "200" ]]; then
        echo "Login failed for $email — HTTP $status" >&2
        return 1
    fi
    jq -r '.accessToken' /tmp/epos-smoke-body
}

# ── Gateway reachability ────────────────────────────────────────────────────

run_step "Gateway /actuator/health is 200" \
    expect_status 200 GET /actuator/health

# ── Login for each persona ──────────────────────────────────────────────────

ADMIN_TOKEN=""
RESP_TOKEN=""
EVAL_TOKEN=""

step_number=$((step_number + 1))
label=$(printf "[%02d] %s" "$step_number" "admin@epos.tn login → 200 + accessToken")
if ADMIN_TOKEN=$(get_access_token admin@epos.tn 'Admin@1234'); then
    if [[ -n "$ADMIN_TOKEN" && "$ADMIN_TOKEN" != "null" ]]; then
        printf "%s  %sPASS%s\n" "$label" "$GREEN" "$RESET"
    else
        failures=$((failures + 1))
        printf "%s  %sFAIL%s  (empty accessToken)\n" "$label" "$RED" "$RESET"
    fi
else
    failures=$((failures + 1))
    printf "%s  %sFAIL%s\n" "$label" "$RED" "$RESET"
fi

step_number=$((step_number + 1))
label=$(printf "[%02d] %s" "$step_number" "resp@epos.tn login → 200 + accessToken")
if RESP_TOKEN=$(get_access_token resp@epos.tn 'Resp@1234'); then
    if [[ -n "$RESP_TOKEN" && "$RESP_TOKEN" != "null" ]]; then
        printf "%s  %sPASS%s\n" "$label" "$GREEN" "$RESET"
    else
        failures=$((failures + 1))
        printf "%s  %sFAIL%s  (empty accessToken)\n" "$label" "$RED" "$RESET"
    fi
else
    failures=$((failures + 1))
    printf "%s  %sFAIL%s\n" "$label" "$RED" "$RESET"
fi

step_number=$((step_number + 1))
label=$(printf "[%02d] %s" "$step_number" "eval@epos.tn login → 200 + accessToken")
if EVAL_TOKEN=$(get_access_token eval@epos.tn 'Eval@1234'); then
    if [[ -n "$EVAL_TOKEN" && "$EVAL_TOKEN" != "null" ]]; then
        printf "%s  %sPASS%s\n" "$label" "$GREEN" "$RESET"
    else
        failures=$((failures + 1))
        printf "%s  %sFAIL%s  (empty accessToken)\n" "$label" "$RED" "$RESET"
    fi
else
    failures=$((failures + 1))
    printf "%s  %sFAIL%s\n" "$label" "$RED" "$RESET"
fi

run_step "Wrong password → 401" \
    expect_status 401 POST /api/v1/auth/login "" '{"email":"admin@epos.tn","password":"wrong"}'

# ── auth-service: /users RBAC ───────────────────────────────────────────────

run_step "GET /api/v1/users as admin → 200" \
    expect_status 200 GET /api/v1/users "$ADMIN_TOKEN"

run_step "GET /api/v1/users as resp → 200" \
    expect_status 200 GET /api/v1/users "$RESP_TOKEN"

run_step "GET /api/v1/users as eval → 403" \
    expect_status 403 GET /api/v1/users "$EVAL_TOKEN"

run_step "GET /api/v1/users with no token → 401" \
    expect_status 401 GET /api/v1/users ""

# ── auth-service: /matieres ─────────────────────────────────────────────────

run_step "GET /api/v1/matieres as eval → 200 (any authenticated)" \
    expect_status 200 GET /api/v1/matieres "$EVAL_TOKEN"

# ── exam-service: examen create + list ──────────────────────────────────────

EXAMEN_BODY_SCOPED=$(jq -n \
    --arg nom "E2E Smoke Examen $(date +%Y%m%d%H%M%S)" \
    --arg desc "Generated by scripts/e2e-smoke.sh" \
    --arg date "$(date -d '+7 days' +%Y-%m-%d 2>/dev/null || date -v+7d +%Y-%m-%d)" \
    '{nom:$nom, description:$desc, dateExamen:$date, matiereId:1}')

EXAMEN_BODY_OUT_OF_SCOPE=$(echo "$EXAMEN_BODY_SCOPED" | jq '.matiereId = 2 | .nom = "Out of scope examen"')

run_step "POST /api/v1/examens as resp (matiereId=1) → 201" \
    expect_status 201 POST /api/v1/examens "$RESP_TOKEN" "$EXAMEN_BODY_SCOPED"

run_step "POST /api/v1/examens as resp (matiereId=2) → 403 (out of scope)" \
    expect_status 403 POST /api/v1/examens "$RESP_TOKEN" "$EXAMEN_BODY_OUT_OF_SCOPE"

run_step "GET /api/v1/examens as resp → 200" \
    expect_status 200 GET /api/v1/examens "$RESP_TOKEN"

run_step "GET /api/v1/examens as eval → 403 (class-level @PreAuthorize)" \
    expect_status 403 GET /api/v1/examens "$EVAL_TOKEN"

# ── scoring-service: etudiants ──────────────────────────────────────────────

run_step "GET /api/v1/etudiants as admin → 200" \
    expect_status 200 GET /api/v1/etudiants "$ADMIN_TOKEN"

run_step "GET /api/v1/etudiants as eval → 200 (evaluator may read students)" \
    expect_status 200 GET /api/v1/etudiants "$EVAL_TOKEN"

# ── CORS preflight on protected path ────────────────────────────────────────

step_number=$((step_number + 1))
label=$(printf "[%02d] %s" "$step_number" "OPTIONS preflight from http://localhost:4200 → 200 + Allow-Origin")
preflight_resp=$(curl -sS -i -o /tmp/epos-smoke-headers -w "%{http_code}" \
    -X OPTIONS "$BASE_URL/api/v1/users" \
    -H "Origin: http://localhost:4200" \
    -H "Access-Control-Request-Method: GET" \
    -H "Access-Control-Request-Headers: authorization,content-type" || true)
if [[ "$preflight_resp" != "200" ]]; then
    failures=$((failures + 1))
    printf "%s  %sFAIL%s  (status %s)\n" "$label" "$RED" "$RESET" "$preflight_resp"
elif ! grep -i "^Access-Control-Allow-Origin:[[:space:]]*http://localhost:4200" /tmp/epos-smoke-headers >/dev/null; then
    failures=$((failures + 1))
    printf "%s  %sFAIL%s  (missing/incorrect Access-Control-Allow-Origin)\n" "$label" "$RED" "$RESET"
else
    printf "%s  %sPASS%s\n" "$label" "$GREEN" "$RESET"
fi

# ── Summary ─────────────────────────────────────────────────────────────────

echo ""
if [[ "$failures" -eq 0 ]]; then
    printf "%sAll %d steps passed.%s\n" "$GREEN" "$step_number" "$RESET"
    exit 0
else
    printf "%s%d of %d steps failed.%s\n" "$RED" "$failures" "$step_number" "$RESET"
    exit 1
fi
