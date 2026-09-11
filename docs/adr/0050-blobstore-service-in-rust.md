# ADR-0050 — The `blobstore` service is written in Rust

**Status:** Accepted · **Date:** 2026-09-11

**Amends:** [ADR-0043](0043-blobstore-as-its-own-service.md),
[06 §6.7](../architecture/06-deployment-view.md),
[`deploy/services.yaml`](../../deploy/services.yaml), `REQ-MED-009`

## Context

[ADR-0043](0043-blobstore-as-its-own-service.md) gave the filesystem `BlobStore` a
service of its own so that `api` and `worker` stay stateless, and
[ADR-0044](0044-internal-is-not-a-trust-boundary.md) put gRPC over mTLS with a
pinned fingerprint in front of it. It sized the service deliberately:

| Property | Value |
|---|---|
| Memory | **32 MB reserved, 128 MB limit** |
| Port | 8100, gRPC, never published |
| Volume | `blobdata`, at `/var/lib/homeinv/blobs` |
| Profiles | all three, including `minimal` |

That sizing is not incidental. ADR-0043's honest counterweight was that *"for a
single-instance `minimal` installation the extra container buys nothing that a
mount would not have bought"*. It is affordable **because** it is 32 MB. A service
that needed 512 MB would have changed the answer to the question ADR-0043 asked.

It was written without naming an implementation language, and the language was
implicitly the repository's: Java and Spring Boot. **A JVM does not run in 128 MB.**
The heap alone would leave nothing for metaspace, thread stacks, code cache and the
direct buffers a file server spends its life in — the container would be killed
under the first concurrent upload, which is the failure that looks like data loss
and reads like a network fault.

So the language is a decision, and it was about to be made by default.

## Options

| Option | For | Against |
|---|---|---|
| **Java / Spring Boot, budget raised to ~256/512 MB** | One language, one build, one dependency catalogue · every convention in `CLAUDE.md` applies unchanged | Re-opens ADR-0043's trade at a worse price: `minimal` grows by half a gigabyte for a service whose whole job is `read()` and `write()` · changes the profile sums in [06 §6.7](../architecture/06-deployment-view.md), `REQ-NFR-009` and `tracked-facts.yaml` |
| Java with GraalVM `native-image` | One language · the binary fits the budget | A second toolchain anyway, and the one with the longest build and the most configuration — gRPC and TLS both drive reflection and resource registration · a native-image failure surfaces at run time, in the one service that holds every tenant's media |
| Go | Small, fast to build, `grpc-go` is the reference implementation · shallow learning curve | A second language · a garbage collector on the byte path, which is the one thing the budget is tight on |
| **Rust** | Fits the budget with room to spare · no garbage collector on the byte path · `tonic` gives gRPC and `rustls` gives mTLS with no C toolchain · memory safety without a runtime, on the one service that parses nothing but handles everything | A second language and a second toolchain (`cargo`, `clippy`, `cargo-deny`, a Renovate manager, REUSE headers) · the steepest learning curve for a future contributor · the plugin SDKs are Java and Python, so this is the only Rust in the repository |

## Decision

**`blobstore/` is a Rust service** — `tonic` for gRPC, `rustls` for mTLS, a
`scratch`-based image built from a statically linked binary.

It implements exactly the `BlobStore` contract and nothing else: `put`, `get`,
`head`, `delete` over the `sha256/<tenantId>/<hash>` layout
([ADR-0032](0032-per-tenant-blob-addressing.md)). It has **no** database
connection, **no** tenant model and **no** authorization beyond the pinned client
certificate: the tenant is a path segment the caller supplies, and the caller is
`api` or `worker`, authenticated by mTLS. Authorization stays in the application
layer, where [ADR-0010](0010-api-surfaces.md) put it.

## Rationale

The deciding argument is the one ADR-0043 already made and this decision must not
quietly undo. The extra container is justified by being **small**; a 512 MB Spring
Boot service would have been the option ADR-0043 rejected, arrived at one ADR
later.

Between Rust and Go, the budget is met by both and the tie is broken by what this
service actually is: **the one process in the deployment that touches every byte
of every tenant's media and has no other job.** It runs with no domain logic to get
wrong and no database to constrain it, so what remains is memory handling on
attacker-supplied input — which is precisely the class of defect the language
choice can remove rather than test for. `CLAUDE.md` rule 6 already refuses to
pretend that a sandbox exists where it does not; this is the same reasoning applied
where a real guarantee is available.

The counterweight is real and is accepted, not argued away: a second language in a
one-person project is a maintenance surface, and it is the surface a future
contributor is least likely to be comfortable with. It is bounded deliberately —
this service is a few hundred lines, implements a contract defined elsewhere, and
is covered by the same `BlobStore` contract test suite as the other three
implementations.

## Consequences

- **A new top-level directory `blobstore/`**, outside the Gradle build, built by
  `cargo`. `CLAUDE.md`'s directory list and `deploy/README.md` name it.
- **A second toolchain in CI**: `cargo build --release`, `cargo test`,
  `cargo clippy -D warnings`, `cargo-deny` for licences and advisories. Renovate
  gains the `cargo` manager.
- **Licensing is unchanged**: `blobstore/` is core, therefore
  **AGPL-3.0-or-later**, with REUSE headers like every other file. It is not a
  plugin and must not be confused for one — it implements no plugin manifest and
  opens nothing outside the deployment ([ADR-0043](0043-blobstore-as-its-own-service.md)).
- **`cargo-deny` is a licence gate, not a formality.** A crate under a licence
  incompatible with AGPL-3.0-or-later fails the build, and the allowlist is
  explicit.
- **The image is `scratch` plus the binary and the CA bundle.** Nothing else is in
  it: no shell, no package manager, and therefore no `/healthcheck` script — the
  health check is the binary itself with a `--health` flag, over the same gRPC port.
- **The budget in `deploy/services.yaml` stands unchanged**, which is the point of
  the decision. The profile memory sums in [06 §6.7](../architecture/06-deployment-view.md)
  and `tracked-facts.yaml` are not touched by it.
- **The `BlobStore` contract test runs against it** as the reference implementation
  ([ADR-0007](0007-media-storage.md)), from the Java side, against a container
  started by Testcontainers — so the two languages are proved to agree on the
  contract rather than assumed to.
