# 09 — Extensibility and Plugins

## 9.1 The three extension levels

Extending does not automatically mean code. The lowest level that suffices is
always the one chosen.

| Level | Means | Who may | Risk |
|---|---|---|---|
| **1 — Configuration** | Data in the running system | Tenant administrators | low |
| **2 — Out-of-process plugin** | Own container, gRPC/mTLS | Operator (installation), tenant administrator (activation) | medium, bounded by the process and network boundary |
| **3 — In-process plugin** | JAR in an isolated classloader | **operator only**, signed only | high — see 9.7 |

### What level 1 already covers

Configurable without a line of code: item types and their fields, location
categories, value lists, tags and tag groups, roles and permissions, label
templates and label geometries (including new Avery formats), saved searches,
notification and reminder rules, mapping profiles for import and enrichment,
webhook targets, tenant settings.

> This is deliberate: the most common extension wishes of an inventory system —
> "I need a field", "I need a different label format" — must never require a
> software release.

## 9.2 Extension points (ports)

Every port is a narrow, functionally motivated interface. The core knows only the
port, never an implementation.

**"Shipped" now means two different things**, and the column says which
([ADR-0026](../adr/0026-core-outbound-via-plugins.md)):

| Marking | Meaning |
|---|---|
| **in-core** | Part of the core image, always available, opens no socket to a host outside the deployment |
| **first-party plugin** | Built and signed by this project, delivered as its own container, but still a plugin — because it makes an outbound connection. It is installed, granted and revoked like any other |

| Port | Purpose | Example implementations |
|---|---|---|
| `CodeFormat` | Generate a code and claim/parse a raw scan | QR (**in-core**), DataMatrix, Code128, EAN-13, ITF-14, Aztec, GS1 Digital Link, NFC tag |
| `ScanSource` | The origin of a scan | Browser camera (**in-core**), app camera, HID handheld scanner, Bluetooth ring scanner, image file, clipboard |
| `LabelRenderer` | Template + data → a printable artifact | PDF sheet (**in-core**), PNG, ZPL, EPL, Brother raster |
| `PrintTarget` | Artifact → printer | Download (**in-core** — writes to the `BlobStore`, opens nothing), CUPS/IPP, Brother QL over the network, Zebra over TCP 9100, Dymo |
| `LabelMediaProvider` | Contribute label geometries | Avery Zweckform catalogue (**in-core**, it is data), Herma, continuous rolls |
| `MetadataResolver` | Code → metadata | ISBN (Open Library, DNB), EAN/GTIN (Open Food Facts, GS1), MPN, Discogs, TMDB, IGDB |
| `BlobStore` | Binary storage | Filesystem (**in-core**, the default — the adapter is in the core image and writes to the in-deployment `blobstore` service, which holds the volume so `api` and `worker` stay stateless; the same shape as the ClamAV row below, and not a plugin, because it opens nothing outside the deployment, [ADR-0043](../adr/0043-blobstore-as-its-own-service.md)) · S3/MinIO and **Nextcloud/WebDAV** as **first-party plugins** · FTP, Backblaze |
| `SearchIndex` | Index and search | OpenSearch (**in-core** — part of the deployment, not an external host), PostgreSQL (**in-core**), Meilisearch, Typesense |
| `NotificationChannel` | Deliver a message | **Every channel is a plugin**, because every one of them talks to a host outside the deployment: SMTP, webhook, Web Push/VAPID, Firebase, APNs (all **first-party plugins**) · ntfy, Matrix, Signal |
| `IdentityProvider` | Federated login | OIDC (**first-party plugin**), LDAP, SAML |
| `ImageProcessor` | Image derivatives | libvips (**in-core**), ImageMagick |
| `VirusScanner` | Check uploads | **ClamAV (in-core adapter, mandatory)** — `clamd` is part of the deployment, so the adapter opens no external connection. Its **signature updates** are the deployment's business and not "its own", as this row used to claim: `freshclam` reaches the mirror through the `egress-proxy`, which for that reason runs in every profile and carries one fixed allowlist entry ([ADR-0036](../adr/0036-scanner-egress.md)). `clamd` sits on the `scanner` segment with `worker`, out of reach of any plugin ([ADR-0037](../adr/0037-per-plugin-network-segments.md)). Other scanners are interchangeable, but "no scanner" is not a supported configuration |
| `ValuationProvider` | Estimate current and replacement value | Straight-line depreciation (**in-core**, pure arithmetic), declining balance, market-price and dealer services (plugins — they call out) |
| `ImportMapper` | Read a foreign format | CSV profile (**in-core**), Homebox, InvenTree, Snipe-IT |

