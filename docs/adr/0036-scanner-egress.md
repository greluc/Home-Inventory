# ADR-0036 — The malware scanner gets a route out, and the egress proxy runs in every profile

**Status:** Accepted · **Date:** 2026-09-11
**Amends:** [ADR-0024](0024-malware-scan.md), [ADR-0027](0027-egress-enforcement.md),
[06 §6.10](../architecture/06-deployment-view.md)

## Context

[ADR-0024](0024-malware-scan.md) makes the malware scan mandatory and
**fail-closed**, and says of ClamAV: *"it runs, like any plugin, rootless in the
`plugins` network segment, with outbound access permitted only to the signature
mirror."* [13 §13.8](../architecture/13-operations-and-observability.md) schedules
`freshclam` daily and states why — *"a scanner with stale signatures gives false
confidence"* — and `REQ-SEC-093` makes signature age above 48 hours an alert, at
stage 0.

**None of that was reachable.** Three separate facts made the signature mirror
unreachable, and each one alone was enough:

| Fact | Where |
|---|---|
| The `plugins` segment is `internal: true` and plugin containers get no gateway; the only route out is the `egress-proxy` | [ADR-0027](0027-egress-enforcement.md), [`deploy/services.yaml`](../../deploy/services.yaml) |
| The `egress-proxy` ran in `profiles: [standard, ha]` only. In `minimal` it does not exist | `deploy/services.yaml` |
| The proxy's allowlist is generated *"from the `capabilities[].hosts` entries of every **granted** plugin manifest"*. ClamAV is not a plugin and has no manifest, so it had no entry in **any** profile | [ADR-0027 §2](0027-egress-enforcement.md) |

Add the stage plan and it gets worse: the plugin runtime and the proxy arrive in
stage 1 ([ADR-0028](0028-plugin-runtime-stage-1.md)), while `REQ-SEC-091`,
`REQ-SEC-092` and `REQ-SEC-093` are stage 0. **Stage 0 would ship a mandatory,
fail-closed scanner whose signature database freezes at the image build date** —
and an alert that fires forever, which is how alerts get muted
([13 §13.1](../architecture/13-operations-and-observability.md)).

The claim in [06 §6.10](../architecture/06-deployment-view.md) that *"`minimal` is
now genuinely self-contained … it opens no connection outside the deployment at
all"* contradicted ADR-0024 directly. One of the two had to go.

## Options

| Option | For | Against |
|---|---|---|
| **The proxy runs in every profile and carries a fixed deployment allowlist entry for the mirror** | The scan stays fail-closed *and* current in every profile and every stage · one enforcement point, one access log, the property stays testable · hostname allowlisting is exactly what a CDN-fronted mirror needs | `minimal` gains a container (32 MB) and loses the "opens nothing outward" sentence · the allowlist gains a second source, which must be visibly bounded |
| `minimal` freezes its signatures | Keeps `minimal` perfectly isolated | A mandatory, fail-closed scanner with a build-date signature set is worse than no claim at all. `REQ-SEC-093` would have to be disabled per profile, and the one profile a newcomer starts with would be the one with the weakest scan |
| Signatures only through image updates | No egress at all, anywhere | Ties signature freshness to the operator's upgrade cadence, which [06 §6.12](../architecture/06-deployment-view.md) deliberately makes a rare, manual act (`AutoUpdate=disabled`). Daily signatures and deliberate, digest-pinned image updates are incompatible goals |
| Give ClamAV a manifest and treat it as a plugin | Reuses the existing mechanism unchanged | It is not a plugin: it is in-core infrastructure with no capability grant, no per-tenant consent and no lifecycle. Inventing a manifest for it would make "a manifest means a tenant consented" false |

## Decision

### 1. The `egress-proxy` runs in **every** profile

`profiles: [minimal, standard, ha]`. It is no longer a consequence of running
plugins; it is the deployment's single outbound chokepoint, and the deployment
has exactly one outbound need even with no plugin installed.

### 2. The allowlist has two sources, and the second one is closed

| Source | Contents | Changes when |
|---|---|---|
| **Granted plugin manifests** | `capabilities[].hosts` and `capabilities[].tcp` of every granted manifest, per plugin ([ADR-0027](0027-egress-enforcement.md)) | a tenant administrator grants or revokes |
| **The deployment allowlist** | A fixed list shipped with the proxy image, one entry per in-deployment service that needs a named external target. **Today it has exactly one entry: the ClamAV signature mirror.** | only by a change to this repository, reviewed like any other |

The second list is **closed in the same sense as the instance-wide table list in
[07 §7.1](../architecture/07-data-model.md)**: adding an entry requires a row
here and a sentence saying why the service cannot be a plugin. That is
deliberately more friction than adding a manifest host, because this is the one
list no tenant ever consents to.

