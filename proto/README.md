# proto/

The **plugin contract** — `home_inv.plugin.v1`, as protobuf.

**Licence: Apache-2.0**, not AGPL. This is deliberate and structural: a plugin
author compiles against this, and must stay free to license their plugin however
they wish ([ADR-0018](../docs/adr/0018-licensing.md)). CI checks that nothing in
here depends on a core module — without that check the promise would be worth
nothing.

## What belongs here

```
proto/
├── buf.yaml
└── home_inv/plugin/v1/
    ├── common.proto             what every port shares
    ├── health.proto             what a plugin says about itself
    ├── blob_store.proto         binary storage
    ├── code_format.proto        generate and parse codes
    ├── scan_source.proto        cameras, handheld scanners
    ├── label_media.proto        label geometries
    ├── label_renderer.proto     template + data → a printable artifact
    ├── print_target.proto       printers
    ├── metadata_resolver.proto  ISBN, EAN, GTIN …
    ├── search_index.proto       index and search
    ├── notification.proto       delivery channels
    ├── identity_provider.proto  federated login
    ├── image_processor.proto    image derivatives
    ├── virus_scanner.proto      checking uploads
    ├── valuation.proto          current and replacement value
    ├── import_mapper.proto      reading a foreign format
    └── core_api.proto           what a plugin may call back into
```

**A service is named after its port**, exactly as `REQ-PLG-001` spells it:
`home_inv.plugin.v1.BlobStore`, not `BlobStoreService`. That name is the gRPC
path and the thing a plugin author looks up, and `PluginContractTest` fails when
a service and its port drift apart. `buf.yaml` says which lint rules this costs
and why they are excepted rather than obeyed.

There is **no `buf.gen.yaml`**. `buf` lints and compares; the stubs are generated
by each consumer's own toolchain — Gradle's protobuf plugin for the core, Gradle
again for the Java and Kotlin SDKs, `tonic-build` for the Rust services. A
generation config nobody runs is a config that drifts from what is actually
built.

## Rules that CI enforces

- `buf lint` — style and consistency, against the rules `buf.yaml` names
- `buf breaking` against **the previous commit** — a break fails the build. On a
  pull request that is the base branch's tip, on a push to `main` the commit
  before; both answer "did this change break the contract"
- Field numbers are never reused; removed fields are marked `reserved`
- A new major version becomes `…/v2`; `v1` stays served for at least six months

## Why protobuf and not the REST API

Plugins may be written in any language — Java, Kotlin, Python, Go, Rust, Node.
A protobuf contract generates a client for all of them, and `buf breaking` turns
contract drift into a build failure rather than a runtime surprise at an
operator's site. The full reasoning:
[09 Extensibility](../docs/architecture/09-extensibility-and-plugins.md).

## Status

**Fifteen services: the fourteen ports of `REQ-PLG-001` and the health check
every plugin serves.** `core_api.proto` — what a plugin may call back into
(09 §9.6) — is the one file above that does not exist yet; it belongs with the
runtime that enforces it (`REQ-SEC-057`).

*This file said "Empty. The contract is written in stage 3" until 2026-09-14. It
had been written when every `REQ-PLG` was stage 3; `ADR-0028` moved the runtime
to stage 1 because the core makes no outbound connection any more, and
`blob_store.proto` had already been here since the blob store became its own
service.*

The reason that sentence gave still holds for **publishing**, which is a
different act from writing: until stage 3 this contract is exercised by
first-party plugins and may still change, and `ADR-0011`'s six-month guarantee
begins when the SDK does (`ADR-0028`). The `buf breaking` gate above is what
makes a change deliberate in the meantime.
