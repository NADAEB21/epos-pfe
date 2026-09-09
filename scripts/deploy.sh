#!/usr/bin/env bash
# Ship the current working tree to the cloud VM and rebuild the stack.
#
# Run from Git Bash on Windows, or any POSIX shell:
#   ./scripts/deploy.sh                                        # AWS  (infrastructure/terraform)
#   TF_DIR=infrastructure/terraform-azure ./scripts/deploy.sh  # Azure
#   DEPLOY_HOST=203.0.113.10 DEPLOY_USER=ubuntu ./scripts/deploy.sh   # any VPS
#
# Provider-agnostic: with DEPLOY_HOST set, the target is that machine directly
# (DEPLOY_USER defaults to ubuntu, DEPLOY_KEY to your ssh default keys);
# otherwise IP, hostname, key and login user come from the Terraform outputs of
# whichever directory TF_DIR points at. The remote side is identical everywhere:
# /opt/epos/epos.env written by scripts/bootstrap-vps.sh, docker-compose.prod.yml,
# and a /opt/epos/READY marker.
#
# Deliberately NOT `git archive HEAD`: this is a mid-development deployment, so
# it must carry uncommitted edits and untracked files too. `git ls-files -co
# --exclude-standard` is the right set — tracked plus untracked, minus anything
# gitignored (node_modules, target/, .env, ...).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TF_DIR="${TF_DIR:-$REPO_ROOT/infrastructure/terraform}"
case "$TF_DIR" in /*|[A-Za-z]:*) ;; *) TF_DIR="$REPO_ROOT/$TF_DIR" ;; esac
TERRAFORM="${TERRAFORM:-terraform}"

SSH_OPTS=(-o StrictHostKeyChecking=accept-new -o ServerAliveInterval=30)

if [ -n "${DEPLOY_HOST:-}" ]; then
  echo "==> Direct target (DEPLOY_HOST)"
  IP="$DEPLOY_HOST"
  SSH_USER="${DEPLOY_USER:-ubuntu}"
  KEY="${DEPLOY_KEY:-}"
  DOMAIN="${DEPLOY_DOMAIN:-}"
else
  command -v "$TERRAFORM" >/dev/null 2>&1 || {
    echo "terraform not found. Set TERRAFORM=/path/to/terraform.exe, or DEPLOY_HOST for a plain VPS" >&2
    exit 1
  }

  echo "==> Reading Terraform outputs"
  cd "$TF_DIR"
  IP="$("$TERRAFORM" output -raw instance_ip)"
  DOMAIN="$("$TERRAFORM" output -raw app_domain)"
  # The AWS config predates the ssh_user output; ec2-user is its fixed login.
  SSH_USER="$("$TERRAFORM" output -raw ssh_user 2>/dev/null || echo ec2-user)"
  KEY="$(cd "$TF_DIR" && cd "$(dirname "$("$TERRAFORM" output -raw ssh_key_path)")" && pwd)/$(basename "$("$TERRAFORM" output -raw ssh_key_path)")"
fi

if [ -n "$KEY" ]; then
  # OpenSSH refuses to use a key it considers world-readable.
  chmod 600 "$KEY" 2>/dev/null || true
  SSH_OPTS+=(-i "$KEY")
fi

# A plain VPS has no Terraform output to name the domain; the bootstrap wrote
# it into the env file, so ask the machine.
if [ -z "$DOMAIN" ]; then
  DOMAIN="$(ssh "${SSH_OPTS[@]}" "$SSH_USER@$IP" \
    'grep -s "^EPOS_DOMAIN=" /opt/epos/epos.env | cut -d= -f2-' || true)"
  [ -n "$DOMAIN" ] || {
    echo "no /opt/epos/epos.env on $IP: run scripts/bootstrap-vps.sh there first" >&2
    exit 1
  }
fi

echo "    instance : $SSH_USER@$IP"
echo "    domain   : $DOMAIN"

echo "==> Packing working tree (tracked + untracked, excluding gitignored)"
cd "$REPO_ROOT"
TAR="$(mktemp -t epos-deploy-XXXXXX.tar)"
trap 'rm -f "$TAR"' EXIT
# Untracked *.md at the repo root are personal session notes (NEXT_SESSION*.md,
# audits, handovers) that can carry credentials pasted during a session. They
# are never needed to build or run the stack, so they stay off the server.
# Same for stray __pycache__ dirs. Tracked files are shipped unconditionally.
{
  git ls-files -c -z
  git ls-files -o --exclude-standard -z \
    | tr '\0' '\n' | grep -v -E '^[^/]+\.md$|(^|/)__pycache__/' | tr '\n' '\0'
} | tar --null -T - -cf "$TAR"
echo "    $(du -h "$TAR" | cut -f1) archive"

echo "==> Uploading"
scp "${SSH_OPTS[@]}" "$TAR" "$SSH_USER@$IP:/tmp/epos.tar"

echo "==> Rebuilding stack on the instance (first run pulls Maven + npm deps; expect ~10-15 min)"
ssh "${SSH_OPTS[@]}" "$SSH_USER@$IP" bash -s <<'REMOTE'
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
