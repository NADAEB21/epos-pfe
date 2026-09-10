# EPOS — Azure deployment (demo / mid-development)

Single Ubuntu VM running the existing Docker Compose stack, fronted by Caddy
for TLS. Same shape as the AWS recipe in `../terraform/`: the application
layer (`docker-compose.prod.yml`, `caddy/Caddyfile`, `scripts/deploy.sh`) is
shared, only the infrastructure layer differs.

```
Internet
   │  :80 (ACME challenge + redirect)  :443
   ▼
┌────────────────────────── Azure VM Standard_B2s (Ubuntu 24.04) ───────────────┐
│  web (Caddy)  ── /            → Angular SPA (baked into the image)            │
│               ── /api/v1/*    → api-gateway:8080                              │
│               ── /ws*         → scoring-service:8083  (STOMP/SockJS)          │
│               ── /actuator/health → api-gateway:8080  (mobile connectivity)   │
│                                                                               │
│  api-gateway ─ discovery ─ auth ─ exam ─ scoring ─ ai-service ─ postgres      │
│  (all internal, on the epos-network bridge — no host ports)                   │
└───────────────────────────────────────────────────────────────────────────────┘
```

Why this exists: the AWS account was closed on 2026-08-31 and a new one could
not be opened with a Tunisian prepaid card. Azure for Students needs no card
and gives a $100 / 12-month credit, verified through GitHub Education.

## Prerequisites

- **Azure CLI** on the machine running Terraform. On Windows:
  `winget install Microsoft.AzureCLI` (reopen the terminal afterwards), then
  `az login` opens the browser. `az account show` must print the subscription.
