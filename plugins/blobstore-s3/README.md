# plugin-blobstore-s3

A tenant's media in S3-compatible object storage — MinIO, Garage, Backblaze B2's
S3 endpoint, AWS itself.

It is the first implementation of the `BlobStore` port that is not the
deployment's own service, and it is a plugin rather than a core adapter for one
reason: it opens a connection **outside** the deployment, and everything that
does is a plugin on its own segment behind the egress proxy
([ADR-0026](../../docs/adr/0026-core-outbound-via-plugins.md)).

## Granting it moves nothing

A tenant that grants this plugin has its **next** bytes written here and none of
its older ones moved
([ADR-0074](../../docs/adr/0074-granting-a-store-routes-and-does-not-move.md)).
Everything uploaded before the grant stays in the deployment's own `blobstore`
and is still served from there; the core asks this plugin first and falls back.
So a `Head` that answers "no" for an old blob is the design working, not a
failure.

## Configured twice, like every plugin

[ADR-0073](../../docs/adr/0073-a-plugin-is-configured-twice.md): what the
**operator** decides for the installation is this container's own environment;
what a **tenant** decides arrives in the call envelope. Here both halves describe
the same five things, and a tenant's value wins over the deployment's.

| Setting | Who | Where | Default |
|---|---|---|---|
| `HOMEINV_S3_ENDPOINT` | operator | container environment | none — the deployment's store, `host` or `host:port` |
| `HOMEINV_S3_BUCKET` | operator | container environment | none — the bucket every tenant uses unless it named its own |
| `HOMEINV_S3_REGION` | operator | container environment | `us-east-1`, which is what a store without regions still needs in the signature |
| `HOMEINV_S3_PREFIX` | operator | container environment | empty |
| `HOMEINV_S3_ADDRESSING` | operator | container environment | `path`; `virtual` puts the bucket in the hostname |
| `HOMEINV_S3_ACCESS_KEY_ID` | operator | container environment | none |
| `HOMEINV_S3_SECRET_KEY_FILE` | operator | **a mounted secret** | `/run/secrets/plugin-blobstore-s3-secret-key` |
| `HOMEINV_S3_TIMEOUT_SECONDS` | operator | container environment | `60` |
| `HOMEINV_EGRESS_PROXY` | operator | container environment | none — and without it there is no route out |
| `endpoint`, `bucket`, `region`, `prefix`, `addressingStyle` | **each tenant** | the call envelope | the deployment's value |
| `accessKeyId`, `secretAccessKey` | **each tenant** | the call envelope, the secret **sealed** in the core | the deployment's pair |

**The deployment's secret key never passes through the core.** It is a file
mounted into this container. `deploy/setup.sh` creates
`deploy/secrets/plugin-blobstore-s3-secret-key` **empty** and says so: a key to
somebody else's storage is not one this deployment may invent. A tenant's own
secret is a different thing and does live in the core — sealed with the tenant's
data key ([ADR-0019](../../docs/adr/0019-sensitive-field-encryption.md)), never
returned by any read endpoint, and opened only into the envelope of this
plugin's own calls.

**A deployment that configures nothing at all is valid**, and is the shape where
every tenant brings its own account. A deployment that configures *half* a
default is not, and the health check names each missing piece rather than
reporting "broken".

**Credentials are a pair.** `accessKeyId` and `secretAccessKey` are taken
together or not at all. Half a pair would sign one party's key id with another's
secret, and the store's answer would be a message about the signature — which
sends the reader to look at the clock, the region and the canonical form before
the one thing that is actually wrong.

The **stores this deployment may reach** are neither the operator's environment
nor a tenant's setting in the sense above: they are the `network:outbound`
`tcp:` targets of the manifest, compiled into the egress proxy's allowlist
([ADR-0027](../../docs/adr/0027-egress-enforcement.md)). A tenant may name any
endpoint it likes and will get a refusal from the proxy unless the operator
allowed that host. That division is deliberate: the tenant chooses where its data
goes, the operator chooses which places this deployment may reach at all.

## Where a blob lands

`<prefix>sha256/<tenantId>/<aa>/<bb>/<hash>` — the same layout the in-deployment
`blobstore` service writes on disk
([ADR-0032](../../docs/adr/0032-per-tenant-blob-addressing.md)). Identical on
purpose: moving a tenant between the two stores is a copy and never a rename,
and a person looking into either place sees the same thing.

One bucket therefore holds many tenants without their addresses colliding, which
is what makes the deployment-wide default safe to use.

## Uploads

Below 8 MiB an object is one `PUT`. At or above it the upload becomes a
multipart upload of 8 MiB parts, started only once the bytes actually arrive —
the object's size is not known in advance, because the core streams it.

Nothing is held whole. Two parts is the high-water mark, which is what lets this
container fit `REQ-NFR-009`'s per-plugin budget of 64 MB reserved and 256 MB
limit however large the object is.

**The hash is verified as the bytes stream**, and an upload whose content does
not match the address it was given is refused: a multipart upload already begun
is aborted rather than completed. A store that kept those bytes under the
declared address would be content-addressed in name only, and the next read
would return a file that is not the one asked for.

An object that is already there is **not** uploaded again. The address is the
content hash, so a key that exists holds exactly these bytes; the answer is
`created: false` and the tenant's bandwidth is spent on nothing.

## Signing

AWS Signature Version 4, written out rather than taken from an SDK: the AWS SDK
brings a runtime, a credential provider chain, a retry policy and a region
resolver, and a plugin whose only route out is a `CONNECT` tunnel can use none of
them. The implementation is checked against the **published `get-vanilla` test
vector**, which is the one independent check available to a signer that cannot
call AWS.

The payload hash costs nothing here: `BlobRef.sha256` already **is** the hash of
the object. It is computed again from the bytes rather than trusted, for the
reason above.

## One connection per request

Deliberate. Keep-alive would save a handshake per part and would mean carrying a
half-read socket between calls, where a response nobody drained becomes the next
request's answer — the classic HTTP/1.1 bug, and one that surfaces as the wrong
object rather than as an error.

## What an answer means

| Answer | Meaning |
|---|---|
| `INVALID_ARGUMENT` | the address is not a SHA-256, or the envelope names a different tenant from the address, or the bytes do not hash to the address |
| `FAILED_PRECONDITION` | installed and not configured for this tenant — the message lists every missing piece at once |
| `NOT_FOUND` | this store does not hold that blob. For an old one that is expected: the grant moved nothing |
| `UNAVAILABLE` | the store or the proxy refused or could not be reached; the store's own `<Code>` and `<Message>` are passed through |

## Building and testing

```bash
cd plugins/blobstore-s3
cargo test
cargo clippy --all-targets -- -D warnings
```

The tests are unit tests: the signature against the published vector, the
configuration merge, the key layout, the query canonicalisation and the error
parsing. There is no test against a live S3 in this crate — the port's behaviour
against the core is held by `TenantBlobStoreIT` in `app/`, which runs a real
plugin over a real TLS socket.
