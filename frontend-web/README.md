# frontend-web — EPOS web application (Angular 18 PWA)

Dashboard for the **responsable de matière** (subject lead) and the
**super-administrateur**: exam design, student enrolment, live monitoring of an
exam in progress, results, and the psychometric analysis and deliberation screens.

The evaluator does not use this application — station grading happens on the
Flutter mobile app (`epos_mobile/`). The offline contract differs per actor:
deep on mobile, shallow on web (ADR-0002).

## Running

The backend stack must be up first — see [`../docs/RUNNING_LOCALLY.md`](../docs/RUNNING_LOCALLY.md).

```bash
npm ci
ng serve          # http://localhost:4200
```

All API calls go through the gateway on `:8080`. No service is contacted directly.

## Tests

```bash
ng test                  # unit tests (Karma)
ng build --configuration production
```

## Structure

See [`STRUCTURE.md`](STRUCTURE.md) for the folder layout and the conventions used
across features.

## Notes

- Shared chart components are documented in
  [`src/app/shared/graphes/README.md`](src/app/shared/graphes/README.md).
- `scoring-service` exposes **snake_case** fields; the front-end models match that
  spelling deliberately rather than remapping it.