- **Terraform ≥ 1.9** (`C:\Users\Nada\terraform\terraform.exe` on Nada's PC).
- The subscription id, in `terraform.tfvars` (copy the `.example`) or as
  `ARM_SUBSCRIPTION_ID`.

## First deploy

```bash
cd infrastructure/terraform-azure
cp terraform.tfvars.example terraform.tfvars   # fill in subscription_id
terraform init
terraform apply

cd ../..
TF_DIR=infrastructure/terraform-azure ./scripts/deploy.sh
```

`terraform apply` creates ~11 resources (resource group, VNet, subnet, static
public IP with DNS label, NSG, NIC, VM, SSH key) and bootstraps the VM via
cloud-init. The bootstrap body is `scripts/bootstrap-vps.sh`, the same script
used for a plain VPS (`../README-vps.md`); `user_data.sh.tftpl` only exports
its inputs and pastes it in. `deploy.sh` ships the working tree and builds the images; the first
build pulls the whole Maven and npm dependency tree, so budget 10–15 minutes.

On Nada's PC, where neither binary is on PATH:

```bash
TERRAFORM=/c/Users/Nada/terraform/terraform.exe TF_DIR=infrastructure/terraform-azure bash scripts/deploy.sh
```

## Redeploying after a code change

```bash
TF_DIR=infrastructure/terraform-azure ./scripts/deploy.sh
```

It ships your **working tree** (tracked + untracked, minus gitignored files and
minus untracked root `*.md` session notes), so you do not have to push to
GitHub to see a change live.

## Hostname and TLS

The static public IP carries an Azure DNS label, so the app lives at
`https://<label>.<location>.cloudapp.azure.com` — Azure-owned DNS, no
third-party resolver such as sslip.io in the path. Caddy obtains a real
Let's Encrypt certificate for it; the Angular PWA service worker and Android's
cleartext block both need that. The label is `epos-<random>` unless you set
`dns_label`.

To move to a real domain later (e.g. `epos.fanch.tn`): point an A record at
`terraform output -raw instance_ip`, set `EPOS_DOMAIN` and
`CORS_ALLOWED_ORIGINS` in `/opt/epos/epos.env` on the VM, redeploy. Nothing
else changes.

## Pointing the mobile app at this deployment

```bash
terraform output -raw mobile_dart_defines
```

Release/profile builds only: `kDebugMode` short-circuits the dart-defines.

## Secrets

Unlike the AWS recipe (SSM SecureString + IAM role), **all secrets are
generated on the VM** by the cloud-init bootstrap with `openssl rand` and
written to `/opt/epos/epos.env` (mode 600): `JWT_SECRET` (64 alphanumeric
bytes → HS512 everywhere), the Postgres password, and the internal
`ai_reader` / `ai_writer` passwords. They never leave the box and nothing in
Terraform state or the Azure portal holds them. Key Vault + managed identity
would buy nothing here: the only consumer is the VM itself, and the Postgres
volume lives on the OS disk, so a replaced VM starts from an empty database
with fresh secrets either way.

Read them when debugging: `ssh ... 'cat /opt/epos/epos.env'`.

`terraform.tfstate` still holds the SSH private key and is gitignored.

## Mail

The env file ships with `MAIL_ENABLED=false`. To send real invitations and
convocations, set `MAIL_USERNAME`, `MAIL_APP_PASSWORD` (a Gmail app password)
and `MAIL_FROM` in `/opt/epos/epos.env` on the VM, flip `MAIL_ENABLED=true`,
and rerun `deploy.sh` (or `docker compose ... up -d`). cloud-init runs once;
the file is never regenerated.

## ⚠️ Known exposure: seeded demo credentials

`infrastructure/init-db/init.sql` seeds well-known accounts
(`admin@epos.tn / Admin@1234` and friends). On a public URL these are trivially
guessable. Acceptable for a demo holding no real data; **change them before
putting any real exam data in**, and before circulating the URL beyond the team.

## Access

```bash
terraform output -raw ssh_command    # SSH (locked to the IP that ran apply)
```

SSH is restricted to the public IP of whoever ran `terraform apply`. If your IP
changes, re-run `terraform apply` (it re-detects) or set `ssh_allowed_cidr`.
There is no Azure equivalent of AWS Session Manager wired in; the portal's
"Run command" on the VM is the emergency door.

## Operating

```bash
cd /opt/epos/app/infrastructure
C="docker compose --env-file /opt/epos/epos.env -f docker-compose.prod.yml"
$C ps
$C logs -f ai-service
$C restart exam-service
```

## Cost — the credit is finite

Azure for Students: **$100 for 12 months, no card, no overage** — when the
credit runs out the subscription is disabled, not billed. `Standard_B2s` costs
about **$30/month** running 24/7 plus ~$2.50 for the 30 GiB disk, so the credit
covers roughly three months continuously. **Deallocate the VM whenever you are
not demoing** — a deallocated VM costs only the disk and the static IP
(~$3/month together), and the IP, hostname and certificate all survive:

```bash
RG=$(terraform output -raw resource_group); VM=$(terraform output -raw vm_name)
az vm deallocate -g "$RG" -n "$VM"      # stop billing compute
az vm start      -g "$RG" -n "$VM"      # ~1 min; containers restart on their own
```

`az vm stop` (without `deallocate`) keeps billing. Check the balance in the
portal: Cost Management → Credits, or
`az consumption budget list` once a budget exists.

The stack needs 1–2 minutes after `start` before the gateway routes are warm
(Eureka registration); probe `/actuator/health` before demoing.

## Troubleshooting first apply

- **"The requested size … is not available in location"** — student
  subscriptions are capacity-restricted in some regions. Set
  `location = "westeurope"` (or `northeurope`) in `terraform.tfvars`.
- **"Operation could not be completed as it results in exceeding approved
  … Cores quota"** — same cause; try another region or `Standard_B1ms` is
  too small, so a region change is the answer.
- **DNS label already taken** — set `dns_label` to something else.
- **`cloud-init status --wait` never returns** — the bootstrap failed; read
  `/var/log/cloud-init-output.log` on the VM.
- **Build OOMs on B2s** — `terraform apply -var vm_size=Standard_B2ms`
  (resizes in place, ~2 min of downtime, disk and IP kept).

## Teardown

```bash
terraform destroy
```

Destroys the whole resource group including the Postgres data. Dump anything
you care about first.