**Admission criterion for a new port:** there must be at least two plausible,
genuinely different implementations. A port with exactly one conceivable
implementation is not an abstraction but ballast.

## 9.3 The manifest

Every plugin brings a signed manifest. It is the basis for registration,
permission granting and compatibility checking.

```yaml
apiVersion: home-inv.plugin/v1
metadata:
  id: de.greluc.homeinv.plugin.isbn        # reverse domain, globally unique
  name: "ISBN metadata"
  version: "1.4.2"                          # SemVer of the plugin
  vendor: "greluc"
  license: "Apache-2.0"
  homepage: "https://github.com/greluc/homeinv-plugin-isbn"
  descriptions:
    de: "Löst ISBN-10 und ISBN-13 über Open Library und die DNB auf."   # spelling-exempt: a manifest's own multilingual DATA, which is exactly what this example is showing
    en: "Resolves ISBN-10 and ISBN-13 via Open Library and DNB."

spec:
  contract: ">=1.0.0 <2.0.0"                # supported contract versions
  runtime: out-of-process                   # out-of-process | in-process
  implements:
    - port: MetadataResolver
      schemes: [ISBN10, ISBN13]
      priority: 100

  capabilities:                             # each granted individually
    - id: network:outbound
      hosts: ["openlibrary.org", "services.dnb.de"]     # HTTPS via CONNECT
      reason: "Querying the metadata sources"
    # A non-HTTP target is declared as host:port with its protocol; the proxy
    # forwards raw TCP to exactly that destination (ADR-0027). plugin-smtp uses
    # this form:
    #   - id: network:outbound
    #     tcp: ["mail.example.org:587"]
    #     reason: "Delivering invitations and notifications"
    - id: core:item:read
      reason: "Read existing fields so that only gaps are proposed"

  settings:                                 # surfaced in the UI
    - key: dnbApiToken
      type: secret
      required: false
      label: { de: "DNB-Zugangstoken", en: "DNB access token" }
    - key: preferredSource
      type: enum
      values: [openlibrary, dnb]
      default: openlibrary

  health:
    endpoint: /healthz
    intervalSeconds: 60

  resources:                                # guidance for the operator's limits
    memoryMiB: 128
    timeoutSeconds: 5
```

| Field | Security meaning |
|---|---|
| `capabilities` | **Exhaustive.** What is not in the manifest is not possible — not even with consent granted. An extension requires a new manifest and new consent. |
| `network:outbound.hosts` | A fixed target list. It is compiled into the allowlist of the **egress proxy** that is the plugin's only route outward — no container runtime can express a hostname rule by itself, so the proxy is the enforcement point ([ADR-0027](../adr/0027-egress-enforcement.md)). A host not listed here is refused **and logged**, which is what makes REQ-ENR-009 answerable from evidence rather than from the manifest. |
| `contract` | A range, not a point. The core rejects a plugin outside its range at startup without failing itself. |
| Signature | Manifest and artifact are signed (`cosign`). Unsigned plugins are permitted only if the operator explicitly enables that — with a permanent warning. |

## 9.4 The capability model

A plugin has **no** permissions at all until they are granted. There is no base
entitlement, no "read access, which is harmless anyway".

