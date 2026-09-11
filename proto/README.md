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
├── buf.gen.yaml
└── home_inv/plugin/v1/
    ├── code_format.proto        generate and parse codes
    ├── metadata_resolver.proto  ISBN, EAN, GTIN …
    ├── print_target.proto       printers
    ├── scan_source.proto        cameras, handheld scanners
    ├── notification.proto       delivery channels
    ├── core_api.proto           what a plugin may call back into
    └── common.proto             shared messages
```

## Rules that CI enforces

- `buf lint` — style and consistency
- `buf breaking` against the published state — **a break fails the build**
- Field numbers are never reused; removed fields are marked `reserved`
- A new major version becomes `…/v2`; `v1` stays served for at least six months

## Why protobuf and not the REST API

Plugins may be written in any language — Java, Kotlin, Python, Go, Rust, Node.
A protobuf contract generates a client for all of them, and `buf breaking` turns
contract drift into a build failure rather than a runtime surprise at an
operator's site. The full reasoning:
[09 Extensibility](../docs/architecture/09-extensibility-and-plugins.md).

## Status

Empty. The contract is written in stage 3, and deliberately not before — a
contract published early is binding early, and these ports need to prove
themselves against real use first.
