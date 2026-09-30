# plugin-smtp

The notification that arrives as an e-mail.

It is the plugin a deployment misses first: an invitation, a password reset and
every security notification of `REQ-NOTI-004` are mail or they are nothing.
Without it an installation says so plainly rather than failing silently, which
is what `REQ-NOTI-002` means by *every channel is a plugin, e-mail included*.

## Configured twice, like every plugin

[ADR-0073](../../docs/adr/0073-a-plugin-is-configured-twice.md): what the
**operator** decides for the installation is this container's own environment;
what a **tenant** decides arrives in the call envelope.

| Setting | Who | Where | Default |
|---|---|---|---|
| `HOMEINV_SMTP_HOST` | operator | container environment | none — and without it nothing is sent |
| `HOMEINV_SMTP_PORT` | operator | container environment | `587` |
| `HOMEINV_SMTP_SECURITY` | operator | container environment | `starttls`; `implicit` for 465 |
| `HOMEINV_SMTP_USER` | operator | container environment | empty, for a server that wants no account |
| `HOMEINV_SMTP_PASSWORD_FILE` | operator | **a mounted secret** | `/run/secrets/plugin-smtp-password` |
| `HOMEINV_SMTP_SENDER` | operator | container environment | none — the envelope sender and the `From:` |
| `HOMEINV_SMTP_EHLO_NAME` | operator | container environment | `home-inventory` |
| `HOMEINV_SMTP_TIMEOUT_SECONDS` | operator | container environment | `30` |
| `HOMEINV_EGRESS_PROXY` | operator | container environment | none — and without it there is no route out |
| `senderName` | **each tenant** | the call envelope | none; the message then carries the address alone |

**The deployment's mail password never passes through the core.** It is a file
mounted into this container, exactly as the database password is for `api`.
`deploy/setup.sh` creates `deploy/secrets/plugin-smtp-password` if it is not
there and never overwrites it — so write your own into that file before the
first start.

The **mail server this deployment may reach** is neither the operator's
environment nor a tenant's setting in the sense above: it is the
`network:outbound` `tcp:` target of the manifest, compiled into the egress
proxy's allowlist ([ADR-0027](../../docs/adr/0027-egress-enforcement.md)). Both
have to name the same server, and `deploy/services.yaml` is where they are kept
in step.

## TLS is not optional

A server that offers no `STARTTLS` is **refused** on the `starttls` setting, and
nothing is sent. A submission that fell back to plaintext would hand this
deployment's mail credentials to anything on the path *and still deliver the
mail*, which is exactly why nobody would notice. There is no flag to allow it: a
mode that exists is a mode somebody runs.

`implicit` is TLS from the first byte, which is what port 465 expects.

## What a recipient gets

A message with `Date`, `Message-ID`, `MIME-Version` and `Auto-Submitted:
auto-generated` — the last one is RFC 3834's way of saying a machine sent this,
which is what stops a vacation responder from writing back to the notification
address and making a second notification.

The body is **base64** whatever it contains. That is not caution about the
content: a UTF-8 body needs `8BITMIME` and not every server offers it, a long
line is folded by servers that then break signatures, and a line beginning with
a full stop ends the `DATA` command. Base64 removes all three.

A message with an HTML part is `multipart/alternative`; one with attachments is
`multipart/mixed` around that. An attachment's name survives a non-ASCII
spelling, because `Beleg Küche.pdf` is an ordinary name.

## What it refuses

* **an address that is not one local part, one `@` and one domain**, or that
  carries a space or a control character. That is a second `RCPT TO` or a second
  header rather than an address, and the core dead-letters it on the first
  attempt: "that is not an address" does not become one by being repeated;
* **a server with no STARTTLS**, as above;
* **an incomplete configuration**, one piece at a time. The health check names
  each missing setting rather than answering "not healthy", because that is the
  diagnosis an operator can do least with (`REQ-PLG-015`).

## What an answer means

| The server said | What the core does |
|---|---|
| `250` to the final dot | delivered; the queue id, when the server gives one, goes in the delivery log |
| `5xx` | **dead-lettered on the first attempt** — permanent, and the other way round from HTTP |
| `4xx`, a timeout, a refused connection | retried, widening, five times (`REQ-NOTI-005`) |

## Idempotency, and what it does not promise

The `Message-ID` is derived from the core's idempotency key, so a retry of the
same message carries the same id and a receiver that deduplicates on it sees
one message. This plugin also remembers the last 10 000 keys **in memory** and
answers a repeat without sending again — and a restart forgets them, which is
stated rather than hidden.

## Building and testing

```bash
cargo test --manifest-path plugins/smtp/Cargo.toml
```

The image is `scratch` with one statically linked binary: no shell, no libc, and
no CA bundle — the public roots are compiled in as data.