| Capability | Permits | Note |
|---|---|---|
| `core:item:read` | Reading items of the granting tenant | Field visibility applies here too: `sensitive` fields are removed |
| `core:item:write` | Modifying items | Changes appear in the audit log **with the plugin as the actor** |
| `core:location:read` / `:write` | Locations | |
| `core:media:read` / `:write` | Media | Writing only through `StoreBlob`, never directly into the store |
| `core:event:emit` | Raising its own events | Only from a declared list of event types |
| `core:event:subscribe` | Receiving events | Only the types named in the manifest |
| `core:setting:read` | Reading its own settings | **Its own only**, never anyone else's |
| `network:outbound` | Outbound network connections | Only through the egress proxy, only to the manifest's hosts. Enforced outside the plugin, never by the plugin ([ADR-0027](../adr/0027-egress-enforcement.md)) — an allowlist that untrusted code applies to itself is documentation, not a control |
| `ui:panel` | Its own area in the UI | Served in an isolated `iframe` with its own origin and a strict CSP. The panel needs **no** `Cross-Origin-Embedder-Policy` of its own: the application does not set COEP ([ADR-0040](../adr/0040-no-cross-origin-isolation.md)), which was the one requirement a third-party author could not have guessed and would have met as a blank frame |
| `print:target` | Appearing as a print target | |

**Granting:**

```mermaid
stateDiagram-v2
    [*] --> Installed: operator provides the container
    Installed --> Registered: manifest read, signature verified,<br/>contract version matches
    Registered --> Consent: tenant administrator sees<br/>the capabilities in plain language
    Consent --> Active: granted (logged, with person and time)
    Consent --> Registered: declined
    Active --> Disabled: manually, or circuit permanently open,<br/>or signature became invalid
    Disabled --> Active: re-enabled
    Active --> Revoked: capability withdrawn
    Revoked --> [*]
```

Capabilities are granted **per tenant**. An installed plugin is active for tenant
A and invisible to tenant B until B's administrator consents.

A manifest change never escalates silently. When a new version asks for a
capability the tenant has not agreed to, **what was granted stays granted and the
new one is simply not granted** — the plugin carries on with the capabilities it
has, and a call needing the new one fails visibly at that call. *This paragraph
said the change "resets the consent — the plugin continues with the old
capabilities or is paused" until 2026-09-14; the two readings were decided in
favour of the first with the owner, because a typo-fix release must not disable a
working plugin for every tenant while somebody finds time to look at it.* A grant
also stops meaning anything once the capability leaves the manifest: `permits` is
false for a capability the **current** manifest no longer declares, so a grant
that outlived what it was for is a leftover rather than a permission.

Consenting is a **tenant administrator's** act and reading is a member's, over
`GET /api/v1/plugins`, `GET /api/v1/plugins/{id}` and
`PUT`/`DELETE /api/v1/plugins/{id}/capabilities/{capability}`. The listing shows
both halves at once — what the manifest asks for and what this tenant has agreed
to — because "what it wants" without "what it has" is not a decision anybody can
take. Consent to a capability the manifest does not declare is **refused** rather
than recorded: it would otherwise be waiting as a permission if the plugin asked
for it later. There is deliberately **no endpoint that installs anything** —
installation is an operator's act outside the running system (REQ-PLG-013).

## 9.5 Out-of-process: the normal case

```mermaid
graph LR
    subgraph Core["app (core)"]
        REG["ExtensionRegistry"]
        CB["Resilience:<br/>deadline · circuit breaker ·<br/>bulkhead · retry"]
        GS["CapabilityGuard"]
    end
    subgraph Plugin["plugin-host (own container)"]
        SDK["Plugin SDK"]
        IMPL["Implementation"]
    end
    REG --> CB --> |"gRPC/mTLS<br/>deadline · 8 MB"| SDK --> IMPL
    IMPL -.->|"CoreApi<br/>every call checked"| GS
```

