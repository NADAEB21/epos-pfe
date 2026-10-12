# EPOS — production on a Windows PC at the faculty, public through a tunnel

The faculty's server is an ordinary Windows 11 PC (16 GB RAM) on the faculty
network. The faculty cannot give it a public IP or open ports, yet évaluateurs
must reach EPOS from any Wi-Fi network or from mobile data. The PC therefore opens an
**outbound** tunnel, Tailscale Funnel, which gives it a stable public HTTPS
address with no domain, no router change and no inbound port:

```
phone / browser (any network)
   │  https://epos.<tailnet>.ts.net        (TLS terminated by Tailscale)
   ▼
Tailscale Funnel ── outbound connection ── tailscaled (Windows service, unattended)
                                              │ http://localhost:80
                                              ▼
┌──────────────── Docker Desktop (WSL2) on the faculty PC ─────────────────┐
│  web (Caddy, plain HTTP: EPOS_DOMAIN=:80)                                │
│     /api/v1/*, /actuator/health → api-gateway   /ws* → scoring-service   │
│     /  → Angular SPA                                                     │
│  api-gateway ─ discovery ─ auth ─ exam ─ scoring ─ ai-service ─ postgres │
└──────────────────────────────────────────────────────────────────────────┘
```

The application layer is the same as on the VPS/Azure targets
(`docker-compose.prod.yml`, `caddy/Caddyfile`). Only the host scripts differ;
they live in `scripts/windows-server/`:

| Script | Run as | What it does |
|---|---|---|
| `prepare-host.ps1` | admin | No sleep/hibernation, Tunis time zone + time sync, Windows Update active hours / no auto-reboot; checks virtualization, port 80, SMTP 587, outbound HTTPS |
| `new-env.ps1` | user | Writes `C:\EPOS\epos.env` with secrets generated on the PC (the Windows twin of `bootstrap-vps.sh`); never overwrites an existing file |
| `start-epos.ps1` | user | Waits for Docker, `compose up -d` (builds only if images are missing, or with `-Build`), waits for healthy containers **and** a live API route |
| `handover-reset.ps1` | user | Backup, then empty database with one SUPER_ADMIN (port of `handover-reset.sh`) |
| `install-tasks.ps1` | admin | Scheduled tasks `EPOS-Start` (at logon) and `EPOS-Backup` (nightly 23:00, 30-day retention) |
| `nightly-backup.ps1` | (task) | Calls `sauvegarde/backup-epos.ps1`, prunes old folders only after a successful backup |

## Why these choices

- **HTTPS is not optional.** On plain `http://<lan-ip>` the mobile web build
  crashed on login (`crypto.subtle` needs a secure context, faculty test
  2026-07-23). The tunnel provides a real certificate.
- **Caddy serves plain HTTP** (`EPOS_DOMAIN=:80`): TLS ends at Tailscale, so
  Caddy must not try to obtain a Let's Encrypt certificate. The browser
  origin is still `https://epos.<tailnet>.ts.net`, so `CORS_ALLOWED_ORIGINS`
  and `MAIL_RESET_BASE_URL` are set to it explicitly. The gateway sees
  `http://localhost`, so CORS must list the public origin.
