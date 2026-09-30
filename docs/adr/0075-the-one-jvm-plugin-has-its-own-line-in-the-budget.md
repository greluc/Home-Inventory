<!--
SPDX-FileCopyrightText: Lucas Greuloch
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR-0075 — The one JVM plugin gets its own line in the memory budget

**Status:** Accepted · **Date:** 2026-09-21

**Amends:** [ADR-0072](0072-first-party-plugins-live-here.md) (its consequence *"four small
Rust containers and one JVM fit the reservation sums"* was one figure, and it is two),
`REQ-NFR-009`

## Context

`REQ-NFR-009` states the memory budget as two sums plus one adder: **64 MB of reservation
and 256 MB of limit per installed plugin**. That adder was written when every plugin in
sight was a small Rust container, and four of the five are:
`plugin-webhook`, `plugin-smtp`, `plugin-blobstore-s3` and `plugin-blobstore-nextcloud`
reserve 32–64 MB and mean it.

`plugin-oidc` is the fifth, and [ADR-0072](0072-first-party-plugins-live-here.md) chose
**Java** for it deliberately: *"a subtle validation bug written twice is exactly how a
federated login becomes an authentication bypass"*, so it uses the audited JOSE library
rather than a second implementation of ID-token validation. That decision stands. What did
not survive contact with it is the arithmetic beside it.

A `temurin` JRE with gRPC, Netty and Nimbus does not live in 64 MB. The heap is the
smallest part of the problem: metaspace, the code cache, Netty's direct buffers and the
thread stacks all sit **outside** it, and `-XX:MaxRAMPercentage` bounds only the heap. A
container limited to 256 MB with a 192 MB heap ceiling has 64 MB left for everything else,
which is the configuration that works in a demonstration and gets killed on the day
somebody signs in while the JIT is still compiling.

## Options

| Option | The budget | What it costs |
|---|---|---|
| **Give the JVM its own adder** | 64 MB per Rust plugin, **192 MB** for the JVM one (512 MB limit) | ≈ 128 MB more in `standard`, against 8 GB |
| Force it into 64 / 256 | unchanged | The requirement stays pretty and the container is the first one the kernel reaps under pressure. A budget that is met by writing a smaller number is not met |
| Write it in Rust after all | unchanged | Contradicts ADR-0072 on the one point it argued hardest, and trades a memory figure for a hand-written ID-token validator |

## Decision

**`REQ-NFR-009`'s per-plugin adder becomes two figures**, and this record is written
**before** the plugin it is for:

| | Reservation | Limit |
|---|---|---|
| Each Rust plugin | 64 MB | 256 MB |
| `plugin-oidc`, the one JVM | **192 MB** | **512 MB** |

The profile sums themselves do not move: they have excluded plugins since the adder was
written, and `tracked-facts.yaml` computes them that way.

## Rationale

The requirement is amended first and the implementation follows, which is the order
`CLAUDE.md` states and the reason this is a record rather than a commit message: a number
that is changed to fit what was built is not a budget, and the change is worth more than the
number because it says which of the five is different and why.

The alternative that keeps the figure — one JVM squeezed into the Rust adder — fails the
test `REQ-NFR-009` exists to pass. It is measured *against a running deployment under load*,
so a reservation that is too small does not produce a failing check, it produces a container
that is reclaimed first and an authentication that intermittently is not there.

`plugin-oidc` is also the plugin a deployment may well not install: federated sign-in is
`S`-priority (`REQ-AUTH-005`), and an instance without an external provider runs the other
four and carries none of this.

## Consequences

- **`REQ-NFR-009` carries both adders**, and so do [06 §6.7](../architecture/06-deployment-view.md)
  and the profile table beside it. Every restatement moves with the row, which is what
  `REQ-CON-015` is for.
- **`deploy/services.yaml` gives `plugin-oidc` `memoryReservation: 192Mi` and
  `memoryLimit: 512Mi`**, and the generated units and Compose file follow from it.
- **The image is `eclipse-temurin:25-jre-noble` with the flags `app/` already uses** —
  `-XX:MaxRAMPercentage=75` and `-XX:+ExitOnOutOfMemoryError`. A JVM that runs out of memory
  exits rather than degrading, because a federated login that answers slowly and sometimes
  is worse than one that is plainly down.
- **A sixth plugin in Java would need this record amended again**, which is the point of
  writing the adder per language rather than per plugin: the next one has to say why it is
  not Rust before it spends the memory.
