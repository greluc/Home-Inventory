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

## Status

**The manifest model is here; the port interfaces are not.**

`PluginManifest`, `PluginManifestReader` and `InvalidManifestException` arrived on
2026-09-14 with `REQ-PLG-004`, which is stage 1: the core reads a manifest to
register a plugin, and the SDK reads the same one to tell an author what is wrong
with theirs before they ship it. Two readers of one format is one reader too
many, so it lives in the module both sides may depend on.

The **ports** still wait for stage 3, and the reason this file gave is unchanged:
a contract published early is binding early, and `REQ-PLG-009` — the SDK in five
languages — is a stage-3 requirement for exactly that reason. The first-party
plugins of stage 1 are built against the same contract third parties get in stage
3, *which is what proves it before it is published*
([04 §Stage 1](../docs/requirements/04-roadmap-and-stages.md)).

*This section said "Empty" until 2026-09-14. It had been written when every
`REQ-PLG` was stage 3; `ADR-0028` moved the runtime to stage 1 because the core
makes no outbound connection any more, and the status line did not follow.*
