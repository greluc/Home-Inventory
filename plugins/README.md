# plugins/

The plugins this project builds itself, one directory each.

**Licence: AGPL-3.0-or-later**, like the rest of the first-party code. The
contract they are written against — [`plugin-api/`](../plugin-api/),
[`proto/`](../proto/) and later [`plugin-sdk/`](../plugin-sdk/) — stays
Apache-2.0 so that a *third party* may licence their plugin as they please
([ADR-0018](../docs/adr/0018-licensing.md)).

## Why they exist at all

`api` and `worker` have **no** route out of the deployment
([ADR-0026](../docs/adr/0026-core-outbound-via-plugins.md)). Everything that
leaves it is therefore a plugin, and these five are the ones a deployment cannot
sensibly do without:

| Directory | Port | What it reaches | Language | State |
|---|---|---|---|---|
| [`smtp/`](smtp/) | `NotificationChannel` | An SMTP submission server | Rust | **built** |
| [`webhook/`](webhook/) | `NotificationChannel` | A URL a tenant configured, signed with an HMAC | Rust | **built** |
| [`blobstore-s3/`](blobstore-s3/) | `BlobStore` | An S3-compatible endpoint | Rust | **built** |
| [`blobstore-nextcloud/`](blobstore-nextcloud/) | `BlobStore` | A Nextcloud instance over WebDAV | Rust | **built** |
| `oidc/` | `IdentityProvider` | An OIDC provider: discovery, JWKS, PKCE | Java | stage 1, not written |

[`common/`](common/) is not a plugin: it is the library the Rust ones share —
the mTLS identity they present to the core, the `CONNECT` tunnel that is their
one route out, the TLS handshake at the far end of it, **one HTTP/1.1 exchange**
over both, base64 and hex, HMAC-SHA256, the calendar arithmetic behind a UTC
stamp, what a blob's address is, and the bounded set of keys already delivered.
It appeared with the second plugin, which is the moment
[ADR-0072](../docs/adr/0072-first-party-plugins-live-here.md) named for it.

The language per plugin and the reasoning behind each choice are in
[ADR-0072](../docs/adr/0072-first-party-plugins-live-here.md). The short version:
`REQ-NFR-009` counts memory as the sum of the reservations, five JVMs would spend
over a gigabyte of it, and `plugin-oidc` is the one where an audited token
validation library outweighs an image size.

## Being in this repository buys nothing

Each of these is **installed, granted and revoked exactly like a third party's**:
a container the operator declares in
[`deploy/services.yaml`](../deploy/services.yaml), a manifest, a pinned
certificate fingerprint, capabilities granted per tenant, and an egress allowlist
compiled from that manifest. **No code path asks whether a plugin is ours** — a
privileged path is what would eventually be used for something else
([09 §9.9](../docs/architecture/09-extensibility-and-plugins.md)).

They are also the first users of the contract a third party gets, which is the
point of writing them before the SDK rather than after: they find its rough edges
while it can still be changed.

## What belongs here

A plugin that this project builds, signs and ships as its own container. Nothing
that the core could do in-process belongs here — the in-core adapters are listed
in [09 §9.9](../docs/architecture/09-extensibility-and-plugins.md), and an
adapter that opens no socket has no reason to be a container.
