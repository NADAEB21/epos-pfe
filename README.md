# EPOS - Evaluation Platform for Operational Skills

## Project Structure
- **/microservices**: Spring Boot 3.2 services.
- **/frontend-web**: Angular 17 Dashboard (Admin/Responsable).
- **/frontend-mobile**: Angular 17 PWA (Evaluator).
- **/infrastructure**: Docker Compose & Database configurations.
- **/ai-modules**: Python scripts for XGBoost & NLP.

## How to start
1. Clone the repo.
2. Go to /infrastructure and run `docker-compose up -d`.

## Security

### Password hashing
The auth-service uses **bcrypt** with an explicit cost factor of **12** (OWASP-recommended for current hardware, ~250–400 ms per hash). The cost is configurable via:

- `security.bcrypt.cost` in `application.yml`
- `BCRYPT_COST` environment variable (overrides the file)
- `application-test.yml` sets cost `4` for fast CI runs

Revisit the cost factor annually as hardware speeds up. Argon2 (`Argon2PasswordEncoder`) is a future option; migrating would mean switching the bean to `DelegatingPasswordEncoder` and re-hashing existing passwords on next login.
