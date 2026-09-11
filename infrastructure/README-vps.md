# EPOS — deployment on a plain VPS (OVH, Ooredoo, any Ubuntu host)

The same application layer as the AWS and Azure recipes (`docker-compose.prod.yml`,
`caddy/Caddyfile`, `scripts/deploy.sh`), without Terraform: the machine is
ordered by hand, bootstrapped once with `scripts/bootstrap-vps.sh`, then
deployed to with `scripts/deploy.sh` exactly like the cloud targets.

```
Internet
   │  :80 (ACME challenge + redirect)  :443
   ▼
┌────────────────────────── VPS, Ubuntu 24.04, ≥ 4 GiB ─────────────────────────┐
│  web (Caddy)  ── /            → Angular SPA (baked into the image)            │
│               ── /api/v1/*    → api-gateway:8080                              │
│               ── /ws*         → scoring-service:8083  (STOMP/SockJS)          │
│               ── /actuator/health → api-gateway:8080  (mobile connectivity)   │
│                                                                               │
│  api-gateway ─ discovery ─ auth ─ exam ─ scoring ─ ai-service ─ postgres      │
│  (all internal, on the epos-network bridge — no host ports)                   │
└───────────────────────────────────────────────────────────────────────────────┘
```

## Sizing

| Offer (OVH fr-tn, 2026-09) | vCores | RAM | Fit |
|---|---|---|---|
| VPS-1 (~15 DT TTC/mois) | 2 | 4 GiB | Runs the stack with the 2 GiB swap the bootstrap adds; builds are slow. Same class as the AWS box that worked. |
| VPS-2 (~29 DT TTC/mois) | 4 | 8 GiB | **Recommended.** Headroom for ai-service and for image builds without swapping. |

