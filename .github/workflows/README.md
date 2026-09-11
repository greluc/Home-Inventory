# .github/workflows/

CI and release workflows.

## Status

Empty — there is no code to build yet. This file records what the workflows will
have to do, so that the gates are not invented ad hoc later.

## The gates, already decided

These come from the requirements catalogue, not from habit. Each fails the build:

| Area | Check |
|---|---|
| **Module boundaries** | Spring Modulith + ArchUnit — no cycles, no reaching past an `api` package, no framework imports in `domain`, no entity leaving its block |
| **Tenant isolation** | An automated proof across **every** table; a new table without RLS fails |
| **Authorization** | Every endpoint has a permission check and negative tests for `403` and `404` |
| **Contracts** | `oasdiff` (REST), `buf breaking` (plugins), schema comparison (GraphQL, events) |
| **Deployment** | No `privileged`, `cap_add`, host network, mounted socket, port < 1024, or `SecurityLabelDisable` — across Compose, Quadlet **and** Helm |
| **Drift** | The generated artifacts match `deploy/services.yaml` |
| **Secrets** | `gitleaks` |
| **Supply chain** | Dependency and container scanning; high or critical fails |
| **SAST** | CodeQL, SpotBugs with `find-sec-bugs`, ESLint security |
| **Headers** | The served CSP compared against an expected string |
| **Licensing** | No AGPL-incompatible dependency in the core; only permissive ones in the plugin API |
| **DCO + CLA** | Every commit signed off; the contributor has signed |

## The matrix that matters

The smoke suite runs against **both** container runtimes, rootless, and against
`kind` — and does so once on a Debian-family and once on a RHEL-family
distribution with SELinux `enforcing` (REQ-NFR-064).

A test that only passes rootful proves nothing. A change that works under only
one runtime is a bug that surfaces at an operator's site, on the runtime nobody
tested.

## Workflow hardening

Actions pinned by commit SHA, top-level `permissions: contents: read`, no
`pull_request_target` on code, and no secrets exposed to pull requests from
forks. Release signing keys live in a protected environment, never in the
workflow file.
