# ADR-0006 — Two plugin runtimes behind one contract

**Status:** Accepted · **Date:** 2026-09-11

> **Amended by [ADR-0085](0085-a-manifest-signature-is-checked-offline-and-an-image-is-not.md)** — registration gains a verification step. The core checks a detached signature over the manifest's bytes against a key the operator installed, offline because `api` has no route to Fulcio, and the state a plugin reaches depends on what that check found: a signature that does not verify is registered `DISABLED` and no setting runs it, which is a different thing from a plugin nobody signed (`REQ-PLG-004`).

## Context

Extensions range from purely computational building blocks (code generation)
through network access (ISBN resolution) to hardware integrations (label
printers, handheld scanners) that may have to run on a different machine.
Foreign code must not endanger the core.

**The decisive constraint:** since Java 24 there is no functioning
`SecurityManager` any more. On the JVM **nothing** can effectively be taken away
from loaded code. A dedicated classloader separates namespaces, not privileges.
Loaded code can read files, open network connections, reach into the core by
reflection and terminate the process.

## Options

| Option | For | Against |
|---|---|---|
| In-process only (JAR) | Simple, fast, no serialisation | Foreign code runs with every privilege the core has; hardware integration would still need an external path |
| Out-of-process only | The highest isolation, free choice of language, hardware in the right place | Latency and effort even for trivial extensions |
| **Both behind one contract** | Each extension picks the fitting runtime; the core knows only the contract | Two runtime paths to maintain and test |
| WASM as a sandbox | Real in-process isolation | An immature toolchain on the JVM; no network or hardware access; considerable bespoke effort |

## Decision

**One contract (`home_inv.plugin.v1`, protobuf), two runtimes.** Out-of-process
(gRPC over mTLS, own container) is the **default** and the only path for
third-party code. In-process (a JAR in an isolated classloader) is **off** by
default and available only for signed artifacts explicitly enabled by the
operator.

## Rationale

The platform provides no in-process isolation. Out-of-process is therefore the
only way to take third-party code without giving up Q1. In-process is still
needed, because 5 000 gRPC calls to generate the codes for one label sheet would
be disproportionate — but exclusively for code the operator already trusts as
much as the core itself.

## Consequences

- **A tenant administrator cannot install in-process plugins.** Otherwise tenant
  separation would be defeatable by a plugin.
- The administration UI permanently shows when in-process code is running, with
  publisher and fingerprint.
- All examples, the SDK and the documentation show out-of-process. The convenient
  path must be the safe one.
- Capabilities apply to both runtimes alike; with in-process they are, however,
  only a convention and not a boundary — and the documentation says so.
- Every out-of-process call has a deadline, a payload limit, a bulkhead and a
  circuit breaker.
- Risk R4: in-process could become the norm out of convenience. The counters are
  the default, the missing tenant path and the visible notice.
