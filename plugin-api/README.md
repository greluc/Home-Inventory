# plugin-api/

The Java interfaces a plugin implements — `homeinv-plugin-api`.

**Licence: Apache-2.0**, not AGPL.

## Why the licence differs, and why that is load-bearing

The core is AGPL-3.0-or-later. If a plugin author had to compile against
AGPL-licensed interfaces, the AGPL could reach into their plugin — which would
make "you may license your plugin however you like" a false promise
([ADR-0018](../docs/adr/0018-licensing.md)).

The separation is therefore **structural, not a statement of intent**:

- These modules have **no dependency** on any core module
- CI checks that, and a violation fails the build
- Only permissively licensed dependencies are allowed in here

A file that lands in this directory by accident makes the promise untrue. That is
why the boundary is checked rather than trusted.

## What belongs here

The port interfaces and their carrier types: `CodeFormat`, `ScanSource`,
`LabelRenderer`, `PrintTarget`, `LabelMediaProvider`, `MetadataResolver`,
`BlobStore`, `SearchIndex`, `NotificationChannel`, `IdentityProvider`,
`ImageProcessor`, `VirusScanner`, `ValuationProvider`, `ImportMapper` — plus the
error types and the manifest model.

## What does not

Any implementation. Anything that needs a core type. Anything that would drag a
copyleft dependency in with it.

## How the ports are shaped

Every port method takes a `CallContext` first — the tenant the call is for, the
trace it belongs to, the language a person reads, and how long the caller will
wait. A plugin is granted **per tenant** (09 §9.4), so "which tenant" is the
question that decides whether a call is allowed at all; it is answered before the
call is made and never by the plugin.

Failure is a `PluginException` with a `Kind` that survives the process boundary as
a gRPC status. One exception with a kind, not a family of classes: a class name
does not travel, and `retryable()` is derived from the kind so that two plugins
cannot disagree about whether the same situation is worth trying again.

Nothing here returns a URL for the core to fetch. A plugin holds the network
capability and the egress allowlist, so it fetches and hands over the bytes
(`REQ-SEC-034`).

`PluginHealth` is implemented by every plugin, and `HealthState` is the set from
[`plugin-health-states.yaml`](../docs/reference/plugin-health-states.yaml) —
compared against it on every build. An outbound plugin verifies its target
**completely** before it serves and reports `PROVISIONING_INCOMPLETE` on a partial
one, accepting nothing (`REQ-PLG-015`): a half-built target takes writes the other
half cannot read back.

## Status

**The manifest model and the fourteen ports are here. The SDK is not.**

`PluginManifest`, `PluginManifestReader` and `InvalidManifestException` arrived on
2026-09-14 with `REQ-PLG-004`, which is stage 1: the core reads a manifest to
register a plugin, and the SDK reads the same one to tell an author what is wrong
with theirs before they ship it. Two readers of one format is one reader too
many, so it lives in the module both sides may depend on.

The **fourteen ports** followed the same day with `REQ-PLG-001`, in
`de.greluc.homeinv.plugin.api.port`. They are here rather than in the core's own
blocks because this is the module a plugin author compiles against, and it is
Apache-2.0 ([ADR-0064](../docs/adr/0064-the-ports-a-plugin-implements-are-apache.md)).
Six of them — `CodeFormat`, `ScanSource`, `LabelRenderer`, `PrintTarget`,
`LabelMediaProvider` and `MetadataResolver` — belong to features that ship at stage
2 or 3 and have no implementation yet. They exist now because the contract is
written once (`ADR-0028`), and `PortCatalogueTest` reads the names out of
`REQ-PLG-001` so that the catalogue and this package cannot drift apart.

*This section said the ports "still wait for stage 3" until 2026-09-14, which had
been true when every `REQ-PLG` was stage 3. `ADR-0028` moved the runtime to stage
1, and the SDK — `REQ-PLG-009`, five languages — is what stays there.*

What is **still absent** is everything an author needs around a port: the gRPC
scaffolding, the contract test suite and the project template. That is
`plugin-sdk/`, and it is stage 3.
