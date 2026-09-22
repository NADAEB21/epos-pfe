# EPOS — Evaluation Platform for Operational Skills

Digitalization platform for the **Objective Structured Practical Examinations (OSPE)**
of the Faculty of Pharmacy of Monastir: exam design, station grading (offline-capable),
live conduct, and decision support grounded in psychometrics.

## Project Structure

| Path | Contents |
|---|---|
| `microservices/` | Spring Boot 3.2 / Java 17 services — `api-gateway`, `auth-service`, `exam-service`, `scoring-service`, `discovery-server`, `epos-common` |
| `frontend-web/` | Angular 18 PWA — subject lead and administrator dashboard |
| `epos_mobile/` | Flutter application — evaluator station grading, offline-first (ADR-0001) |
| `ai-service/` | Python / FastAPI — psychometric analysis and deliberation support (ADR-0008, ADR-0029) |
| `infrastructure/` | Docker Compose, database initialization, deployment |
| `docs/` | Architecture decision records, requirements catalogue, user guides |
| `scripts/` | Operational scripts (handover reset, AI cohort tooling) |

Only `api-gateway` is exposed to the host (`:8080`). Every other service is reachable
through it and nowhere else.

## How to start

1. Clone the repo.
2. Provision local secrets:
   ```bash
   cp infrastructure/.env.example infrastructure/.env
   # then edit infrastructure/.env and replace the placeholder values
   ```
   `.env` is gitignored — never commit it. The same variables (`DB_USERNAME`,
   `DB_PASSWORD`) are picked up by every microservice's `application.yml` /
   `application.properties` at startup.
3. Go to /infrastructure and run `docker compose up -d`.

> Postgres listens on `127.0.0.1:5432` and pgAdmin on `127.0.0.1:5050` by default —
> both bound to loopback only. For remote access, override the port binding in a
> local `docker-compose.override.yml` rather than editing the committed file.

Full walkthrough, including test execution outside Docker: [`docs/RUNNING_LOCALLY.md`](docs/RUNNING_LOCALLY.md).

## Documentation

| Document | What it covers |
|---|---|
| [`docs/adr/`](docs/adr/) | **32 architecture decision records** (Nygard format). Several carry a `SUPERSEDED` or `LAPSED` marker with a §0 section recording what actually happened. |
| [`docs/besoins-et-cas-utilisation.md`](docs/besoins-et-cas-utilisation.md) | Requirements catalogue — 87 use cases, functional and non-functional requirements |
| [`docs/api-et-protocoles.md`](docs/api-et-protocoles.md) | REST surface per service and the WebSocket/STOMP contract |
| [`docs/etude-cycle-de-vie-du-compte.md`](docs/etude-cycle-de-vie-du-compte.md) | Cross-cutting study — what deactivating an account actually triggers (findings behind ADR-0023) |
| [`docs/guide-utilisateur/`](docs/guide-utilisateur/) | End-user guides — reading indices, results and trends |
| [`docs/ia-bi/`](docs/ia-bi/) | Analysis module — chart component specification and ground-truth fixtures |
| [`docs/exploitation-sauvegarde-et-amorcage.md`](docs/exploitation-sauvegarde-et-amorcage.md) | Operations: backup, restore, bootstrapping |
| [`docs/RUNNING_LOCALLY.md`](docs/RUNNING_LOCALLY.md) | Running the full stack locally |
| [`infrastructure/README-vps.md`](infrastructure/README-vps.md) | VPS deployment runbook |

## Security

### Password hashing
The auth-service uses **bcrypt** with an explicit cost factor of **12** (OWASP-recommended for current hardware, ~250–400 ms per hash). The cost is configurable via:

- `security.bcrypt.cost` in `application.yml`
- `BCRYPT_COST` environment variable (overrides the file)
- `application-test.yml` sets cost `4` for fast CI runs

Revisit the cost factor annually as hardware speeds up. Argon2 (`Argon2PasswordEncoder`) is a future option; migrating would mean switching the bean to `DelegatingPasswordEncoder` and re-hashing existing passwords on next login.
