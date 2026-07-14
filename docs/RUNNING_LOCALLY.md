# Running EPOS locally

This guide is for teammates who **already have the repo cloned** and want to run
the full backend stack on their machine. You may be on `develop`, on your own
feature branch, or sitting on unpushed work — all three cases are covered below.

Everything runs in Docker. You don't need Java, Maven, or Postgres installed
locally to run the stack (you do need them if you want to run tests outside
Docker — see §9).

## 1. Prerequisites

- **Docker Desktop** ≥ 4.x — WSL 2 backend on Windows, ≥ 4 GB RAM allocated
  (Settings → Resources).
- **Git** (you have this; you're reading from a repo).

## 2. Sync with `develop`

`develop` moved significantly on 2026-05-23 — Sprint 1 + Sprint 2 work landed
(api-gateway, discovery-server, Flyway migrations for exam/scoring, unified
`ApiResponse`, scoped-authority JWT handling, full docker-compose). Your local
branch is likely behind by ~50 commits. Pick the case that matches you:

### Case A — you just want to run the latest stable state

```bash
git fetch origin
git checkout develop
git pull
```

### Case B — you have local work on a feature branch

Bring your branch up to date with `develop`:

```bash
git fetch origin
git rebase origin/develop      # cleaner history; preferred if your branch isn't pushed
# OR
git merge origin/develop       # safer if your branch is already on the remote
```

**Heads up — schema migrations.** `exam-service` and `scoring-service` now use
Flyway (`src/main/resources/db/migration/V1__init_*_schema.sql`). If your local
work adds entities or columns, you need to add a `V2__*.sql` migration file —
relying on `ddl-auto` alone will silently diverge from what other teammates and
CI see. If you'd been running with auto-generated tables on the same DB volume
before Flyway was wired, see "Flyway baseline mismatch" in §7.

## 3. Refresh your `.env`

`infrastructure/.env.example` gained new keys this sprint — notably `JWT_SECRET`,
which all backend services **fail-fast** on if missing. Diff your local file
against the template:

```bash
diff infrastructure/.env.example infrastructure/.env
```

For every key that exists in `.env.example` but not in your `.env`, copy it
across and set a value. A minimal working dev set:

```dotenv
POSTGRES_USER=admin
POSTGRES_PASSWORD=pfe_password
POSTGRES_DB=epos_master

PGADMIN_DEFAULT_EMAIL=admin@epos.tn
PGADMIN_DEFAULT_PASSWORD=admin

DB_USERNAME=admin
DB_PASSWORD=pfe_password

# ─── password reset ─────────────────────────────────────────────────────────
MAIL_ENABLED=true
MAIL_FROM=mon-adresse@gmail.com
MAIL_RESET_BASE_URL=http://localhost:4300/reset-password
MAIL_SMTP_HOST=smtp.gmail.com
MAIL_SMTP_PORT=587
MAIL_USERNAME=mon-adresse@gmail.com
MAIL_APP_PASSWORD=abcd efgh ijkl mnop

JWT_SECRET=change_me_to_a_random_secret_of_at_least_32_bytes
```

> **`JWT_SECRET` must be ≥ 32 bytes (256 bits).** The 44-char placeholder above
> satisfies the minimum and is fine for local dev. For anything beyond dev,
> generate a real one: `openssl rand -base64 48`.

`.env` is gitignored — never commit it.

## 4. Rebuild and restart

Service Dockerfiles and the compose layout both changed in Sprint 2. Force a
rebuild so you don't run stale images:

```bash
cd infrastructure
docker compose down           # NOT down -v — keeps your DB
docker compose up -d --build
```

First rebuild takes ~3–5 min; subsequent runs cache.

## 5. Verify

```bash
docker ps
```

Expect **7 containers**, all `Up` and eventually `(healthy)`:

| Container | Host port | Role |
|---|---|---|
| `epos-postgres` | 127.0.0.1:5432 | Postgres 16 (auth_db, exam_db, scoring_db) |
| `epos-pgadmin` | 127.0.0.1:5050 | DB admin UI |
| `epos-discovery-server` | 127.0.0.1:8761 | Eureka |
| `epos-api-gateway` | **0.0.0.0:8080** | The only externally exposed service |
| `epos-auth-service` | (internal) | reachable via gateway |
| `epos-exam-service` | (internal) | reachable via gateway |
| `epos-scoring-service` | (internal) | reachable via gateway |

Smoke-test login through the gateway:

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@epos.tn","password":"Admin@1234"}'
```

Expected: HTTP 200 with `accessToken` + `refreshToken` in the JSON body.

### Seeded test credentials

| Email | Password | Role |
|---|---|---|
| `admin@epos.tn` | `Admin@1234` | SUPER_ADMIN |
| `resp@epos.tn` | `Resp@1234` | RESPONSABLE_MATIERE (matiere_id=1) |
| `eval@epos.tn` | `Eval@1234` | EVALUATEUR |

Seeded by `infrastructure/init-db/` on first run only — survives a normal
`docker compose down`, wiped by `down -v`.

## 6. Useful URLs

- **API (everything goes through the gateway):** <http://localhost:8080/api/v1/...>
- **Eureka dashboard:** <http://localhost:8761>
- **pgAdmin:** <http://localhost:5050> — login `admin@epos.tn` / `admin`, then add server: host `postgres-db`, port `5432`, user/password from `.env`.

## 7. Common issues

### Service exits (1) on startup

Usually a missing env var, most often `JWT_SECRET`:

```bash
docker logs epos-auth-service | grep -iE "JWT_SECRET|IllegalState"
```

Fix: set the var in `infrastructure/.env`, then **force-recreate** the affected
services — Compose does NOT re-read `.env` for containers that already exist:

```bash
docker compose up -d --force-recreate api-gateway auth-service exam-service scoring-service
```

### Flyway baseline mismatch

If you'd been running with `ddl-auto=update` against the same Postgres volume
before Flyway was wired, your DB has tables Flyway doesn't know about. On
startup you'll see something like:
`Found non-empty schema(s) without schema history table`.

Cleanest fix is to drop the affected DB and let Flyway recreate from V1.
Easiest: wipe the whole volume (kills all DBs, including auth):

```bash
docker compose down -v       # ⚠ wipes all DBs — only if you mean it
docker compose up -d
```

If you have local data you care about, do it surgically via pgAdmin: drop only
`exam_db` or `scoring_db`, recreate empty, restart the affected service.

### Old containers from before the Sprint 2 compose rewrite

Leftover containers/networks from the older layout can confuse the new one:

```bash
docker compose down
docker container prune       # removes only stopped containers
docker network prune         # removes unused networks
docker compose up -d --build
```

### Port already in use

Something else on your machine holds `8080`, `5432`, `5050`, or `8761`. Stop
that process or change the host binding in `infrastructure/docker-compose.yml`.

### "My latest migration / code change isn't showing up"

You probably restarted without rebuilding:

```bash
docker compose up -d --build       # rebuilds only what changed
```

If a Flyway migration file you added isn't running, check that its filename
matches the `V<n>__<description>.sql` pattern exactly and that `<n>` is greater
than any already-applied version.

## 8. Stopping and restarting

| Command | Effect |
|---|---|
| `docker compose stop` | Pause everything. Resume with `docker compose start`. |
| `docker compose down` | Remove containers + network. **Keeps your DB data.** Recommended end-of-day cleanup. |
| `docker compose down -v` | Also wipes the Postgres volume. **Destroys all data.** Use only intentionally. |

## 9. Running tests outside Docker (optional)

Only needed if you're editing service code and want fast feedback. Install
**JDK 17** and **Maven 3.9+**, then from the repo root:

```bash
mvn -pl microservices/auth-service test
# or exam-service, scoring-service, api-gateway, discovery-server
```

Not required to run the app — Docker handles everything end-to-end.

---

Questions? Ping the team channel.
