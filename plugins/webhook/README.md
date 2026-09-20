# plugin-webhook

A notification, signed, POSTed to a URL a tenant chose.

It is a plugin rather than part of the core because it opens a connection to a
host outside the deployment, and `api` and `worker` have no route out at all
([ADR-0026](../../docs/adr/0026-core-outbound-via-plugins.md)). It implements
`NotificationChannel` and `PluginHealth`, and nothing else.

## Configured twice, like every plugin

[ADR-0073](../../docs/adr/0073-a-plugin-is-configured-twice.md): what the
**operator** decides for the installation is this container's own environment;
what a **tenant** decides arrives in the call envelope.

| Setting | Who | Where | Default |
|---|---|---|---|
| `HOMEINV_PLUGIN_PORT` | operator | container environment | `8200` |
| `HOMEINV_MTLS_PLUGIN_FILE` | operator | container environment | `/run/secrets/mtls-plugin-webhook` |
| `HOMEINV_EGRESS_PROXY` | operator | container environment | none — and without it nothing can be delivered |
| `HOMEINV_WEBHOOK_TIMEOUT_SECONDS` | operator | container environment | `10` |
| `signingSecret` | **each tenant** | the call envelope, sealed at rest in the core | none — and without it nothing is sent |
| the target URL | **each tenant** | the subscription's address, which is the call's `recipient` | — |

The **hosts this deployment may reach** are neither: they are the
`network:outbound` capability of the manifest the operator mounts, compiled into
the egress proxy's allowlist ([ADR-0027](../../docs/adr/0027-egress-enforcement.md)).
A tenant that configures a receiver outside that list is refused by the proxy,
with a message that says so.

## What a receiver gets

One `POST`, `Content-Type: application/json`, no redirects followed:

```http
POST /your/path HTTP/1.1
Host: hooks.example.org
Content-Type: application/json
X-HomeInv-Signature: sha256=<hex>
X-HomeInv-Timestamp: <unix seconds>
X-HomeInv-Idempotency-Key: <key>
```

```json
{
  "id": "<idempotency key>",
  "tenantId": "<uuid>",
  "subject": "A warranty is running out",
  "text": "The warranty on your drill ends on 3 October.",
  "html": "",
  "language": "en",
  "attachments": [
    { "fileName": "receipt.pdf", "mediaType": "application/pdf", "bytes": 4096 }
  ]
}
```

**The attachment bytes are not in it.** A webhook is not a file transfer: what
travels is the name, the type and the size, so a receiver knows something was
attached and can ask for it through the API.

### Checking the signature

`HMAC-SHA256` over **`<timestamp>.<body>`** — the value of `X-HomeInv-Timestamp`,
a full stop, then the exact bytes of the body — keyed with the `signingSecret`
that tenant configured, lower-case hex, prefixed with `sha256=`.

**The timestamp is inside the signed material on purpose** (`REQ-API-010`): a
captured request is otherwise a valid request for ever. Reject anything outside
your tolerance window — five minutes is the usual choice — and compare the
digest in constant time.

```python
import hmac, hashlib, time

timestamp = request.headers["X-HomeInv-Timestamp"]
if abs(time.time() - int(timestamp)) > 300:
    abort(401)                      # outside the window: a replay, or a clock to fix

material = timestamp.encode() + b"." + body
expected = "sha256=" + hmac.new(secret.encode(), material, hashlib.sha256).hexdigest()
if not hmac.compare_digest(expected, request.headers["X-HomeInv-Signature"]):
    abort(401)
```

Both headers are **reserved**: a caller cannot set either of them through the
notification's own `headers` map, because a caller that could set the timestamp
could make a replay look fresh.

### What an answer means

| Answer | What the core does |
|---|---|
| `2xx` | delivered |
| `4xx` | **dead-lettered on the first attempt** — the receiver understood and refused, and a retry would be refused the same way |
| `5xx`, a timeout, a refused connection | retried, widening, five times (`REQ-NOTI-005`) |

## What it refuses

* a target that is not `https`, one with credentials in the URL, and one naming
  a private, loopback, link-local or carrier-grade-NAT address — `REQ-SEC-034`,
  checked here as well as at the proxy, because the URL came from a person
  typing into a form;
* a header supplied by the caller that would overwrite the signature, the
  idempotency key or the framing, or that carries a control character;
* **a delivery with no signing secret.** Nothing is sent, and the refusal says
  why. "It worked without a secret" is how a deployment ends up with one.

## Idempotency, and what it does not promise

Delivery is at-least-once, so the same message can arrive twice: the core
retries what it cannot confirm. This plugin remembers the last 10 000
idempotency keys **in memory** and answers a repeat with `deduplicated`, without
sending again.

A restart forgets them. That is stated rather than hidden: the window that
matters is minutes, a process lives longer than that, and a plugin with a
durable store would be a plugin with a database. **A receiver that must not act
twice checks `X-HomeInv-Idempotency-Key` itself** — which the contract asks of
every channel and which no sender can guarantee from outside.

## Building and testing

```bash
cargo test --manifest-path plugins/webhook/Cargo.toml
```

The image is `scratch` with one statically linked binary: no shell, no libc, and
no CA bundle — the public roots are compiled in as data, which is what lets it
verify a receiver's certificate at all.
