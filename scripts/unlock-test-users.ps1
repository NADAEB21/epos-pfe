# EPOS — reset the seeded test accounts to a clean state.
#
# After ~3 failed login attempts (e2e-smoke negative case, fat-fingered UI test,
# etc.) the seeded users have is_active=false and the login flow returns 403.
# This script unlocks them in-place against the running postgres container so
# you don't have to wipe postgres_data to re-seed.
#
# Prereqs:
#   - `docker compose up -d` is running in infrastructure/
#
# Usage:
#   ./scripts/unlock-test-users.ps1

$ErrorActionPreference = "Stop"

$container = "epos-postgres"
$sql = @"
UPDATE users
   SET is_active = TRUE, failed_login_attempts = 0
 WHERE email IN ('admin@epos.tn','resp@epos.tn','eval@epos.tn');
SELECT email, is_active, failed_login_attempts FROM users ORDER BY email;
"@

# Run inside the container so we reuse `$POSTGRES_USER` already set there —
# no need to read infrastructure/.env from the host.
$cmd = 'psql -U "$POSTGRES_USER" -d auth_db -v ON_ERROR_STOP=1'
$sql | docker exec -i $container sh -c $cmd
