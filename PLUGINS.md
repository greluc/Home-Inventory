# Known plugins

A curated list of plugins for Home Inventory, so that people can find them in one
place.

> ## Read this before you install anything from this list
>
> **This project does not distribute these plugins, does not sign them, and makes
> no security assurance about them.** A line in this list means one thing only:
> the plugin passed the contract test suite at the time it was added, and its
> manifest is signed by its own author.
>
> Installing a plugin is a deliberate act by the operator, and it stays that way —
> there is no installation from inside the running system, by design
> ([09 §9.9](docs/architecture/09-extensibility-and-plugins.md)).
>
> **You are trusting the plugin's author, not us.** Read the manifest before you
> grant a capability. `network:outbound` with a host list tells you exactly where
> data would go; `core:item:read` means it can see your inventory. Nothing is
> granted implicitly, and you can revoke any of it later.

## The list

| Plugin | Publisher | Ports | Contract | Licence | Manifest | Certificate fingerprint |
|---|---|---|---|---|---|---|
| *(none yet — the plugin runtime lands in stage 3)* | | | | | | |

## Getting a plugin listed

Open a pull request adding a row. It will be accepted when:

1. **The contract tests pass.** Run `homeinv-plugin-testkit` against your plugin
   and link the run. This is the only technical bar, and it is not negotiable —
   it is what keeps a broken plugin from looking like a broken core.
2. **The manifest is signed** (`cosign`) and the fingerprint in the row matches.
3. **The manifest declares its capabilities honestly.** A plugin that asks for
   `core:item:write` when it only reads will be sent back.
4. **The repository is public** and has a licence, a readme and a way to report a
   problem.
5. **Your row is accurate**: the ports it implements, the contract version range
   it supports, and the licence.

Being on this list is not an endorsement, and removal needs no more than a
credible report that one of the five points above no longer holds.

## Plugins that ship with the product

These are part of the core image, are covered by the project's own CI, and need
no entry above: QR code generation, the PDF sheet renderer, the filesystem, S3
and Nextcloud storage adapters, the OpenSearch and PostgreSQL search adapters,
e-mail and webhook notifications, Web Push, OIDC federation, ClamAV scanning and
the Avery label geometry catalogue.