Pick **Ubuntu 24.04** as the OS and register an SSH public key at order time
(`~/.ssh/id_ed25519_school.pub` on Nada's PC). OVH creates the login user
`ubuntu` with sudo.

## Hostname

Caddy needs a real DNS name to obtain a Let's Encrypt certificate. Two options:

- **A subdomain of a domain you control** (`epos.fanch.tn`): add an `A` record
  pointing at the VPS IPv4 in the registrar's zone editor. Wait until
  `nslookup epos.fanch.tn` answers with the VPS IP before bootstrapping.
- **No domain:** `epos.<ip-with-dashes>.sslip.io` (e.g. `epos.203-0-113-10.sslip.io`)
  resolves to the IP with no setup. Third-party DNS, fine for a demo.

## First deploy

From the repo root on your PC (Git Bash):

```bash
VPS=203.0.113.10                      # the VPS IPv4
DOMAIN=epos.fanch.tn                  # or epos.203-0-113-10.sslip.io

# 1. Bootstrap once (Docker, swap, /opt/epos/epos.env with generated secrets). ~3 min.
ssh -i ~/.ssh/id_ed25519_school ubuntu@$VPS "sudo EPOS_DOMAIN=$DOMAIN bash -s" < scripts/bootstrap-vps.sh

# 2. Ship the working tree and build the images. First build 10–15 min.
DEPLOY_HOST=$VPS DEPLOY_USER=ubuntu DEPLOY_KEY=~/.ssh/id_ed25519_school bash scripts/deploy.sh
```

Then open `https://$DOMAIN`. Certificate issuance takes a few seconds on the
first request. Login `admin@epos.tn / Admin@1234` (seeded demo accounts — see
the warning below).

## Redeploying after a code change

```bash
DEPLOY_HOST=$VPS DEPLOY_USER=ubuntu DEPLOY_KEY=~/.ssh/id_ed25519_school bash scripts/deploy.sh
```

It ships your **working tree** (tracked + untracked, minus gitignored files and
minus untracked root `*.md` session notes), so nothing needs to be pushed to
GitHub first. Every deploy rebuilds all images and restarts the stack
(1–2 min of downtime, every WebSocket dropped): never deploy mid-exam.

## Pointing the mobile app at this deployment

Release/profile builds only (`kDebugMode` short-circuits the dart-defines):

```bash
flutter build apk --release \
  --dart-define=API_BASE_URL=https://$DOMAIN/api/v1 \
  --dart-define=WS_BASE_URL=https://$DOMAIN
```

A VPS IP and a domain are stable, so this APK stays valid for the life of the
server.

## Secrets

All generated on the machine by the bootstrap and written to
`/opt/epos/epos.env` (mode 600, owned by the deploy user): `JWT_SECRET`
(64 alphanumeric bytes → HS512 in the signer and all verifiers), the Postgres
password, and the internal `ai_reader` / `ai_writer` passwords. Nothing leaves
the box. Re-running the bootstrap keeps an existing env file, so it never
rotates secrets under a live database. Read them with
`ssh ... 'cat /opt/epos/epos.env'`.

## Mail

The env file ships with `MAIL_ENABLED=false`. To send real invitations and
convocations, set `MAIL_USERNAME`, `MAIL_APP_PASSWORD` (a Gmail app password)
and `MAIL_FROM` in `/opt/epos/epos.env`, flip `MAIL_ENABLED=true`, and rerun
`deploy.sh` (or `docker compose ... up -d`). OVH blocks outbound port 25 on
VPS; Gmail on 587 with STARTTLS is unaffected.

## ⚠️ Known exposure: seeded demo credentials

`infrastructure/init-db/init.sql` seeds well-known accounts
(`admin@epos.tn / Admin@1234` and friends). On a public URL these are trivially
guessable. Acceptable for a demo holding no real data; **change them before
putting any real exam data in**, and before circulating the URL beyond the team.

## Firewall

OVH VPS ship with no inbound filtering by default, and the compose stack only
publishes 80 and 443 (everything else stays on the bridge network). Locking
SSH to your IP is worth the two minutes:

```bash
sudo ufw allow from <your-public-ip>/32 to any port 22 proto tcp
sudo ufw allow 80/tcp && sudo ufw allow 443/tcp
sudo ufw enable
```

(`curl https://checkip.amazonaws.com` prints your public IP; re-run the first
line when it changes, or skip the restriction if you deploy from many networks.)

## Operating

```bash
ssh -i ~/.ssh/id_ed25519_school ubuntu@$VPS
cd /opt/epos/app/infrastructure
C="docker compose --env-file /opt/epos/epos.env -f docker-compose.prod.yml"
$C ps
$C logs -f ai-service
$C restart exam-service
```

Backups: OVH's "sauvegarde automatisée 1 jour" snapshots the whole disk daily.
For an application-level dump before risky changes:

```bash
$C exec postgres-db pg_dumpall -U epos > /opt/epos/dump-$(date +%F).sql
```

## Handover to the faculty (wipe everything but one admin)

The development period leaves demo exams, synthetic students and test
accounts in the databases. Before handing the deployment over, reset it to
a blank state with a single SUPER_ADMIN account owned by the faculty:

```bash
# on the VM — backs up the 4 databases first, then recreates the Postgres
# volume, creates the account through the public API and deletes the three
# init.sql test accounts. Refuses to run without --yes.
bash /opt/epos/app/scripts/handover-reset.sh --admin-email eposfphm@gmail.com --yes
```

Without `--admin-password` the account receives the "choose your password"
e-mail (requires `MAIL_ENABLED=true` in `/opt/epos/epos.env`, checked before
anything is destroyed), so nobody but the faculty ever knows the password.
The subject catalogue (`matieres`) is kept; the Caddy volumes (TLS
certificate) are untouched. The pre-reset backup lands in
`/opt/epos/backups/handover-<stamp>/` and restores with
`infrastructure/sauvegarde/restore-epos.ps1` or its bash port.

## Account validation at OVH

OVH frequently asks new accounts for identity documents (ID card, proof of
address) after the first VPS order, and community reports put the validation
anywhere from hours to days. Order as early as possible, answer the document
request the same day, and do not cancel-and-reorder (it restarts the clock).
