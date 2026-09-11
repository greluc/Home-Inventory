# ADR-0012 — React with TypeScript and Vite as a PWA

**Status:** Accepted · **Date:** 2026-09-11

## Context

The web frontend has to use the camera for QR and barcode scanning, work fully
offline ([ADR-0014](0014-offline-synchronisation.md)), be installable on a phone,
and share a data model and protocol understanding with the KMP apps.

## Options

| Option | For | Against |
|---|---|---|
| **React + TypeScript + Vite** | The largest ecosystem for camera and code detection · mature PWA and offline tooling · excellent support for clients generated from OpenAPI · very many contributors know it | Its own build and deployment; the library selection must be kept disciplined |
| Angular | Strongly structured, DI and module boundaries similar to Spring | Larger, a steeper upgrade cadence, a smaller selection of scanner libraries |
| Vue 3 | Lean, a good PWA story | A smaller ecosystem for this particular use case |
| Thymeleaf + HTMX | One deployment fewer, the smallest attack surface in the browser | **Real offline use is practically unachievable** — and it is required |

## Decision

**React 19 + TypeScript + Vite**, shipped as an installable PWA.

## Rationale

The offline requirement rules out a server-rendered UI. Among the SPA options the
ecosystem for camera scanning and offline storage decides it — that is the
functionally riskiest part of the frontend, and there one wants to walk a
well-trodden path.

## Consequences

- **A strict CSP without `unsafe-inline` and without `unsafe-eval`.** The Vite
  build is configured accordingly and a CI test checks the served policy.
- `dangerouslySetInnerHTML` is forbidden by a lint rule.
- Scanning: the `BarcodeDetector` API preferred, ZXing-WASM in a web worker as a
  fallback. Camera access requires HTTPS — stated explicitly in the installation
  guide, because it is the most common stumbling block.
- Offline: IndexedDB for domain data (no `localStorage`), a service worker for
  the application code, the Cache API for images, `navigator.storage.persist()`
  requested.
- **IndexedDB is not encrypted.** The PWA therefore stores no `sensitive` fields
  locally and says so.
- Dependencies are deliberately few; every new runtime dependency is a decision,
  because it enters the supply chain.
- The API client is generated from OpenAPI and not hand-maintained.
- Accessibility: WCAG 2.2 AA as the goal, keyboard operation throughout, `axe`
  blocking in CI, without a formal conformance statement.
