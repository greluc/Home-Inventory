# ADR-0064 — The ports a plugin implements live in `plugin-api`, not in the core

**Status:** Accepted · **Date:** 2026-09-14

**Depends on:** [ADR-0018](0018-licensing.md), [ADR-0028](0028-plugin-runtime-stage-1.md)

## Context

`REQ-PLG-001` requires the **fourteen extension points to exist** at stage 1 —
`CodeFormat`, `ScanSource`, `LabelRenderer`, `PrintTarget`, `LabelMediaProvider`,
`MetadataResolver`, `BlobStore`, `SearchIndex`, `NotificationChannel`,
`IdentityProvider`, `ImageProcessor`, `VirusScanner`, `ValuationProvider`,
`ImportMapper` — and the plugin runtime to resolve them.

Four of them already existed as Java interfaces in the `api` package of the block
that needs them: `media.api.BlobStore`, `media.api.ImageProcessor`,
`media.api.VirusScanner`, `search.api.SearchIndex`. That is where a port belongs
under [ADR-0002](0002-modular-monolith.md) — the block that needs an outbound
capability declares it, and an adapter in `infrastructure` satisfies it.

The question this raises is not where the *core's* ports live. It is which
interface a **plugin author** implements, and it has to be answered before the
runtime is built, because the answer decides what the transport carries.

## Options

| Option | A plugin implements | Licence a plugin compiles against | In-process (09 §9.7) | Cost |
|---|---|---|---|---|
| **Plugin-side in `plugin-api`** | `plugin.api.port.*` | Apache-2.0 | works as described | one adapter per port that has a core-side caller |
| Core-side in each block | `media.api.BlobStore` … | **AGPL-3.0-or-later** | not possible as 09 §9.7 describes it | six blocks from stage 2/3 created now to hold one interface each |
| Only the `.proto` | a generated stub | Apache-2.0 | **no type to implement** | the core would program against the transport |

## Decision

**The fourteen ports are Apache-2.0 Java interfaces in
`de.greluc.homeinv.plugin.api.port`, in the `plugin-api` project.** The core keeps
its own ports where they are, in the `api` package of the block that needs them,
spelled in the core's own types; an adapter per port joins the two.

Alongside them, `plugin-api` gains what every port needs: `CallContext` (the
tenant a call is for), `PluginException` with a kind that survives the process
boundary, `MoneyValue`, `HealthState` and `PluginHealth`.

## Rationale

**The licence argument decides it, and the other two are cost against cost.**

[ADR-0018](0018-licensing.md) promises a plugin author that they may license their
plugin however they wish, and makes the promise structural: `plugin-api`,
`plugin-sdk` and `proto` are Apache-2.0 and depend on nothing in the core, which
`plugin-api`'s `noCoreOnTheClasspath` task fails the build over. If the interface
an author actually implements were `media.api.BlobStore`, that check would stay
green while the implementable surface was AGPL — a check that no longer covers the
statement it was written for.

Two consequences follow that are worth naming separately, because each would have
been discovered late:

- **09 §9.7's classloader filter becomes possible.** An in-process plugin sees
  `de.greluc.homeinv.plugin.api.*` and the JDK base, and nothing else. With
  core-side ports the filter would have to admit `media.api`, `search.api`,
  `inventory.api` …, and "the plugin sees parts of the core" is the sentence that
  chapter refuses to write.
- **No block is created before its feature.** `CodeFormat`, `ScanSource`,
  `LabelRenderer`, `PrintTarget` and `LabelMediaProvider` belong to stage 2 and
  `MetadataResolver` to stage 3. Their ports exist now; the blocks that will
  implement them do not have to.

The third option — a port that is only a protobuf service — was rejected on two
counts: it leaves an in-process plugin with nothing to implement, which
`REQ-PLG-003` and `REQ-SEC-058` require to be possible, and it would have the core
programming against the transport. `media` knows `BlobStore` precisely so that it
does not know gRPC.

## Consequences

- **One adapter per port that has a core-side caller.** Eight at stage 1, of which
  the `BlobStore` one already exists in the shape of `GrpcBlobStore`. The adapter
  is also where the core's types meet the contract's: `search.api.SearchIndex`
  speaks `CursorCodec.Position` and `QueryFilter`, and the plugin-side one speaks
  strings, because a plugin cannot be made to depend on the core's cursor format.
- **Two interfaces share a name in eight cases.** Deliberate: they are the same
  port seen from two sides, and giving the plugin-side one a different name would
  hide that. Imports say which is meant.
- **The contract may still change.** It is exercised at stage 1 by first-party
  plugins and published at stage 3; only then does
  [ADR-0011](0011-api-versioning.md)'s six-month rule bind it (ADR-0028).
- **`REQ-PLG-001` is checkable.** `PortCatalogueTest` reads the port names out of
  the requirement's own text and fails when an interface for one is missing — the
  same mechanism that keeps `permissions.yaml` and the `Permission` enum together.
- **Six ports have no implementation and no caller yet.** They are contract, not
  dead code, and the requirement that put them there says which stage each one's
  feature arrives in.