- **Login rate limit raised to 100/min** (`LOGIN_RATE_CAPACITY`): behind
  Caddy every request shares one client address, so the default 10/min would
  turn away the 11th évaluateur at exam start. Per-account guessing remains
  bounded by the 3-failure lockout. Per-client keying is follow-up work (#16).
- **The URL is the machine name + the tailnet name.** The release APK has the
  URL baked in, so both are fixed once and never changed.

## One-time preparation (on Nada's PC, the day before)

1. Create the Tailscale account **with the faculty's address
   `eposfphm@gmail.com`** (the client owns it, not a person). In the admin
   console:
   - DNS → enable **MagicDNS** and **HTTPS Certificates**.
   - DNS → **Rename tailnet** → pick the final name now, because it is part of the URL.
   - Access controls → check that the policy contains the `funnel` node
     attribute (new tailnets have it; the first `tailscale funnel` command
     otherwise prints a link that adds it).
2. The URL is `https://epos.<tailnet>.ts.net`. Build the release APK against it:
   ```powershell
   cd epos_mobile
   flutter build apk --release `
     --dart-define=API_BASE_URL=https://epos.<tailnet>.ts.net/api/v1 `
     --dart-define=WS_BASE_URL=https://epos.<tailnet>.ts.net
   ```
3. Rehearse the whole procedure below with the laptop registered as the node
   `epos`, and test from a phone **on mobile data**. Then **free the name**:
   `tailscale funnel reset`, `tailscale logout`, and delete the machine in the
   admin console. Otherwise the faculty PC becomes `epos-1` and the APK points
   at the wrong host.
4. USB kit:
   - installers for Docker Desktop, Git for Windows and Tailscale, plus
     Sysinternals Autologon;
   - the APK;
   - the images, which avoids a 15-minute Maven/npm build on the faculty's
     bandwidth (in Git Bash; `>` in PowerShell 5.1 would corrupt a binary
     stream):
     ```bash
     docker save -o epos-images.tar epos/discovery-server epos/api-gateway epos/auth-service \
       epos/exam-service epos/scoring-service epos/ai-service epos/web postgres:16-alpine
     ```

## Installation on the faculty PC

Order matters: the seeded test accounts (`admin@epos.tn / Admin@1234`, ...)
must be gone **before** the tunnel makes the PC public.

1. **Windows.** Use a dedicated local account for the server.
   1. Open an admin PowerShell and run `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`.
   2. Run `scripts\windows-server\prepare-host.ps1`.
   3. Set up automatic logon with Sysinternals Autologon. Docker Desktop exists
      only inside a signed-in session.
2. **Docker Desktop.**
   1. Install Docker Desktop. If it reports "virtualization not detected"
      although the BIOS has it enabled, run
      `wsl --install --no-distribution`, then
      `bcdedit /set hypervisorlaunchtype Auto`, then reboot.
   2. In Settings, enable **Start Docker Desktop when you sign in**.
   3. In Settings, **disable automatic updates**, so the server never updates
      on its own during an exam.
3. **Code and images.**
   ```powershell
   git clone https://github.com/NADAEB21/epos-pfe C:\EPOS\app
   cd C:\EPOS\app ; git checkout v1.0-faculte
   docker load -i E:\epos-images.tar          # from the USB key
   ```
4. **Configuration and first start** (from `C:\EPOS\app\scripts\windows-server`):
   ```powershell
   .\new-env.ps1 -PublicHost epos.<tailnet>.ts.net -MailUser eposfphm@gmail.com -MailAppPassword "<16-char app password>"
   .\start-epos.ps1
   ```
   `new-env.ps1` refuses to overwrite `C:\EPOS\epos.env`. That file is what
   ties the database to its password, so delete it only together with a fresh
   volume.

   If the first start ever ran **without** a valid env file, Postgres
   initialised its volume without a password. Recover with
   `docker compose -p epos down -v` and start again.
5. **Handover reset** (still local, nothing public yet):
   ```powershell
   .\handover-reset.ps1 -AdminEmail eposfphm@gmail.com -SkipBackup -Yes
   ```
   `-SkipBackup` is only for a brand-new install with nothing to save.
   The account gets the "choose your password" e-mail, so only the faculty
   ever knows the password.
6. **Tunnel** (admin PowerShell):
   ```powershell
   tailscale up --unattended          # sign in as eposfphm@gmail.com
   tailscale set --hostname=epos      # the node name in the URL
   tailscale funnel --bg localhost:80 # persists across reboots
   tailscale funnel status
   ```
7. **Automation:** run `.\install-tasks.ps1` (admin), then
   `Start-ScheduledTask EPOS-Backup` and check that `C:\EPOS\backups` contains 4 dumps.
8. **Acceptance:** from a phone on **mobile data**, check:
   - `https://epos.<tailnet>.ts.net` loads with a valid certificate;
   - web login works;
   - the APK logs in;
   - the mobile dashboard updates live (WebSocket).
9. **Reboot test:** restart the PC and touch nothing. Within about 5 minutes
   the URL must answer again (autologon, then Docker Desktop, then the
   containers and EPOS-Start, with Tailscale running as a service).

## Operating

```powershell
cd C:\EPOS\app\infrastructure
$C = "docker compose --env-file C:\EPOS\epos.env -f docker-compose.prod.yml"
Invoke-Expression "$C ps"
Invoke-Expression "$C logs -f --tail 200 scoring-service"
Invoke-Expression "$C restart exam-service"
```

- Logs of the scripts and scheduled tasks are in `C:\EPOS\logs`.
- **Never update, rebuild or reboot during an exam.** A rebuild restarts every
  service and drops every WebSocket.
- **Update the code:**
  1. Take a backup first (`Start-ScheduledTask EPOS-Backup`).
  2. Run `git pull` in `C:\EPOS\app`.
  3. Run `.\start-epos.ps1 -Build`.
- **Backups** land in `C:\EPOS\backups\<stamp>\` every night. After each exam
  day, copy the latest folder **off the PC** (an encrypted USB key or another
  machine), never to an open share. The dumps hold personal data and password
  hashes.
- **Restore:** `infrastructure\sauvegarde\restore-epos.ps1 -BackupDir C:\EPOS\backups\<stamp> -Force`.
  The production user is `admin` on this PC, which is the script's default.
- **Dead disk:**
  1. Reinstall Windows and Docker Desktop.
  2. Copy back `epos.env`. Keep a copy of it with the backups: without it the
     restored database cannot be opened, and existing tokens break.
  3. Run `start-epos.ps1`, then `restore-epos.ps1 -Force`.
  4. Rejoin Tailscale as `epos`.

## Known limits

- **One PC.** EPOS is only as available as this PC's power, network and
  Windows Update. There is no redundancy. A UPS is the cheapest improvement.
- **Funnel's bandwidth limit.** Funnel traffic is subject to a bandwidth limit
  that Tailscale does not publish. That is ample for EPOS's JSON/STOMP traffic,
  but it is not a contractual service.
- **Better long-term home.** A domain the faculty controls (e.g. `epos.fanch.tn`)
  behind a Cloudflare Tunnel or a VPS (`README-vps.md`). Changing the URL means
  rebuilding the APK.