| Protection | Implementation |
|---|---|
| Process boundary | Own container, **rootless**, own user, `read_only`, `cap_drop: ALL`, `no-new-privileges`, memory and CPU limits. An escape lands as an unprivileged user on the host, not as `root` — see [ADR-0022](../adr/0022-rootless.md). |
| Namespace boundary | Each plugin gets its **own UID range** where feasible (`UserNS=auto`), so two plugins cannot see each other even at host level |
| Network boundary | **Its own network segment**, holding exactly the plugin, `api`, `worker` and `egress-proxy` ([ADR-0037](../adr/0037-per-plugin-network-segments.md)). No access to PostgreSQL, OpenSearch, RabbitMQ, Valkey or ClamAV; no access to the core's management endpoints, which bind to `internal` only; **no access to any other plugin**, including to that plugin's forwarding port on the proxy. Outbound only to the hosts named in the manifest. Until 2026-09-11 this row said "its own network segment" while the topology had one shared `plugins` segment, and every clause after the first was weaker than it read |
| Identity | Its own mTLS certificate; the fingerprint is recorded in the registration and checked on every connection |
| Deadline | A deadline per call; exceeding it aborts and counts towards the circuit breaker |
| Bulkhead | A bounded, dedicated concurrency pool per plugin — a slow plugin consumes no core resources |
| Circuit breaker | Opens above an error rate; calls then fail immediately and visibly instead of piling up |
| Observability | Duration, result, payload size and errors per call as metrics and in the plugin log |
| Language | **Any.** Java, Kotlin, Python, Go, Rust, Node — the contract is protobuf. |

## 9.6 The plugin calling the core

A plugin may call the core, but narrowly bounded:

1. The connection identifies the plugin through mTLS.
2. Every call carries a short-lived token issued for **plugin + tenant +
   capability**. No trust is derived from the connection alone.
3. `CapabilityGuard` re-checks on **every** call: is the capability still granted
   for this tenant? Is the plugin still active?
4. Data access goes through the same authorization and the same RLS as user
   access — a plugin cannot see more than the role it acts under.
5. Every write appears in the audit log with the plugin as the actor.

## 9.7 In-process: the exception, and why it stays one

Since Java 24 there is **no functioning `SecurityManager`**. There is therefore no
effective way on the JVM to take privileges away from loaded code: an in-process
plugin can read files, open network connections, reach into the core by
reflection and call `System.exit()`. A dedicated classloader separates
namespaces, **not privileges**.

From this follows, without an escape:

| Rule | |
|---|---|
| Default | In-process is **off** |
| Admission | Only by the operator, only for signed artifacts whose signing key is on an explicit trust list |
| No tenant path | A tenant administrator **cannot** install in-process plugins — otherwise tenant separation would be defeatable by a plugin |
| Visibility | The administration UI permanently shows that in-process code is running, with publisher and fingerprint |
| Isolation, as far as possible | A dedicated classloader with a parent filter: the plugin sees only `de.greluc.homeinv.plugin.api.*` and the JDK base, not the core implementation |
| Reason to use it | Latency and call volume only — e.g. code generation for 5 000 labels, where 5 000 gRPC calls would be disproportionate |

## 9.8 The plugin SDK

What is provided:

| Artifact | Content |
|---|---|
| `homeinv-plugin-api` (Maven) | Java interfaces for the ports, carrier types, error types |
| `homeinv-plugin-sdk-java` | gRPC server scaffolding, manifest validation, health endpoint, logging, test helpers |
| `homeinv-plugin-sdk-kotlin` | The same, as its own artefact with an idiomatic API — **coroutines rather than futures**, Kotlin types, its own scaffold. Not "the Java one works from Kotlin": that is true and is not an SDK |
| `homeinv-plugin-sdk-rust` | The same again, on `tonic`. The deployment already runs two first-party Rust services ([ADR-0050](../adr/0050-blobstore-service-in-rust.md)), so the toolchain, the base image and the release path exist before the first plugin needs them |
| `homeinv-plugin-sdk-python` | The same for Python — because metadata and device integrations often already exist there |
| `homeinv-plugin-sdk-go` | The same for Go — a single static binary in a `scratch` image is the shape a plugin wants, and gRPC is its native idiom |
| `home_inv.plugin.v1` (`buf` module) | Protobuf definitions for every other language |
| `homeinv-plugin-testkit` | **Contract tests**: a suite every port implementation must pass (correct error handling, deadlines, idempotency, behaviour on missing capabilities) |
| Example plugins | Complete and runnable, in the repository, and spread across the SDK languages rather than clustered in one: a `MetadataResolver` (Kotlin), a `PrintTarget` (Python), a `StorageAdapter` (Rust), a `WebhookTarget` (Go), a `CodeFormat` (Java, in-process). Stage 3's "done when" is an **external** author working from the published SDK alone, and an SDK whose only worked example is in another language is not that |

