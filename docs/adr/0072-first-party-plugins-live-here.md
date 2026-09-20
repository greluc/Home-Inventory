<!--
SPDX-FileCopyrightText: Lucas Greuloch
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR-0072 — The first-party plugins live in this repository, one language each

**Status:** Accepted · **Date:** 2026-09-20

**Depends on:** [ADR-0018](0018-licensing.md), [ADR-0026](0026-core-outbound-via-plugins.md),
[ADR-0027](0027-egress-enforcement.md), [ADR-0037](0037-per-plugin-network-segments.md),
[ADR-0050](0050-blobstore-service-in-rust.md)

## Context

[ADR-0026](0026-core-outbound-via-plugins.md) made every outbound call a plugin's job, and
five plugins therefore carry stage 1 on their own: `plugin-smtp`, `plugin-webhook`,
`plugin-oidc`, `plugin-blobstore-s3` and `plugin-blobstore-nextcloud`. Everything that
leaves a deployment waits on them — e-mail including the security notifications of
`REQ-NOTI-004`, webhooks, OIDC federation, and the two remote media stores.

[09 §9.9](../architecture/09-extensibility-and-plugins.md) names them and says what they
are: *"built and signed by this project, shipped as their own containers because each one
calls out … installed, granted and revoked exactly like a third party's — no privileged
path, because a privileged path is what would eventually be used for something else."*

What it never says is **where they live**. The directory layout in
[CLAUDE.md](../../CLAUDE.md) has no home for them, `plugin-sdk/` is stage 3 and empty, and
a plugin written today therefore has no SDK to build on — it implements the protobuf
contract directly, which is what a third party does anyway.

## Decision

**They live here, under `plugins/`, one directory each** — decided with the owner on
2026-09-20.

The same CI builds them, signs them and runs the contract test suite against them, and
`deploy/services.yaml` declares them as services. That last part is the one that earns the
decision rather than merely simplifying it: the per-plugin network segments of
[ADR-0037](0037-per-plugin-network-segments.md), the generated egress allowlist of
[ADR-0027](0027-egress-enforcement.md) and the connectivity case of
[ADR-0071](0071-the-core-answers-plugins-on-one-channel.md) are all **generated from plugin
services that do not exist yet**. A topology nothing instantiates is a topology nothing
tests.

**Being in this repository buys no privilege, and that is checked rather than promised.**
Each one is registered with a manifest and a pinned certificate fingerprint, grants its
capabilities per tenant, and reaches the internet only through the proxy with its own
allowlist. There is no code path that asks whether a plugin is ours.

### One language each, from Rust and Java

`REQ-NFR-009` measures memory as the **sum of the reservations** — 8 GB in `standard`, 4 GB
in `minimal` — so five JVMs would spend over a gigabyte of that budget on containers whose
work is a socket and a library call. That decides most of it, and the exception decides
itself.

| Plugin | Language | Why this one |
|---|---|---|
| `plugin-smtp` | Rust | An SMTP submission with STARTTLS and authentication. `lettre` is mature, the container is tens of megabytes, and it is the plugin every deployment installs first |
| `plugin-webhook` | Rust | An HTTP POST with an HMAC signature, a retry schedule and a delivery log. The smallest of the five |
| `plugin-blobstore-s3` | Rust | On the media path, where bytes move. The in-deployment `blobstore` is Rust for the same reason ([ADR-0050](0050-blobstore-service-in-rust.md)), and the two speak the same `BlobStore` contract |
| `plugin-blobstore-nextcloud` | Rust | WebDAV over the same HTTP client as the two above, sharing their retry and chunking code |
| `plugin-oidc` | **Java** | Discovery, JWKS with rotation, PKCE and ID-token validation. This is the one where the library situation outweighs the image size: Nimbus is audited, widely deployed and gets CVEs fixed by people who do this full time, and a subtle validation bug written twice is exactly how a federated login becomes an authentication bypass |

The two toolchains are already in this repository — `blobstore/` and `egress-proxy/` are
Rust with `cargo deny` gating their dependencies' licences, `app/` is Java with SpotBugs and
find-sec-bugs — so each plugin is built and gated the way its language already is here. A
third language was offered and declined.

### Licence

**AGPL-3.0-or-later**, like the rest of the first-party code. `plugin-api/`, `plugin-sdk/`
and `proto/` stay Apache-2.0 so that a *third party* may licence their plugin as they
please ([ADR-0018](0018-licensing.md)); that promise is about the contract, not about our
implementations of it. A plugin under this directory depends on the Apache contract and on
no core module, which `plugin-api`'s own classpath check already enforces from the other
side.

## Options

| Option | Contract exercised by CI | Repositories to release | Risk of a privileged path |
|---|---|---|---|
| **Here, under `plugins/`** | yes, in the same job | one | present, and checked — nothing asks whether a plugin is ours |
| One repository per plugin | across repositories | six | lowest, structurally |
| One separate plugin repository | across two repositories | two | low |

Splitting them out is the purer answer to "installed exactly like a third party's", and it
was not chosen: six release pipelines and a cross-repository contract suite is a great deal
of machinery for a property one test asserts directly. That test is
`ArchitectureRulesTest.noPluginIsPrivilegedByItsName`, and it is the price of this
decision: **the core may not name a plugin in a string literal**, comments excepted, so the
`if (pluginId.equals("de.greluc.homeinv.plugin.smtp"))` that would quietly create a
privileged path fails the build on the commit that writes it.

## Consequences

- **`plugins/` joins the directory list**, with its own `README.md` saying what belongs
  there, as every directory here does.
- **The plugin topology stops being hypothetical.** `deploy/services.yaml` gains real
  plugin services, and with them the first generated per-plugin segments, the first
  generated egress allowlist entries beyond ClamAV's, and the connectivity case
  [ADR-0071](0071-the-core-answers-plugins-on-one-channel.md) could not write.
- **Two toolchains in the plugin CI**, each already present: `cargo deny`, `clippy` and
  `cargo fmt` for the four Rust plugins; the Gradle build, SpotBugs and find-sec-bugs for
  the Java one.
- **The memory budget stays checkable.** Four small Rust containers and one JVM fit the
  reservation sums `REQ-NFR-009` is measured against; five JVMs would not have, and the
  budget would have been amended to fit the implementation rather than the other way round.
- **`plugin-sdk/` is still stage 3 and still empty**, and these five are written against
  the protobuf contract directly. That is deliberate: they are the first users of the
  contract a third party gets, so they find its rough edges before an SDK papers over
  them — which is what [`requirements/04`](../requirements/04-roadmap-and-stages.md) means
  by *"the contract is exercised by first-party plugins before it is opened"*.