### 3. `freshclam` reaches the mirror only through the proxy

ClamAV sits on its own segment ([ADR-0037](0037-per-plugin-network-segments.md))
with no gateway, exactly like a plugin. `freshclam` is pointed at the proxy
through its `HTTPProxyServer`/`HTTPProxyPort` settings — it does **not** read
`HTTPS_PROXY` from the environment the way the plugin SDKs do, which is the one
place this service differs from a plugin and the reason it is written down.

**How the configuration gets in** (decided 2026-09-11, closing **A6**): the
generator produces `freshclam.conf` from this ADR's proxy setting and delivers it
through the runtime's **file-mount mechanism** — `podman secret … type=mount`, a
Docker secret, a Kubernetes `ConfigMap` — mounted read-only at
`/etc/clamav/freshclam.conf`. It is not a secret and is not treated as one in
the repository; what is borrowed is only the delivery path, because it is the one
mechanism all three runtimes have for getting a file into a container with a
read-only root filesystem and no bind mounts ([ADR-0022](0022-rootless.md)).

> **Why not a thin first-party image**, which would have been the tidier shape and
> matches `web` and `egress-proxy`: it would mean maintaining a build on top of an
> upstream image whose *daily* rebuilds are the whole point. Every upstream
> signature-relevant release would need our rebuild, digest, `trivy` scan and
> `cosign` signature before an operator could take it — a supply-chain step
> standing between the scanner and its updates, which is exactly the dependency
> this ADR exists to remove. A mounted config file has no such coupling.
>
> The cost, stated: a config file rides the secret mechanism under Podman and
> Docker and a `ConfigMap` under Kubernetes, so the three generated artifacts
> differ here in a way most keys do not. The service matrix absorbs that, which
> is what it is for.

### 4. Every attempt is logged, like every other

The mirror fetch appears in the same access log as every plugin call, attributed
to `clamav` rather than to a plugin ID. A failing fetch is therefore visible in
the same place an operator already looks, and the 48-hour signature alert gains a
cause instead of only a symptom.

## Rationale

The alternative that looked cheapest — let `minimal` run on frozen signatures —
fails on the same ground [ADR-0024](0024-malware-scan.md) chose fail-closed in the
first place: *"a malicious file that got through cannot be recalled"*. A scanner
running a year-old signature set is not a weaker version of a scanner; it is a
scanner that reports clean and means nothing, while the system tells the operator
uploads are being checked.

Running the proxy everywhere also removes a category of surprise. Previously the
set of containers changed between profiles in a way that changed a *security*
property, not just a capability — `minimal` had no enforcement point, so if
anything ever did need egress there it would have been given a gateway. Now the
enforcement point is unconditional, and the question "may this reach out?" always
has the same answer in the same place.

The honest cost is the sentence that has to be withdrawn. `minimal` is no longer
a deployment that contacts nothing. It is a deployment that contacts exactly one
host, for exactly one purpose, through the same proxy and the same log as
everything else — and saying so is better than a claim that only held because
nobody had traced the scanner's own dependency.

## Consequences

- **[06 §6.10](../architecture/06-deployment-view.md) is corrected.** "`minimal`
  opens no connection outside the deployment at all" becomes "`minimal` opens
  exactly one, to the signature mirror, through the proxy". The surrounding point
  — that `minimal` needs no remote storage and no mail — stands.
- **`minimal` gains ~32 MB.** The profile budget moves from roughly 3 GB to
  roughly 3.05 GB, which changes nothing about the 8 GB floor for a Raspberry Pi.
- **The proxy moves to stage 0.** It was stage 1 with the plugin runtime; the
  scanner is stage 0, so its enforcement point must be. The plugin-facing half of
  the proxy stays stage 1 — in stage 0 the allowlist has one entry and no manifest
  source at all, which is the smallest possible version of the same component.
- **`REQ-SEC-093` becomes satisfiable in every profile and every stage**, rather
  than being a requirement whose acceptance test could not pass.
- **A new requirement, `REQ-SEC-097`**, states the deployment allowlist and its
  closure, so the second source is verified rather than implied.
- **[ADR-0027](0027-egress-enforcement.md) §2 is amended**: the allowlist source
  is no longer "granted manifests" alone.
- **[09 §9.2](../architecture/09-extensibility-and-plugins.md) is corrected.** It
  said of ClamAV that *"its own signature updates are its business, not the
  core's"* — a sentence that sounded like a boundary and was in fact an unanswered
  question. The updates are the deployment's business, and this is where it is
  answered.
