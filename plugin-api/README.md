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

Empty. The contract is written in stage 3, when the ports have proved themselves
against real use. A contract published early is binding early.
