# ADR-0013 — Kotlin Multiplatform and Compose Multiplatform

**Status:** Accepted · **Date:** 2026-09-11

## Context

Apps for Android and iOS are to come later, but have to be considered **now**:
the API shape, the authentication flow, the sync protocol and the shared types
all depend on it. The apps need the camera, offline operation, resumable uploads
and optionally Bluetooth scanners.

## Options

| Option | For | Against |
|---|---|---|
| **KMP + Compose Multiplatform** | Sync logic, domain model and API client written **once** in Kotlin · existing Kotlin/Android experience · platform-native access through `expect`/`actual` where needed | Compose on iOS is younger; camera and hardware-near parts stay platform-specific |
| Separate native (Kotlin + Swift) | The best platform integration | **The sync logic twice** — the most expensive and most error-prone part duplicated |
| Flutter | One codebase, good camera plugins | Dart as a fourth language in the project, alongside Java, Kotlin and TypeScript |
| PWA only | No additional project | Limited hardware access on iOS; no Bluetooth scanners; unreliable background reconciliation |

## Decision

**Kotlin Multiplatform** for the shared logic, **Compose Multiplatform** for the
UI, with platform-specific parts for the camera, the key store and Bluetooth.

## Rationale

The most expensive part of the apps is not the UI but the bidirectional
reconciliation with conflict handling. That part **must** be identical on both
platforms — two implementations mean two behaviours and therefore bugs nobody
finds. KMP solves exactly that.

## What follows from it already (stages 0 to 2)

Even without a line of app code, the following is prepared now:

| Preparation | Because |
|---|---|
| OpenAPI with a generated **Kotlin** client in CI | The contract must be cleanly generatable for Kotlin too |
| OAuth 2.1 Authorization Code + PKCE through the system browser | No password in the app; this shapes the auth building block |
| Client-generated UUIDv7 for every aggregate | Offline creation must work without a server round trip |
| `Idempotency-Key` on every creating `POST` | Network drops are the normal case in mobile use |
| tus for uploads | Uploads must survive network changes |
| The sync protocol as part of the versioned API contract | It cannot be bolted on afterwards |
| A shared **test suite** for the sync rules | Web and app must decide identically |

## Consequences

- The repository becomes multi-language (Java, Kotlin, TypeScript) with a
  toolchain each. The build must carry that without friction.
- The sync library in `commonMain` is a separate, individually testable artifact
  — usable outside the app as well.
- Local storage: SQLDelight on SQLite, secrets in the operating system key store
  (Keystore resp. Keychain).
- Publishing to the app stores is a separate process with its own costs and lead
  times; alternatively F-Droid and direct APK distribution for Android.
- Push notifications go through Firebase and APNs, attached as plugins and with a
  content-free payload ([ADR-0023](0023-push-notifications.md)).
