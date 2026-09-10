#!/bin/bash
# EPOS host bootstrap for any Ubuntu 22.04 / 24.04 machine: an OVH or Ooredoo
# VPS, or the Azure VM (whose cloud-init wraps this same file, see
# infrastructure/terraform-azure/user_data.sh.tftpl). Run once, as root.
#
# It installs the container runtime, adds swap, and writes /opt/epos/epos.env
# with freshly generated secrets. It deliberately does NOT fetch application
# code: scripts/deploy.sh ships the working tree over SSH afterwards.
#
# Inputs (environment variables):
#   EPOS_DOMAIN        required. Public hostname Caddy obtains the certificate
#                      for (epos.fanch.tn, epos.1-2-3-4.sslip.io, ...). DNS must
#                      already resolve it to this machine.
#   EPOS_ADMIN_USER    login user that runs deployments. Default: the user who
#                      invoked sudo, else "ubuntu". Must already exist.
#   EPOS_DB_USERNAME   Postgres superuser. Default "epos".
#   EPOS_APP_TIMEZONE  ADR-0010 exam clock. Default "Africa/Tunis".
#
# Typical VPS run, from the repo root on your PC:
#   ssh ubuntu@203.0.113.10 'sudo EPOS_DOMAIN=epos.fanch.tn bash -s' < scripts/bootstrap-vps.sh
#
# Re-running is safe: swap and the env file are only created if absent, so a
# second run never rotates the secrets under a live database.
#
# No `set -x` here on purpose: tracing would print the secrets below into the
# terminal or, on Azure, into /var/log/cloud-init-output.log.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

: "${EPOS_DOMAIN:?EPOS_DOMAIN is required (public hostname Caddy gets the certificate for)}"
ADMIN_USER="${EPOS_ADMIN_USER:-${SUDO_USER:-ubuntu}}"
DB_USERNAME="${EPOS_DB_USERNAME:-epos}"
APP_TIMEZONE="${EPOS_APP_TIMEZONE:-Africa/Tunis}"

if [ "$(id -u)" -ne 0 ]; then
  echo "bootstrap-vps.sh must run as root (use sudo)" >&2
  exit 1
fi
id "$ADMIN_USER" >/dev/null 2>&1 || {
  echo "user '$ADMIN_USER' does not exist; set EPOS_ADMIN_USER to the login user" >&2
  exit 1
}

# Ubuntu images run unattended-upgrades on first boot; wait for the dpkg lock
# instead of failing on it.
APT="apt-get -o DPkg::Lock::Timeout=600 -y"

$APT update
$APT install ca-certificates curl gnupg git

# Docker's own repository, not Ubuntu's docker.io: current compose refuses to
# build without buildx >= 0.17, and the root Dockerfile's `RUN --mount=type=cache`
# needs BuildKit regardless. The distro packages lag too far behind.
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
. /etc/os-release
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
  > /etc/apt/sources.list.d/docker.list
$APT update
$APT install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

systemctl enable --now docker
usermod -aG docker "$ADMIN_USER"

# 4 GiB of RAM is enough to *run* the stack but not to build it: the Maven
# reactor and the Angular production build both spike well past what is free.
if [ ! -f /swapfile ]; then
  dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

mkdir -p /opt/epos/app
chown -R "$ADMIN_USER:$ADMIN_USER" /opt/epos

if [ -f /opt/epos/epos.env ]; then
  echo "bootstrap: /opt/epos/epos.env already exists, keeping its secrets"
else
  # Generated here, never leaving the machine. Alphanumeric on purpose: a
  # literal "$" would be swallowed by compose's env interpolation. 64 bytes for
  # the JWT secret makes HmacJwtDecoders.autoSelectByLength pick HS512
  # consistently in the signer (auth-service) and all three verifiers.
  # cut, not head -c: head exits early and the SIGPIPE it sends upstream would
  # count as a pipeline failure under pipefail and abort the whole bootstrap.
  alnum() { openssl rand -base64 96 | tr -dc 'A-Za-z0-9' | cut -c1-"$1"; }
  JWT_SECRET=$(alnum 64)
  DB_PASSWORD=$(alnum 32)

  # Internal-only Postgres roles for ai-service (ai_reader / ai_writer). They
  # never leave the bridge network; init2-ai.sh reads these once, on the first
  # boot of the data volume.
  AI_READER_PASSWORD=$(openssl rand -hex 24)
  AI_WRITER_PASSWORD=$(openssl rand -hex 24)

  # Create the file 600-owned first; `cat >` truncates in place and keeps the
  # mode, so no secret is ever briefly world-readable.
  install -m 600 -o "$ADMIN_USER" -g "$ADMIN_USER" /dev/null /opt/epos/epos.env
  cat > /opt/epos/epos.env <<EOF
POSTGRES_USER=$DB_USERNAME
POSTGRES_PASSWORD=$DB_PASSWORD
POSTGRES_DB=epos_master
DB_USERNAME=$DB_USERNAME
DB_PASSWORD=$DB_PASSWORD
AI_READER_PASSWORD=$AI_READER_PASSWORD
AI_WRITER_PASSWORD=$AI_WRITER_PASSWORD
JWT_SECRET=$JWT_SECRET
EPOS_DOMAIN=$EPOS_DOMAIN
APP_TIMEZONE=$APP_TIMEZONE
CORS_ALLOWED_ORIGINS=https://$EPOS_DOMAIN
MAIL_ENABLED=false
MAIL_SMTP_HOST=smtp.gmail.com
MAIL_SMTP_PORT=587
MAIL_SMTP_AUTH=true
MAIL_SMTP_STARTTLS=true
MAIL_USERNAME=
MAIL_APP_PASSWORD=
MAIL_FROM=noreply@epos.tn
MAIL_RESET_BASE_URL=https://$EPOS_DOMAIN/reset-password
# Messagerie : renseigner MAIL_USERNAME / MAIL_APP_PASSWORD (= MAIL_FROM) puis
# MAIL_ENABLED=true, et relancer le compose (scripts/deploy.sh ou up -d).
# Un seul expediteur pour reset, invitations (#389) et convocations (#227).
# Sans ces cles, les stubs disent « messagerie desactivee ».
EOF
fi

echo "epos: bootstrap complete, awaiting code push" > /opt/epos/READY
echo "bootstrap: done. Domain $EPOS_DOMAIN, deploy user $ADMIN_USER. Next: scripts/deploy.sh"