**This is how a plugin comes about:**

```bash
# 1. Scaffold it — `--lang` is one of java, kotlin, rust, python, go
homeinv-plugin init --port MetadataResolver --lang kotlin --id com.example.mpn

# 2. Implement: exactly three methods

# 3. Run it against the contract tests
./gradlew pluginContractTest

# 4. Test locally against a running instance
homeinv-plugin dev --core localhost:8080 --tenant dev

# 5. Build, sign, ship
docker build -t example/mpn:1.0.0 . && cosign sign example/mpn:1.0.0
```

## 9.9 Distribution and discovery

| Path | Description |
|---|---|
| In-core | Contained in the core image, always available, opening nothing outward: QR, PDF sheet, the download print target, the filesystem `BlobStore`, OpenSearch and PostgreSQL search, libvips, the ClamAV adapter, straight-line depreciation, the CSV mapper, the Avery catalogue |
| First-party plugins | Built and signed by this project, shipped as their own containers because each one calls out: `plugin-blobstore-s3`, `plugin-blobstore-nextcloud`, `plugin-smtp`, `plugin-webhook`, `plugin-webpush`, `plugin-push-fcm`, `plugin-push-apns`, `plugin-oidc` ([ADR-0026](../adr/0026-core-outbound-via-plugins.md)). They are installed, granted and revoked exactly like a third party's — no privileged path, because a privileged path is what would eventually be used for something else |
| Operator installation | A container from a registry, added to the service matrix (whence the Quadlet unit or Compose service, or Helm values); registered in the administration UI with the manifest URL and the certificate fingerprint |
| Directory | A **curated list in the repository** (`PLUGINS.md`) with name, publisher, ports, manifest URL, certificate fingerprint, contract version and licence. Admission by pull request, conditional on passing the contract test suite and a signed manifest. **No** distribution, **no** counter-signature and **no** security assurance by this project — that is stated at the top of the list. Installation remains a deliberate act by the operator. |

**Explicitly not planned:** a "marketplace" with one-click installation from
inside the running system. That would bypass the operator's decision and is the
main route by which plugin ecosystems get compromised.

## 9.10 Example: a DataMatrix plugin from start to finish

| Step | What happens | Core change |
|---|---|---|
| 1 | Implement the `CodeFormatPlugin` service: `Describe`, `Claims`, `Parse`, `Render` | none |
| 2 | Manifest: `implements: CodeFormat`, `priority: 50`, no capabilities (pure computation) | none |
| 3 | Pass the contract tests from the testkit | none |
| 4 | Build the container, sign it, add it to the service matrix | none |
| 5 | Operator registers it, tenant administrator activates it | none |
| 6 | `identification` picks the format up into the resolution chain | none |
| 7 | `labeling` offers DataMatrix in label templates | none |
| 8 | Web and apps show the new symbology, because they **query** the format list rather than knowing it | none |

**Zero core changes.** That is exactly the test for quality goal Q3, and exactly
this sequence runs as an automated test in CI.

## 9.11 Evolution of the plugin SDK contract

| Rule | Value |
|---|---|
| Version | SemVer on `home_inv.plugin.vN` |
| Extension | New optional fields and new methods are minor |
| Break | A new major version `v2`; `v1` stays served for **at least 6 months** |
| Check | `buf breaking` against the published state, blocking in CI |
| Compatibility matrix | Published: which core version serves which contract range |
| Behaviour on mismatch | The plugin is disabled and reported; the core starts normally. **A foreign plugin must never prevent the system from starting.** |
