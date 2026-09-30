# plugin-blobstore-nextcloud

A tenant's media in a Nextcloud folder, over WebDAV.

It is the path [ADR-0007](../../docs/adr/0007-media-storage.md) named first:
photographs end up in a store that is already backed up, synchronised and
shared, and the person who owns them can open the folder and see them. Like
every store outside the deployment it is a plugin rather than a core adapter,
because it opens a connection outward
([ADR-0026](../../docs/adr/0026-core-outbound-via-plugins.md)).

## Granting it moves nothing

A tenant that grants this plugin has its **next** bytes written here and none of
its older ones moved
([ADR-0074](../../docs/adr/0074-granting-a-store-routes-and-does-not-move.md)).
Everything uploaded before the grant stays in the deployment's own `blobstore`
and is still served from there; the core asks this plugin first and falls back.

## Configured twice, like every plugin

[ADR-0073](../../docs/adr/0073-a-plugin-is-configured-twice.md): what the
**operator** decides for the installation is this container's own environment;
what a **tenant** decides arrives in the call envelope, and a tenant's value wins.

| Setting | Who | Where | Default |
|---|---|---|---|
| `HOMEINV_NEXTCLOUD_URL` | operator | container environment | none — `https://cloud.example.org`, with a path when Nextcloud is not at the root |
| `HOMEINV_NEXTCLOUD_USER` | operator | container environment | none |
| `HOMEINV_NEXTCLOUD_PASSWORD_FILE` | operator | **a mounted secret** | `/run/secrets/plugin-blobstore-nextcloud-password` |
| `HOMEINV_NEXTCLOUD_FOLDER` | operator | container environment | empty — the account's root |
| `HOMEINV_NEXTCLOUD_TIMEOUT_SECONDS` | operator | container environment | `60` |
| `HOMEINV_EGRESS_PROXY` | operator | container environment | none — and without it there is no route out |
| `url`, `folder` | **each tenant** | the call envelope | the deployment's value |
| `username`, `appPassword` | **each tenant** | the call envelope, the password **sealed** in the core | the deployment's pair |

**An app password, never the account password.** Nextcloud issues app passwords
per application, they carry no web session, and revoking one revokes exactly this
integration. A deployment that held a person's account password would hold the
key to their whole Nextcloud, which is not what storing photographs needs.

**The deployment's app password never passes through the core.** It is a file
mounted into this container; `deploy/setup.sh` creates
`deploy/secrets/plugin-blobstore-nextcloud-password` **empty** and says so. A
tenant's own password is a different thing and does live in the core — sealed
with the tenant's data key
([ADR-0019](../../docs/adr/0019-sensitive-field-encryption.md)), never returned
by any read endpoint, and opened only into the envelope of this plugin's calls.

**A deployment that configures nothing at all is valid**, and is the shape where
every tenant brings its own account. A deployment that configures *half* a
default is not, and the health check names each missing piece.

**Credentials are a pair.** `username` and `appPassword` are taken together or
not at all. Half a pair would authenticate as one party with the other's
password, and the answer is a `401` that says nothing about which half was wrong.

The **instances this deployment may reach** are the `network:outbound` `tcp:`
targets of the manifest, compiled into the egress proxy's allowlist
([ADR-0027](../../docs/adr/0027-egress-enforcement.md)). A tenant may name any
instance and will get a refusal from the proxy unless the operator allowed that
host.

## Where a blob lands

`<folder>/sha256/<tenantId>/<aa>/<bb>/<hash>` inside the account — the same
layout the in-deployment `blobstore` service writes on disk and the S3 plugin
writes into its bucket
([ADR-0032](../../docs/adr/0032-per-tenant-blob-addressing.md)). Identical on
purpose: moving a tenant between any two of the three stores is a copy and never
a rename, and a person looking into any of them sees the same thing.

## Uploads

**WebDAV creates no parent.** A `PUT` into a collection that does not exist
answers `409 Conflict`, and no header changes that. So a `409` is answered by
creating the chain with `MKCOL` and trying once more — after the first blob of a
tenant every collection already exists, which is why the chain is walked on
failure rather than before every upload.

Below 8 MiB an object is one `PUT`. At or above it the upload becomes a
**chunked upload**: a collection under `remote.php/dav/uploads/<account>/`, one
`PUT` per 8 MiB chunk into it, and a final `MOVE` of its `.file` onto the
destination, which is the moment the object appears. It exists because the core
streams an object whose size is not known in advance, and a single `PUT` needs a
`Content-Length` before the first byte.

Chunk names are zero-padded. `10` sorting before `2` is a corrupted file that
nothing reports, and padding makes the numeric and the textual order the same
whichever one the instance uses.

**The hash is verified as the bytes stream**, and an upload whose content does
not match the address it was given is refused: a chunked upload already begun is
abandoned rather than assembled.

An object that is already there is **not** uploaded again: the address is the
content hash, so a path that exists holds exactly these bytes.

## Deleting

A `DELETE` moves the file into that account's trash, and Nextcloud purges it on
that account's own retention rule. That is the tenant's setting rather than this
plugin's: `REQ-PRIV-004` asks that nothing is lost silently, not that a store
forgets instantly, and the core's own deletion is two-stage for the same reason.

## What an answer means

| Answer | Meaning |
|---|---|
| `INVALID_ARGUMENT` | the address is not a SHA-256, or the envelope names a different tenant from the address, or the bytes do not hash to the address |
| `FAILED_PRECONDITION` | installed and not configured for this tenant — the message lists every missing piece at once |
| `NOT_FOUND` | this Nextcloud does not hold that blob. For an old one that is expected: the grant moved nothing |
| `UNAVAILABLE` | the instance or the proxy refused or could not be reached; Nextcloud's own `<s:message>` is passed through |

## Building and testing

```bash
cd plugins/blobstore-nextcloud
cargo test
cargo clippy --all-targets -- -D warnings
```

The tests are unit tests: the path layout, the collection chain, the absolute
`Destination` a `MOVE` needs, the configuration merge, the `Authorization`
header and the error parsing. There is no test against a live Nextcloud here —
the port's behaviour against the core is held by `TenantBlobStoreIT` in `app/`,
which runs a real plugin over a real TLS socket.
