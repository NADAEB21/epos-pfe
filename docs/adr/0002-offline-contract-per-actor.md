# ADR 0002: Offline contract per actor

- **Date:** 2026-05-13
- **Status:** Accepted
- **Deciders:** Nada (lead architect), Feten, Aziz
- **Related:** ADR-0001 (mobile stack)

## Context
EPOS has two frontend clients consuming the same backend:

- **Flutter** mobile app for the évaluateur (live exam-day scoring).
- **Angular PWA** web dashboards for the responsable matière and super admin (admin / review / planning).

Both need to behave gracefully when network drops, but the *depth of "offline"* achievable on each stack is fundamentally different. Conflating the two leads to over-promising on the web side.

A hard requirement called out by the team: **session state must survive an unexpected device shutdown** (battery drain, accidental shutdown, OS crash). Both stacks must address this — *differently*.

## Decision

### Évaluateur (Flutter mobile) — deep offline-first

| Aspect | Behavior |
|---|---|
| Auth | Authenticate once; cached session works for the full exam regardless of wifi. |
| Writes | All scoring actions write to local SQLite synchronously; submission queues if offline. |
| Sync | Background sync on reconnect, exponential backoff, max 5 retries. |
| Crash survival | SQLite WAL journal → at most the in-flight keystroke is lost on sudden shutdown. |
| Conflict | If server rejects (e.g. notation locked, per issue #23), entry moves to local "rejected" folder with the server error visible in-app. |
| Security | Encrypted at rest via SQLCipher; tokens in Keystore-backed `flutter_secure_storage`. |

### Responsable / Super Admin (Angular PWA web) — shallow offline + crash recovery

| Aspect | Behavior |
|---|---|
| Shell | Service worker pre-caches app shell so the app loads with no network. |
| Reads | IndexedDB caches recently viewed data for read-only browsing while offline. |
| Long-form writes | Autosave to IndexedDB on every input change, debounced ~300 ms (exam creation, grille editing). |
| Crash recovery | On next open after a crash: detect unfinished drafts, prompt **"Resume draft from <timestamp>?"**. Realistic data-loss window: last few seconds before the crash, not zero. |
| Bulk operations | User creation, role assignment, etc. require network — no offline write parity with mobile. |
| Sensitive blobs | If a draft contains sensitive data, encrypt with Web Crypto API using a key derived from the active session; clear on logout. |

## Non-goals

- The web PWA does **not** need full offline scoring. The évaluateur uses the Flutter app for that.
- Cross-device sync of draft state is out of scope.
- iOS Safari quirks with service workers and IndexedDB persistence are *not* a defense-scope concern (web is desktop/laptop primary).

## Consequences

- Documentation and onboarding must make this asymmetry explicit, so testers know what to expect when "going offline" on each client.
- The web team can stop trying to match Flutter's offline depth — they were spec'ing a sync engine that wasn't needed.
- The mobile team must own the SQLite + sync-queue complexity; this is the heaviest single mobile story.
