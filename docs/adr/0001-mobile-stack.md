# ADR 0001: Mobile stack — Flutter for évaluateur app

- **Date:** 2026-05-13
- **Status:** Accepted
- **Deciders:** Nada (lead architect), Feten, Aziz

## Context
The évaluateur (exam grader) needs a tablet/phone app for scoring students at exam stations during the practical pharmacy exam at Faculté de Pharmacie de Monastir. The cahier de charge explicitly mandates **Flutter** for this app.

Beyond the mandate, mobile-native offline guarantees are critical:

- Exam-day wifi in clinical-skills rooms is unreliable.
- Score data must survive device shutdown (battery drain, accidental drop, OS crash).
- Sensitive material (auth tokens, grading data) needs OS-level secure storage, not browser local storage.

An earlier draft of the backlog framed the mobile app as an Angular PWA. That was an internal mistake corrected after a teammate flag-up — it did not match the cahier de charge.

## Decision
Build the évaluateur mobile app in **Flutter**, targeting **Android primarily** and **iOS where feasible** within the timeline.

Concrete choices:

| Concern | Choice |
|---|---|
| Auth token persistence | `flutter_secure_storage` (Android Keystore / iOS Keychain) for refresh token; access token in-memory only, regenerated via refresh on cold start |
| Local data store | SQLite via `sqflite` + **SQLCipher** for at-rest encryption |
| Sync queue | Durable SQLite table; exponential backoff, max 5 retries |
| HTTP client | `dio` with interceptors for JWT injection and refresh-on-401 |
| Testing | `flutter_test` (unit + widget) and `integration_test` (critical path, airplane-mode toggled) |
| Auto-logout | 30 min inactivity, enforced even offline |

## Alternatives considered

- **Angular PWA on mobile** *(prior framing)*: service-worker offline is thinner — no true background process, IndexedDB is plaintext unless app-encrypted, tab-close pauses sync, no Keystore-equivalent. Did not satisfy cahier de charge.
- **React Native**: not requested by cahier de charge; no team expertise; ecosystem split (Expo vs bare) adds decision cost we don't need.
- **Native Android (Kotlin) only**: faster on-device performance but dual-codebase if iOS is later needed; Flutter buys iOS optionality.

## Consequences

**Positive:**
- True offline-first behavior reachable.
- OS-level secure storage for credentials.
- Single codebase covers Android + (eventually) iOS.

**Negative / cost:**
- Two frontend stacks in the project (Flutter + Angular PWA) → API-contract drift risk. Mitigated by ADR-0003.
- Dart learning curve for the team. Scoped down by limiting **defense-scope mobile work** to: Flutter scaffold + offline auth + one score-entry screen with local SQLite queue. **Full sync engine, conflict-resolution UI, and iOS build are phase-2** (post-2026-09-01).
