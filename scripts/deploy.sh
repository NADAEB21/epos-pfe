#!/usr/bin/env bash
# Ship the current working tree to the AWS instance and rebuild the stack.
#
# Run from Git Bash on Windows, or any POSIX shell:
#   ./scripts/deploy.sh
#
# Deliberately NOT `git archive HEAD`: this is a mid-development deployment, so
# it must carry uncommitted edits and untracked files too. `git ls-files -co
# --exclude-standard` is the right set — tracked plus untracked, minus anything
# gitignored (node_modules, target/, .env, ...).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TF_DIR="$REPO_ROOT/infrastructure/terraform"
TERRAFORM="${TERRAFORM:-terraform}"

command -v "$TERRAFORM" >/dev/null 2>&1 || {
  echo "terraform not found. Set TERRAFORM=/path/to/terraform.exe" >&2
  exit 1
}

echo "==> Reading Terraform outputs"
cd "$TF_DIR"
IP="$("$TERRAFORM" output -raw instance_ip)"
DOMAIN="$("$TERRAFORM" output -raw app_domain)"
KEY="$(cd "$TF_DIR" && cd "$(dirname "$("$TERRAFORM" output -raw ssh_key_path)")" && pwd)/$(basename "$("$TERRAFORM" output -raw ssh_key_path)")"

# OpenSSH refuses to use a key it considers world-readable.
chmod 600 "$KEY" 2>/dev/null || true

echo "    instance : $IP"
echo "    domain   : $DOMAIN"

echo "==> Packing working tree (tracked + untracked, excluding gitignored)"
cd "$REPO_ROOT"
TAR="$(mktemp -t epos-deploy-XXXXXX.tar)"
trap 'rm -f "$TAR"' EXIT
git ls-files -co --exclude-standard -z | tar --null -T - -cf "$TAR"
echo "    $(du -h "$TAR" | cut -f1) archive"

echo "==> Uploading"
SSH_OPTS=(-i "$KEY" -o StrictHostKeyChecking=accept-new -o ServerAliveInterval=30)
scp "${SSH_OPTS[@]}" "$TAR" "ec2-user@$IP:/tmp/epos.tar"

echo "==> Rebuilding stack on the instance (first run pulls Maven + npm deps; expect ~10-15 min)"
ssh "${SSH_OPTS[@]}" "ec2-user@$IP" bash -s <<'REMOTE'
set -euo pipefail

# Wait for cloud-init, in case this runs moments after terraform apply.
if [ ! -f /opt/epos/READY ]; then
  echo "    waiting for instance bootstrap to finish..."
  cloud-init status --wait >/dev/null 2>&1 || true
fi

rm -rf /opt/epos/app
mkdir -p /opt/epos/app
tar -xf /tmp/epos.tar -C /opt/epos/app
rm -f /tmp/epos.tar

cd /opt/epos/app/infrastructure
COMPOSE=(docker compose --env-file /opt/epos/epos.env -f docker-compose.prod.yml)
"${COMPOSE[@]}" up -d --build --remove-orphans
echo
"${COMPOSE[@]}" ps
REMOTE

echo
echo "==> Deployed: https://$DOMAIN"
echo "    Certificate issuance takes a few seconds on the very first deploy."
