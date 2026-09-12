# ADR-0055 — Five first-class plugin SDKs: Java, Kotlin, Rust, Python and Go

- **Status:** Accepted
- **Date:** 2026-09-12
- **Deciders:** @greluc
- **Amends:** `REQ-PLG-009`, [09 §9.8](../architecture/09-extensibility-and-plugins.md),
  [04 Roadmap](../requirements/04-roadmap-and-stages.md) and `plugin-sdk/README.md`,
  all of which named Java and Python and nothing else.

## Context

`REQ-PLG-009` promised *"an SDK … Java, Python, the protobuf module, a contract
test suite, a project template"*, and 09 §9.8 listed two artefacts to match.
Everything else was left to the `buf` module, under the heading *"protobuf
definitions for every other language"*.

That phrasing is the problem. A protobuf module is a wire contract, not an SDK: it
gives an author generated stubs and leaves them to write the server scaffolding,
the manifest validation, the health endpoint, the logging shape and the test
harness themselves — which is the work `plugin-sdk/README.md` says should be *"an
afternoon, not a week"*. Naming two languages and gesturing at the rest sets the
bar at an afternoon for two and a week for everyone else.

Kotlin and Rust were absent entirely, which is odd on both counts. The apps are
Kotlin Multiplatform, so Kotlin is already a first-party language in this project;
and two of the deployment's own services — `blobstore` and `egress-proxy` — are
Rust ([ADR-0050](0050-blobstore-service-in-rust.md)), so its toolchain, base image
and release path exist here already.

## Decision

**Five SDKs, all first-class: Java, Kotlin, Rust, Python and Go.** First-class
means, for each of them, the same list: gRPC server scaffolding, manifest
validation, a health endpoint, logging, test helpers, a `homeinv-plugin init
--lang <name>` scaffold, at least one complete example plugin, and a contract-test
run that actually passes.

**Kotlin is its own artefact**, not the Java one with a note. It gets an idiomatic
API — coroutines rather than futures, Kotlin types, its own scaffold. "The Java
SDK is callable from Kotlin" is true of every JVM library and is not what
supporting a language means; a Kotlin author writing `CompletableFuture` chains
against a Java builder has been handed a dependency, not an SDK.

**The example plugins spread across the languages** rather than clustering in one:
`MetadataResolver` in Kotlin, `PrintTarget` in Python, `StorageAdapter` in Rust,
`WebhookTarget` in Go, `CodeFormat` in Java. Stage 3's definition of done is *an
external author, working only from the published SDK, getting a plugin running* —
and for four of five languages that would otherwise be proven by nothing.

## Consequences

- **Stage 3 grows by three SDKs and three example plugins.** That is the price and
  it is the point; this is the stage whose whole content is the published
  contract. Nothing moves into an earlier stage.
- **The contract test suite becomes five suites, or one that runs five ways.**
  `homeinv-plugin-testkit` is the single bar for listing in `PLUGINS.md`, so a
  language whose SDK cannot be put through it is not on this list. That constraint
  is the reason to decide the set now rather than adding languages later one at a
  time.
- **Five release paths.** Maven Central for Java and Kotlin, crates.io for Rust,
  PyPI for Python, a module path for Go. Each needs its own publication and its
  own signature in CI.
- **Breaking the contract gets more expensive**, which is a feature. `buf
  breaking` already gates `home_inv.plugin.v1`; five SDKs make the six-month
  overlap promise in 09 §9.11 mean five sets of artefacts kept alive, and that is a
  real deterrent against a casual `v2`.
- **Go is new to this repository.** Java, Kotlin, Rust and Python all appear
  already — in the core, the apps, the deployment services and the tooling — but
  nothing here is Go today. It is on the list because an external plugin author is
  as likely to reach for it as for any of the others, and because a single static
  binary in a `scratch` image is exactly the shape ADR-0006 wants an
  out-of-process plugin to have.
