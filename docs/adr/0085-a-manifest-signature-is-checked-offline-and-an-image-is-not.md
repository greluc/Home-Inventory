<!--
SPDX-FileCopyrightText: Lucas Greuloch
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR-0085 — A manifest signature is checked offline, and an image is not checked yet

**Status:** Accepted · **Date:** 2026-09-22

**Amends:** [ADR-0006](0006-plugin-runtime.md) — registration gains a verification step, and the
state a plugin reaches depends on what that step found.

## Context

`REQ-PLG-004` asks that *every plugin brings a signed manifest*, with one acceptance criterion:
*an unsigned plugin only with an explicit operator setting*. [`09 §9.3`](../architecture/09-extensibility-and-plugins.md)
put the same thing in its table — *"Manifest and artifact are signed (`cosign`)"* — and
[`09 §9.4`](../architecture/09-extensibility-and-plugins.md)'s state diagram had already drawn
`Installed --> Registered: manifest read, signature verified`.

Nothing verified anything. `deploy/services.yaml` carried a `signed:` boolean per plugin, the
generator copied it into the list the core reads, and the core stored it in a column. Its own
comment said what it was: *"whether the operator verified the signature. Not verified here: `cosign`
is the operator's tool and this is what they report having run."* A claim about a check is not a
check, and an administration surface showing a tick beside it says something nobody established.

Three facts constrain the answer:

1. **`api` has no outbound route** ([ADR-0026](0026-core-outbound-via-plugins.md)). Sigstore's
   keyless flow verifies a certificate against Fulcio and a transparency entry against Rekor, and
   both are network calls. A core that verified keylessly would either not verify or would need the
   one thing the topology exists to deny it.
2. **A manifest is verified where it is used, and an image cannot be.** The manifest is bytes the
   deployment already holds. An image signature lives in a registry beside the image, so checking it
   means reaching a registry — which is a different place, a different moment and a different
   process.
3. **The document the core registers was not the document the publisher wrote.** `generate.py`
   loaded each manifest, substituted `services.yaml`'s `hosts` into its `network:outbound`
   capability, and re-serialised it. No signature could ever have matched that.

## Decision

**The core verifies the manifest offline, against a public key the operator installed, at every
start-up.**

- The signature is the detached base64 `cosign sign-blob` produces — SHA-256 then ECDSA, which the
  JVM verifies with no new dependency. It is committed beside the manifest as
  `plugins/<name>/manifest.yaml.sig` and copied into the generated list.
- The key is **the operator's**, never the manifest's: a document carrying the key that approves it
  is a document approving itself. It is a `cosign-plugin-<name>` secret entry, one per plugin, so
  one publisher's key can be replaced without touching anybody else's.
- **The installed manifest is the publisher's document, byte for byte.** The host substitution is
  gone. The two lists were never the same thing: the manifest's is the publisher's *request*, and
  `services.yaml`'s is the deployment's *grant*, and the grant is what the egress proxy enforces —
  it is compiled straight from `services.yaml` and never from the manifest
  ([ADR-0027](0027-egress-enforcement.md)). Overwriting the request with the grant changed nothing
  about what a plugin could reach and destroyed the only record of what it had asked for.

**Three outcomes, and the two that look alike are kept apart.**

| What was found | `signed` | State | What an operator sees |
|---|---|---|---|
| The signature is over this manifest | true | `REGISTERED` | nothing to say |
| Nobody signed it, and the deployment permits that | false | `REGISTERED` | a standing reason — the permanent warning `09 §9.3` asks for |
| Nobody signed it, and the deployment does not | false | `DISABLED` | the reason, naming `HOMEINV_PLUGINS_ALLOW_UNSIGNED` |
| A signature that does not verify | false | `DISABLED` | the reason, and **no setting runs it** |

An unsigned plugin is one whose publisher did not sign. An *invalid* signature is a document that
does not match the signature travelling with it — it was altered after signing, or the key is not
the one that signed it. Collapsing the two into one boolean is exactly what would let the second
pass as the first, so `state_reason` exists beside `signed` and says which.

**Disabled rather than absent**, for the two that fail. A plugin that simply never appeared leaves
an operator looking at a container that is running and doing nothing, with the reason in a log line
that has scrolled past. The registration is what a surface can show.

**The private key never enters a build.** CI runs `tools/manifest_signatures.py --check`, which
needs the public half; signing is a person's command with a key that person holds. This repository's
own rule is that anything entering a worktree, a CI log or a container volume must be assumed
leaked, and a long-lived signing key is the one secret whose leak cannot be repaired quietly: every
signature it ever made becomes worthless.

**The image is not verified, and this decision says so rather than pretending.** `cosign verify` on
an image needs a registry, and `ci.yml` builds with `push: false` — this project publishes no images
at all. A one-shot service wired in front of `api` today would be a gate in front of a door that does
not exist: it would have nothing to verify, so it would have to default to permissive, which is an
"insecure but running" mode this project does not have. Image verification belongs with publishing,
and is taken up when there is something published to verify. `09 §9.3`'s table row and `09 §9.9`
describe that half and are marked as not built.

## Options

| Option | Why not |
|---|---|
| **Keyless (Fulcio/Rekor) in the core** | Two network calls from the one container that has no outbound route. It would make ADR-0026 false to make REQ-PLG-004 true. |
| **The manifest carries its own key** | A document that approves itself. It authenticates nothing an attacker could not also produce. |
| **One trust list for the whole deployment** | Simpler, and it makes every publisher interchangeable: a key trusted for one plugin would vouch for any other. One entry per plugin is what makes revoking one publisher possible. |
| **Keep rewriting the manifest and sign the rewritten one** | The rewriting depends on the operator's host list, which differs per deployment. No publisher could sign it, so only the deployment could — which verifies nothing. |
| **A signing key as a CI secret** | It would make the signatures real without a person in the loop, and it would put the one unrotatable secret in the one place this repository assumes is leaked. |
| **A permissive image verifier now** | An open follow-up dressed as a feature: a security gate shipped defaulting to off, in front of images that do not exist. |

## Consequences

- The five first-party manifests are **unsigned until the maintainer signs them**, and every
  deployment shows them disabled with a reason that names the command. That is the honest state of a
  project that has published nothing; it becomes a verified signature by running two commands and
  committing their output, with no code or configuration change.
- `deploy/generate.py` is simpler: it reads a manifest and returns it, rather than parsing,
  substituting and re-serialising.
- The generated plugin list grew — a verbatim manifest keeps its comments, and five entries came to
  20 KiB against a reader capped at 64. The cap is 256 KiB, because sixteen plugins is a deployment
  and not an attack.
- An operator who disables a plugin still has that survive a restart, and a plugin disabled by its
  signature comes back by itself when the signature verifies again. The two are told apart by
  whether a reason was recorded, which is why `setDisabled` writes none.
- `signed` keeps its name and its type and changes its meaning: it was the operator's report and is
  now this core's finding. The column comment says so and is dated.
