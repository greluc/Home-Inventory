# 06 — Deployment View

## 6.1 The principle: rootless

> **Every supported way of running this system runs rootless.** There is no path
> that operates a container daemon or a container as `root` on the host.

That is not a recommendation but a constraint of the design
([ADR-0022](../adr/0022-rootless.md)). It acts on two independent levels that are
frequently confused:

| Level | Meaning | Without it |
|---|---|---|
| **Non-root inside the container** (`User=10001`) | The process in the container is not `root` | An application bug acts with every privilege inside the container |
| **Rootless on the host** (user namespace) | Container `root` is an unprivileged user on the host | An escape from the container lands as `root` on the host |

Both are binding. The second is the more important one, and the one almost always
missing from self-hosting guides.

## 6.2 Target environment

| Property | Value |
|---|---|
| Platform | Proxmox VM, Debian stable |
| Sizing | ≥ 4 vCPU, ≥ 8 GB RAM, SSD |
| Kernel | cgroup v2 with systemd delegation (default on current Debian) |
| Container runtime | **Podman ≥ 5.0 rootless with Quadlet** *or* **rootless Docker with Compose v2** |
| Service user | An unprivileged user (e.g. `homeinv`) without `sudo` rights |
| TLS and access | An existing reverse proxy in front of the VM |
| Media storage | An existing Nextcloud instance (WebDAV) |
| Backup | Proxmox snapshots **plus** an application-level, consistent backup |

### Verified Podman versions (as of 2026-09-11)

Quadlet exists from Podman **4.4**, `pasta` as the default rootless network from
**5.0**. Checked against the distributions' package indexes.

**Minimum requirement: Podman ≥ 5.0.**

| Distribution | Podman | Quadlet (≥ 4.4) | `pasta` (≥ 5.0) | Suitability |
|---|---|---|---|---|
| **Debian 13 trixie** (current *stable*) | **5.4.2** | ✓ | ✓ | **target environment — no backport needed** |
| Debian 14 forky / sid | 5.8.6 | ✓ | ✓ | suitable |
| Ubuntu 26.04 LTS (resolute) | 5.7.0 | ✓ | ✓ | suitable |
| **Fedora 45** | 6.1.0 | ✓ | ✓ | suitable |
| Fedora 43 / 44 | 5.8.4 | ✓ | ✓ | suitable |
| **RHEL 10 / CentOS Stream 10 / Rocky 10 / AlmaLinux 10** | 5.6.0 (as of 10.1) | ✓ | ✓ | suitable |
| **RHEL 9.5+ / CentOS Stream 9 / Rocky 9.5+ / AlmaLinux 9.5+** | 5.0 (9.5), 5.2+ (9.6+) | ✓ | ✓ | suitable |
| RHEL family 9.4 and older | 4.9.x | ✓ | ✗ — `slirp4netns` | limited |
| Ubuntu 24.04 LTS (noble) | 4.9.3 | ✓ | ✗ — `slirp4netns` | limited |
| Debian 12 bookworm (*oldstable*) | 4.3.1 | ✗ | ✗ | **unsuitable** |
| Ubuntu 22.04 LTS (jammy) | 3.4.4 | ✗ | ✗ | unsuitable |

*Limited* means: it runs, but with `slirp4netns` instead of `pasta` — functional,
slower on the network. The installation guide says so.

> **About the RHEL family:** container tools come from a *rolling* AppStream there
> that rebases onto the current stable Podman up to four times a year. The Podman
> version therefore follows the distribution's **minor release**, not a fixed
> package version. That is why the guide states the minor release as the floor
> (RHEL/Rocky/Alma **9.5** resp. **10**) rather than a Podman number the operator
> would first have to look up. CentOS Stream runs ahead of the corresponding RHEL
> minor and is therefore always suitable.

### What differs per distribution family

| | Debian / Ubuntu | Fedora / RHEL / CentOS Stream / Rocky / AlmaLinux |
|---|---|---|
| Package manager | `apt` | `dnf` |
| `newuidmap` / `newgidmap` | package **`uidmap`** — only a `Recommends`, must be requested explicitly | part of `shadow-utils`, present in every base install |
| `pasta` | package **`passt`** — only a `Recommends` | a dependency of `podman` |
| `netavark` / `aardvark-dns` | `netavark` is a `Depends`, **`aardvark-dns` is absent** and must be added | both hard dependencies of `podman` |
| User D-Bus | package **`dbus-user-session`** — only a `Recommends` | standard component |
| Mandatory access control | AppArmor | **SELinux, `enforcing` by default** |
| Host firewall | `nftables` / `ufw` | `firewalld` — port 8080 (`web`, the only publisher) must be opened explicitly for the reverse proxy |

**The Debian side is the more error-prone one.** Four of the required packages are
mere recommendations there; on the RHEL side they are dependencies or present
anyway (see the finding below).

### SELinux — and why it causes none of the usual trouble here

On the RHEL family SELinux runs in `enforcing` mode. The classic Podman pitfall
is volume labelling: a **bind mount** from a host directory gets **no** suitable
context from Podman, the container cannot write, and the error looks like a UID
problem. The usual remedy are the mount flags `:z` (shared) and `:Z` (private).

This project is **unaffected**, because a rule adopted for an entirely different
reason already applies: **runtime-managed volumes only, no bind mounts**
([ADR-0022](../adr/0022-rootless.md), derived from UID mapping). Named volumes
are created by Podman itself and labelled correctly in the process. The same rule
thus solves two different problems on two distribution families.

> **A trap that looks like a solution:** Quadlet knows `SecurityLabelDisable=true`.
> It makes every SELinux problem disappear — and an entire layer of defence with
> it. The key is **not** used in this project; a CI check rejects it. Whoever has
> a labelling problem has a bind mount that should not exist.

> **A finding that leaves an installation quietly half-broken:** in Debian,
> `uidmap`, `passt`, `dbus-user-session` and `slirp4netns` are only
> **`Recommends`** of `podman`, not `Depends`. An install with
> `--no-install-recommends` — the norm in minimal images and automated setups —
> therefore yields **no `newuidmap`** (rootless does not start at all), **no
> `pasta`** (networking falls back or is missing) and **no user D-Bus session**
> (`systemctl --user` behaves unreliably). The packages are therefore installed
> **explicitly** and verified at startup instead of relying on recommendation
> resolution.

## 6.3 What rootless enforces

Rootless is not a switch; it has consequences reaching into the design. These
four are already worked in:

| Consequence | How it is handled |
|---|---|
| **No ports below 1024.** An unprivileged user may not bind 80 and 443. | The application listens on **8080** (HTTP) and the reverse proxy terminates TLS. That was the topology anyway; rootless makes it mandatory. A `sysctl net.ipv4.ip_unprivileged_port_start` is **not** required. |
| **Volume ownership goes through UID mapping.** What is `uid 999` inside the container is an ID from the `subuid` range on the host. | All volumes are managed by the container runtime, no bind mounts into host directories. Backup and restore go through the runtime, not through `cp` (see 6.13). |
| **The application must write nothing onto the host.** | Already a requirement: all state lives in PostgreSQL, in the `BlobStore` or in Valkey; the containers' filesystem is read-only. |
| **No privileged capabilities, no host network, no container socket inside a container.** | No shipped service needs any of it. A plugin that demands it is not supported. |

### Host prerequisites

One-time setup, provided as a script in the installation guide:

```bash
# --- Debian / Ubuntu -------------------------------------------------------
# Required packages explicitly — only "Recommends" of podman, see 6.2.
# aardvark-dns does not appear in podman's dependencies at all; without it
# containers do not resolve each other by name — and that is exactly what the
# stack needs (app → postgres via the service name).
sudo apt install --yes podman uidmap passt dbus-user-session netavark aardvark-dns

# --- Fedora / RHEL / CentOS Stream / Rocky / AlmaLinux ---------------------
# netavark, aardvark-dns and passt are hard dependencies of podman here, and
# newuidmap comes from shadow-utils (base install). Plus firewalld.
sudo dnf install -y podman
# ONE port. `web` is the only container that publishes, and it proxies to `api`
# over the internal segment (ADR-0042). This was two rules, 8080 for api and
# 8081 for web, until api stopped publishing.
sudo firewall-cmd --permanent --add-port=8080/tcp --source=<reverse-proxy-CIDR>
sudo firewall-cmd --reload

# --- identical on every supported distribution from here on ----------------
# Service user without a login and without sudo
sudo useradd --create-home --shell /usr/sbin/nologin homeinv

# Namespace ranges (at least 65536 IDs). Some distributions allocate them on
# useradd — the script checks /etc/subuid and only adds what is missing rather
# than overwriting blindly.
sudo usermod --add-subuids 100000-165535 --add-subgids 100000-165535 homeinv

# Without linger the services end at logout and do not start at boot.
# This is by far the most common rootless mistake.
sudo loginctl enable-linger homeinv

# Check: cgroup v2 with delegated controllers
cat /sys/fs/cgroup/user.slice/user-$(id -u homeinv).slice/cgroup.controllers
# expected: cpu io memory pids

# OpenSearch needs this, and it is a HOST setting: a rootless container cannot
# raise it, and the service user has no sudo. Profiles standard and ha only.
echo 'vm.max_map_count=262144' | sudo tee /etc/sysctl.d/99-homeinv-opensearch.conf
sudo sysctl --system
```

Without delegated `memory` and `cpu` controllers the resource limits do not take
effect. The installation script checks this and **aborts with a clear message**
rather than running silently without limits — likewise on a missing `newuidmap`,
a missing `pasta`, a missing `subuid` allocation, a missing `linger`, and (in the
`standard` and `ha` profiles) a `vm.max_map_count` below 262144. Six checks, six
clear messages; none of them a warning that gets overlooked.

> **`vm.max_map_count` is the one root action rootless cannot absorb.** Everything
> else in this design runs as an unprivileged user; this single kernel parameter
> is set once at install time by root and then never again. Hiding that fact would
> not make it go away — it would only move the discovery to the first OpenSearch
> start, where it appears as a stack trace rather than as a prerequisite. The
> `minimal` profile has no OpenSearch and therefore does not need it at all.

## 6.4 Three supported ways of running it

```mermaid
graph LR
    SM["<b>deploy/services.yaml</b><br/>service matrix<br/><i>one source of truth</i>"]
    SM -->|generates| Q["deploy/quadlet/*.container<br/><b>rootless Podman</b>"]
    SM -->|generates| C["compose.yaml<br/><b>rootless Docker</b>"]
    SM -->|validates| H["deploy/helm/<br/><b>Kubernetes</b>"]
    Q & C & H -->|"same smoke suite"| CI["CI: all three"]
```

| Path | Status | For whom |
|---|---|---|
| **Rootless Podman + Quadlet** | **Supported, documented, tested in CI** | Operating on a Linux VM with systemd — the target environment |
| **Rootless Docker + Compose v2** | **Supported, documented, tested in CI** | Anyone already running Docker or preferring Compose |
| **Kubernetes (Helm)** | Supported, tested in CI against `kind` | Operators with a cluster; the prerequisite for high availability |
| Rootful Docker or Podman | **Not supported** | — |
| Bare metal (JAR + systemd) | Not supported; the expected third-party versions are documented | — |
| Nomad | Not supported | — |

### Drift protection

Three separately maintained descriptions of the same topology drift apart
inevitably. The countermeasures:

1. **One service matrix** (`deploy/services.yaml`) describes per service: image
   with digest, role, ports, networks, volumes, environment keys, secrets,
   resource limits, health check, dependencies.
2. **Quadlet units and `compose.yaml` are generated from it** — both are flat and
   mechanically derivable. A hand-written deviation fails a CI comparison.
3. The **Helm chart** is not generated (too different in shape) but **validated**
   against the matrix: same services, same environment keys, same limits.
4. **The same smoke suite** runs in CI against all three: start up, migrate,
   create an item, upload a photo, search, shut down cleanly.

## 6.5 Path A — rootless Podman with Quadlet

Quadlet describes containers as systemd units. There is **no daemon**:
`systemd --user` starts, supervises and stops the containers directly. That is
why this path is named first — the permanently running attack surface of a root
daemon disappears entirely.

```
~/.config/containers/systemd/     (user homeinv)
├── homeinv-edge.network
├── homeinv-internal.network
├── homeinv-scanner.network
├── homeinv-plugin-<id>.network    (one per installed plugin, ADR-0037)
├── homeinv-pgdata.volume
├── homeinv-osdata.volume
├── homeinv-mqdata.volume
├── homeinv-postgres.container
├── homeinv-valkey.container
├── homeinv-rabbitmq.container
├── homeinv-opensearch.container
├── homeinv-clamav.container
├── homeinv-migrate.container       (Type=oneshot; api and worker Require= it)
├── homeinv-api.container
├── homeinv-worker.container
├── homeinv-web.container
└── plugins/…                     (one .container file per plugin)
```

```ini
# homeinv-api.container
[Unit]
Description=home-inv API
Requires=homeinv-postgres.service homeinv-migrate.service
After=homeinv-postgres.service homeinv-valkey.service homeinv-migrate.service
# migrate is Type=oneshot with RemainAfterExit=yes: api starts only after it has
# exited 0. api itself has no DDL credentials — it validates the schema and
# refuses to start against an unexpected one (ADR-0041).

[Container]
Image=ghcr.io/greluc/home-inv@sha256:…          # always by digest, never by tag
                                                 # NB: the image name is set explicitly —
                                                 # derived from the repository it would
                                                 # read ghcr.io/greluc/home-inventory
ContainerName=homeinv-api
AutoUpdate=disabled                              # digest-pinned, no auto-update

Network=homeinv-internal.network                 # NOT edge: api publishes nothing and
                                                 # is reached through web (ADR-0042)
Network=homeinv-plugin-smtp.network              # one per installed plugin (ADR-0037);
                                                 # generated, never hand-listed
                                                 # No PublishPort at all. 8080 is reached by
                                                 # web over `internal`; 8090 (management)
                                                 # binds to the internal address — 13 §13.3

Environment=SPRING_PROFILES_ACTIVE=api,standard
Environment=MANAGEMENT_SERVER_PORT=8090
Environment=MANAGEMENT_SERVER_ADDRESS=%i_internal_addr
Environment=HOMEINV_DB_PASSWORD_FILE=/run/secrets/db-password
                                                 # NO migration credential here —
                                                 # only homeinv-migrate gets it (ADR-0041)
Secret=homeinv-db-password,type=mount,target=/run/secrets/db-password
Secret=homeinv-data-key,type=mount,target=/run/secrets/data-key

User=10001:10001                                 # non-root INSIDE the container
ReadOnly=true
Tmpfs=/tmp:rw,noexec,nosuid,nodev,size=64m
NoNewPrivileges=true
DropCapability=ALL

HealthCmd=/usr/bin/healthcheck
HealthInterval=15s
HealthStartPeriod=90s
Notify=healthy                                   # dependants start only afterwards
LogDriver=journald

[Service]
Restart=on-failure
RestartSec=10
MemoryMax=1536M
CPUQuota=200%

[Install]
WantedBy=default.target
```

Operation — ordinary systemd tooling, nothing container-specific:

```bash
systemctl --user daemon-reload          # after changing the unit files
systemctl --user start homeinv-api
systemctl --user status homeinv-api
journalctl --user -u homeinv-api -f
```

| Advantage over Compose | |
|---|---|
| No daemon | No permanently running privileged component, no socket that confers root equivalence |
| Real dependencies | `Requires=`/`After=` with `Notify=healthy` — start order is reliable, not "wait and hope" |
| Uniform supervision | `systemctl`, `journalctl`, restart rules, resource limits through systemd — the same tool used for every other service on the VM |
| Resource limits | Through the `[Service]` section, i.e. cgroup v2 directly |
| Start at boot | Through `linger` and `WantedBy=default.target`, without a root unit |

### UID mapping and volumes

The classic pitfall. Binding decisions:

| Point | Decision |
|---|---|
| Volumes | **Podman-managed volumes only** (`.volume` units). No bind mounts into host directories — that is where the ownership mess starts. |
| Mapping mode | Fixed **once and documented** during implementation (default mapping or `UserNS=keep-id:uid=…`), and per service, because PostgreSQL, OpenSearch and RabbitMQ use different inside UIDs. The choice is guarded by a test that checks write access and a restart against an existing volume. |
| Separate namespaces per plugin | `UserNS=auto` gives each plugin container its **own** UID range so that two plugins cannot see each other. Whether that is viable rootless with the planned `subuid` size is verified during implementation; otherwise separate users per plugin remain. Since [ADR-0037](../adr/0037-per-plugin-network-segments.md) the **network** boundary is a second, independent one around the same unit, so the two no longer fail together: if `UserNS=auto` proves unworkable, plugins still cannot reach one another. |
| Maintenance access to volumes | `podman unshare` — never `sudo chown` on the storage directories |

## 6.6 Path B — rootless Docker with Compose

```bash
# Setup as the service user, without sudo
dockerd-rootless-setuptool.sh install
export DOCKER_HOST=unix://$XDG_RUNTIME_DIR/docker.sock
systemctl --user enable --now docker
```

`compose.yaml` is the same file for rootful and rootless — it deliberately uses
**no** features that rootless cannot carry:

```yaml
# Binding for every shipped service. `user` is the FIRST-PARTY value; upstream
# images (postgres, opensearch, rabbitmq, valkey, clamav) carry their own uid and
# their own writable paths, declared per service in deploy/services.yaml. The rule
# is "not uid 0", not "uid 10001".
user: "10001:10001"
read_only: true
tmpfs: [/tmp]                  # plus whatever the service declares
cap_drop: [ALL]
security_opt:
  - no-new-privileges:true
restart: unless-stopped        # generated from startOnBoot: true — the Compose
                               # equivalent of Quadlet's WantedBy=default.target
healthcheck: { ... }
deploy:
  resources:
    limits: { cpus: "2.0", memory: 1536M }
logging:
  driver: json-file
  options: { max-size: 10m, max-file: "5" }
```

**Explicitly not used** (and checked in CI): `privileged`, `pid: host`,
`network_mode: host`, `cap_add`, mounting `/var/run/docker.sock`, ports below
1024, bind mounts into system directories, `user: root`.

| Difference from rootful Docker that operators must know | |
|---|---|
| Ports < 1024 | Not bindable — hence 8080 |
| Storage location | `~/.local/share/docker` instead of `/var/lib/docker` |
| Network performance | Slightly lower (user-space networking), irrelevant at this load |
| AppArmor | On some distributions rootless needs its own profile — covered in the installation guide |
| `docker.sock` | Lives under `$XDG_RUNTIME_DIR` and no longer confers root equivalence |

## 6.7 Services and sizing

| Service | Image | RAM (reservation/limit) | Persistence | Networks |
|---|---|---|---|---|
| `web` (**the ingress**) | own, `nginx-unprivileged` | 64 / 256 MB | none | edge, internal |
| `api` | own, distroless JRE 25 | 768 MB / 1.5 GB | none | internal, every `plugin-<id>` |
| `worker` | **the same image** | 512 MB / 1.5 GB | none | internal, scanner, every `plugin-<id>` |
| `migrate` | **the same image**, one-shot | 256 / 512 MB *while running* | none | internal |
| `postgres` | `postgres:18-alpine` | 1 / 2 GB | volume `pgdata` | internal |
| `opensearch` | `opensearchproject/opensearch:3` | 1.5 / 2 GB | volume `osdata` | internal |
| `rabbitmq` | `rabbitmq:4-management-alpine` | 256 / 512 MB | volume `mqdata` | internal |
| `valkey` | `valkey/valkey:8-alpine` | 128 / 256 MB | volume (AOF) | internal |
| `clamav` | `clamav/clamav` | 1 / 1.5 GB | volume `cvddata` (signatures) | scanner |
| `egress-proxy` | own, minimal | 16 / 32 MB | none | **egress**, scanner, and every `plugin-<id>` |
| `plugin-*` | per plugin | 64 / 256 MB | plugin's own | its own `plugin-<id>` |

### The memory budget, and the fact it turns on

| | `minimal` | `standard` |
|---|---|---|
| Services | web, api, worker, postgres, valkey, clamav, egress-proxy | + opensearch, rabbitmq, first-party plugins |
| Sum of **reservations** | ≈ **3.4 GB** | ≈ **5.4 GB** |
| Sum of **limits** | ≈ **6.9 GB** | ≈ **9.4 GB** (+ ~0.25 GB per plugin) |
| VM | ≥ 8 GB | ≥ 8 GB |

**The reservations are the budget; the limits deliberately over-commit.** That is
a choice and not an oversight, so it is stated rather than hidden behind a
"peaks up to 7.5 GB" figure that was not derivable from the table — the earlier
wording implied a ceiling the matrix does not have.

The limits are per-service ceilings sized for *that service's* worst case: a
label sheet of 500 codes in the worker, an OpenSearch reindex, a large upload
being re-encoded. Those worst cases do not coincide — `worker` rendering labels
is not also `api` under peak read load — and sizing every limit so the sum fits
8 GB would mean throttling each service to roughly half of what it needs when it
is the one doing work.

What follows from it, and what an operator must know:

- **The sum of limits exceeding RAM means the kernel OOM killer is the last
  resort, not the limits.** Each container is still bounded, so one runaway
  service cannot take the host — which is the property the limits exist for.
- **`vm.overcommit` stays at the distribution default.** No tuning is required
  and none is recommended.
- **The `ha` profile does not over-commit**, because it runs on ≥ 16 GB and
  across several nodes.
- A load test that drives `api`, `worker` and OpenSearch to their ceilings
  *simultaneously* on one 8 GB VM is expected to fail. It is not a supported
  configuration; the sizing row in 6.2 says ≥ 8 GB, not exactly 8.

The egress proxy runs in **every** profile since
[ADR-0036](../adr/0036-scanner-egress.md) — `minimal` has no plugins but does have a
scanner whose signatures have to come from somewhere. First-party plugins add
roughly 0.2–0.4 GB depending on how many are installed; a deployment using the
filesystem `BlobStore` and no mail runs none of them.

`api` and `worker` are **the same container image** with a different Spring
profile. That keeps the version matrix at one and prevents worker and API from
drifting apart.

### Network segmentation

Applies equally to Podman (`netavark`) and Docker:

| Network | Members | Route out of the deployment |
|---|---|---|
| `edge` | **`web` only** — the one container with a published port | **yes, and it has to be.** See the measurement below |
| `egress` | **`egress-proxy` only** — the route the chokepoint needs | **yes, and it has to be** |
| `internal` | database, search, broker, cache, `api`, `worker`, `web`, `migrate` — **and the only segment the management listener binds to** ([13 §13.3](13-operations-and-observability.md)) | **no** (`internal: true` resp. `Internal=true`) |
| `scanner` | `clamav`, `worker`, `egress-proxy` | **only through `egress-proxy`**, and only to the signature mirror ([ADR-0036](../adr/0036-scanner-egress.md)). `api` is deliberately absent: the scan runs in the worker, never in the request path |
| `plugin-<id>` — **one per installed plugin** | that plugin, `api`, `worker`, `egress-proxy`. Nothing else, and never a second plugin | **only through `egress-proxy`**, and only to the hosts the granting plugin's manifest declared ([ADR-0027](../adr/0027-egress-enforcement.md)). Plugin containers get no gateway of their own |

The per-plugin segments replaced a single shared `plugins` network on 2026-09-11
([ADR-0037](../adr/0037-per-plugin-network-segments.md)). On the shared one, any
plugin could read the core's management port, stop the fail-closed scanner, and
use another plugin's forwarding port on the proxy — none of which needs a granted
capability. The proxy now has one interface per segment and identifies the caller
by which interface the connection arrived on.

### Why two segments are not internal — measured, not assumed

This chapter previously marked **every** segment internal, `edge` included, on the
grounds that a published port needs no outward route. That was flagged for
verification as **A5** rather than asserted, and the verification found it false
([ADR-0042](../adr/0042-edge-is-not-internal.md)):

| Topology | `docker port` | Published port from the host | Container outbound |
|---|---|---|---|
| `internal` network only | **empty — no mapping is created** | **unreachable** | blocked |
| ordinary network | mapping present | HTTP 200 | **reaches the internet** |

So *"publishes a port"* and *"has no route out"* cannot both be true of one
container. Exactly two containers therefore sit on a non-internal segment, and
**neither holds data, a credential or domain logic**:

- **`web`** — the ingress. It publishes the single host port, serves the bundle
  and the security headers, and proxies `/api`, `/graphql`, `/c/`, `/.well-known`
  and the SSE stream to `api` over `internal`. `api` publishes nothing and is not
  on `edge` at all, which is stricter than the shape this replaced.
- **`egress-proxy`** — which until the same pass sat only on internal segments and
  so could not make the one call it exists to make. That was the identical defect
  one segment over, and it would have surfaced the first time `plugin-smtp` tried
  to send an invitation.

Measured end to end on that topology before it was written down:

```
host -> web:8080 -> api (inbound path)        HTTP 200
api    outbound                               blocked
plugin outbound                               blocked
egress-proxy outbound                         reaches
web    outbound                               reaches   (the accepted cost)
plugin -> egress-proxy on its own segment     reachable
plugin -> api (the gRPC path)                 reachable
plugin A -> plugin B                          isolated
```

Only Docker was measured; Podman with `pasta` and `kind` remain as CI work. That
does not weaken the conclusion — the service matrix generates **one** topology for
all three, so a shape Docker cannot carry is a shape the design cannot use.

Six properties, all verified in CI by a connectivity test rather than asserted:

- **A plugin reaches PostgreSQL, OpenSearch, RabbitMQ or Valkey not at all.** It
  speaks gRPC with the core and HTTP with the proxy, nothing else.
- **A plugin reaches no other plugin, and no plugin reaches `clamd`.** Two
  plugins are started and each tries the other, and the scanner; every attempt
  must fail.
- **A plugin reaches neither core role's management port.** Port 8090 on `api`
  and on `worker` must refuse from every plugin segment.
- **`api` and `worker` reach nothing outside the deployment.** After
  [ADR-0026](../adr/0026-core-outbound-via-plugins.md) they have no external
  target left to reach: object storage, mail, OIDC, webhooks and push are all
  plugins. The test takes them, tries a known external host, and expects a
  refusal.
- **Only `web` and `egress-proxy` sit on a non-internal segment.** A third one
  appearing there fails the build — that is the assertion that keeps the two
  exceptions from becoming a habit (`REQ-SEC-102`).
- **Exactly one host port is published in the whole deployment.** It was two until
  `api` stopped publishing.

```mermaid
graph TB
    subgraph Internet
        BR["Browser / app"]
    end
    subgraph "Existing infrastructure"
        RP["Reverse proxy<br/>TLS, HSTS, WAF, rate limiting"]
        NC["Nextcloud<br/>WebDAV"]
    end
    subgraph VM["Proxmox VM — user homeinv, rootless"]
        direction TB
        subgraph netEdge["Network: edge — NOT internal, one member"]
            WEB["web = ingress<br/>nginx-unprivileged<br/>publishes 8080"]
        end
        subgraph netInt["Network: internal (no internet access)"]
            APP["api ×1..n<br/>publishes nothing"]
            WRK["worker ×1..n"]
            PG[("postgres:18")]
            OS[("opensearch")]
            MQ[["rabbitmq"]]
            KV[("valkey")]
        end
        subgraph netScan["Network: scanner"]
            CAV["clamav"]
        end
        subgraph netP1["Network: plugin-isbn"]
            PH1["plugin-isbn"]
        end
        subgraph netP2["Network: plugin-blobstore-nextcloud"]
            PH2["plugin-blobstore-nextcloud"]
        end
        subgraph netP3["Network: plugin-smtp"]
            PH3["plugin-smtp"]
        end
        subgraph netEgr["Network: egress — NOT internal, one member"]
            EGP["egress-proxy<br/>one interface per segment<br/>allowlist per caller"]
        end
    end
    BR --> RP
    RP -->|"the ONLY published port"| WEB
    WEB -->|"/api /graphql /c/ SSE<br/>over internal"| APP
    APP --> PG & KV & OS & MQ
    WRK --> PG & OS & MQ & CAV
    APP -.->|mTLS| PH1 & PH2 & PH3
    WRK -.->|mTLS| PH1 & PH2 & PH3
    PH1 & PH2 & PH3 --> EGP
    CAV -->|signature mirror only| EGP
    EGP -->|only this container<br/>may reach the internet| Internet
    EGP --> NC
```

Compared with the previous revision of this diagram, two arrows are gone:
`APP --> NC` and `WRK --> NC`. They contradicted the sentence above them and were
the visible half of the finding that produced
[ADR-0026](../adr/0026-core-outbound-via-plugins.md).

Four things changed again on 2026-09-11:

- **The one `plugins` box became one box per plugin plus a `scanner` box.** On the
  shared segment a plugin could reach `clamd`, the core's management port and
  another plugin's forwarding port on the proxy, none of which needs a granted
  capability ([ADR-0037](../adr/0037-per-plugin-network-segments.md)).
- **`clamav` gained an arrow to the proxy.** It was drawn without one while
  ADR-0024 claimed it had outbound access to the signature mirror — the missing
  arrow that made the mandatory scan a scanner with frozen signatures
  ([ADR-0036](../adr/0036-scanner-egress.md)).
- **`api` left the `edge` box and lost its published port.** Every request now
  arrives through `web`, which is the only publisher
  ([ADR-0042](../adr/0042-edge-is-not-internal.md)).
- **`edge` and `egress` are drawn as *not* internal, with one member each.** That
  is the measured correction: a published port on an internal network is never
  wired up, and the proxy on internal segments alone had no way through. Two boxes
  carry the outbound route so that no box holding data has to.

## 6.8 Kubernetes

The Helm chart is maintained as an equal and tested in CI against `kind`.
Rootless applies there too.

| Object | Content |
|---|---|
| `Deployment` api | HPA on CPU and requests/s, `readinessProbe` = `/readyz`, `livenessProbe` = `/livez`, `PodDisruptionBudget` |
| `Deployment` worker | HPA on RabbitMQ queue length (KEDA optional) |
| `securityContext` (pod and container) | `runAsNonRoot: true`, `runAsUser: 10001`, `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`, `capabilities.drop: [ALL]`, `seccompProfile: RuntimeDefault` |
| User namespaces | `hostUsers: false` wherever the cluster version supports it — the counterpart to rootless Podman |
| `PodSecurity` | Namespace with the `restricted` profile in `enforce` mode |
| `NetworkPolicy` | Default `deny-all`, then targeted allowances — mirrors the three networks |
| `ServiceAccount` per plugin | Own account, own `NetworkPolicy`, `automountServiceAccountToken: false` |
| Secrets | `ExternalSecret`/`SealedSecret`; never in the chart |
| `Job` | Migration as a pre-upgrade hook under the migration role — the same one-shot `migrate` service the other two runtimes run, not a Kubernetes special case ([ADR-0041](../adr/0041-migration-as-its-own-service.md)). It is the only workload the migration secret is mounted into |
| `CronJob` | Backup, restore verification, housekeeping, reminder evaluation |

## 6.9 Assessment of the deployment options

| Criterion | Rootless Podman + Quadlet | Rootless Docker + Compose | Kubernetes | Rootful Docker | Bare metal |
|---|---|---|---|---|---|
| Rootless | **native, mature** | **possible, well supported** | via `securityContext`/`hostUsers` | no | n/a |
| Daemon as attack surface | **none** | one, unprivileged | cluster components | one, **as root** | n/a |
| Barrier to entry | low (needs systemd knowledge) | **very low** | high | very low | medium |
| Ongoing operational effort | **low** | low | high | low | medium–high |
| Dependencies and start order | **systemd, reliable** | `depends_on` with health check | complete | as Docker | manual |
| Supervision and logs | **journald, `systemctl`** | own command set | cluster tooling | as Docker | journald |
| Resource limits | **cgroup v2 through systemd** | through Compose | complete | through Compose | systemd |
| High availability | no | no | **yes** | no | no |
| Horizontal scaling | manual (unit templates) | manual (`--scale`) | **automatic** | manual | manual |
| Adoption among self-hosters | growing | **largest** | niche | large | rare |
| CI effort | low | low | medium | — | high |
| **Supported** | **yes** | **yes** | yes | **no** | no |

**Why rootful Docker is no longer supported:** the daemon runs as `root`, and
whoever can reach its socket is effectively `root` on the host. For a system
reachable from the internet that executes foreign plugin code, that is an
enlargement of the blast radius that cannot be justified — especially since both
alternatives run rootless without any loss of function.

**Why bare metal is not supported:** the application is not the problem;
PostgreSQL with `ltree`, OpenSearch, RabbitMQ and Valkey in matching versions
are. That shifts reproducibility problems onto the user and produces bug reports
nobody can recreate.

## 6.10 Operating profiles

So that a small installation does not need the full stack, there are three
profiles. They change **no** application logic, only the active adapters.
**ClamAV is part of every profile** — the malware scan cannot be deselected
([ADR-0024](../adr/0024-malware-scan.md)); it is the reason `minimal` also needs
about 3 GB.

| Profile | `search` | Events | Media | Plugins | RAM (reserved) | Purpose |
|---|---|---|---|---|---|---|
| `minimal` | PostgreSQL adapter | outbox, in-process dispatch | filesystem (in-core) | none — but the `egress-proxy` runs, for the scanner | ~3.4 GB | Small home server, Raspberry Pi 5 **with 8 GB**, development |
| `standard` | **OpenSearch** | **RabbitMQ** | Nextcloud/S3 **via plugin** | `egress-proxy` + `plugin-blobstore-*` + `plugin-smtp` | ~5.4 GB | **The profile of this installation** |
| `ha` | OpenSearch cluster | RabbitMQ cluster | S3 via plugin | as `standard` | ≥ 16 GB | Kubernetes, multiple instances |

> **`minimal` opens exactly one connection outside the deployment, and it is the
> malware scanner's.** This paragraph used to claim it opened none — *"no proxy,
> no plugins"* — and that was true of everything except the one service the same
> profile cannot run without. ClamAV is mandatory in every profile
> ([ADR-0024](../adr/0024-malware-scan.md)) and a scanner whose signatures freeze
> at the image build date reports clean and means nothing. So the `egress-proxy`
> runs here too, with a single fixed allowlist entry for the signature mirror
> ([ADR-0036](../adr/0036-scanner-egress.md)).
>
> The point that survives is the one worth having: **`minimal` still opens nothing
> for the sake of a feature.** No object storage, no mail, no federated login, no
> webhook, no push — what it gives up is invitations and password-reset mail,
> which such an installation typically does not need. Every outbound connection it
> does make passes the same proxy and the same access log as everywhere else.

Moving `minimal` → `standard` is a configuration change plus an index build — no
data loss, no migration.

## 6.11 Configuration and secrets

**Rules:**

1. Configuration comes from environment variables (12-factor); everything has a
   default except secrets.
2. **A missing secret aborts startup with a clear message.** There is no
   generated default key, no "insecure but running".
3. Secrets are passed as **files** (`*_FILE` convention) — as a `podman secret`
   with `type=mount`, a Docker secret or a Kubernetes secret. Never on the command
   line, never as an environment variable holding the value itself.
4. At startup the application logs a **configuration overview with masked
   secrets**.
5. If `HOMEINV_PUBLIC_BASE_URL` differs from a base already printed onto labels,
   the application warns at startup **with the number of affected labels** and
   shows a banner in the administration UI. Startup is not blocked — a domain
   migration should be possible without downtime.

| Variable | Required | Purpose |
|---|---|---|
| `HOMEINV_DB_URL`, `_USER`, `_PASSWORD_FILE` | yes | Application role (**no** DDL rights, **no** `BYPASSRLS`) |
| `HOMEINV_DB_MIGRATION_USER`, `_PASSWORD_FILE` | yes, **on the `migrate` service only** | For Flyway. The one-shot migration service holds it and exits; `api` and `worker` never see it ([ADR-0041](../adr/0041-migration-as-its-own-service.md)) |
| `HOMEINV_DB_MIGRATE_ON_START` | no (`false` on `api`/`worker`, `true` on `migrate`) | The application validates the schema at startup; it does not change it |
| `HOMEINV_JWT_SIGNING_KEY_FILE` | yes | Ed25519 key pair, rotatable (two active keys) |
| `HOMEINV_DATA_ENCRYPTION_MASTER_KEY_FILE` | yes | Master key for envelope encryption ([ADR-0019](../adr/0019-sensitive-field-encryption.md)) |
| `HOMEINV_PUBLIC_BASE_URL` | yes | Base for code resolution, links in e-mails, CORS, cookie domain. **⚠ This value is printed onto every label. Changing it later invalidates every label already printed** — see [10 §10.2.1](10-identification-and-labels.md). |
| `HOMEINV_LEGACY_BASE_URLS` | no | Comma-separated list of former bases under which `/c/{code}` is still accepted, so old labels keep working after a domain change |
| `HOMEINV_MEDIA_BASE_URL` | yes | **A dedicated hostname** for serving media. Prefer a subdomain of the application host: media responses send `Cross-Origin-Resource-Policy: same-site` to stop foreign sites embedding tenant media, and from a **different registrable domain** that same header blocks our own pages — such a host must send `cross-origin` instead, or every image silently fails to load. CORP is enforced independently of `Cross-Origin-Embedder-Policy`, which this deployment does not send at all ([12 §12.9](12-security.md), [ADR-0040](../adr/0040-no-cross-origin-isolation.md)). A startup check warns when the two hosts are not same-site |
| `HOMEINV_PLUGIN_UI_BASE_URL` | no | A wildcard hostname for plugin UI panels, one subdomain per plugin ID (`<plugin-id>.plugins.inv.example.org`). Required only when a plugin with `ui:panel` is installed; without it the capability cannot be granted, and the administration UI says why (`REQ-PLG-012`) |
| `HOMEINV_BLOBSTORE_TYPE` | no (`filesystem`) | `filesystem` / `s3` / `nextcloud` |
| `HOMEINV_TRUSTED_PROXIES` | yes | CIDR list; `X-Forwarded-*` is honoured only from here |
| `HOMEINV_REGISTRATION_MODE` | no (`invite_only`) | `invite_only` / `open` / `closed` |
| `HOMEINV_PROFILE` | no (`standard`) | see 6.10 |

## 6.12 Upgrading

| Step | Podman/Quadlet | Docker/Compose |
|---|---|---|
| Preparation | Take a backup and **verify it restores** (automated) | same |
| Pull images | `podman pull <digest>` | `docker compose pull` |
| Update units | New digests into the `.container` files, `systemctl --user daemon-reload` | New digests into `compose.yaml` |
| Order | Migration → `worker` → `api` → `web`, each `systemctl --user restart`. The order is **enforced**, not merely recommended: `homeinv-migrate` is `Type=oneshot` and both roles `Requires=` it ([ADR-0041](../adr/0041-migration-as-its-own-service.md)) | `docker compose up -d`; `api` and `worker` carry `depends_on: { migrate: { condition: service_completed_successfully } }`, so the same order comes out of the file rather than out of the operator's fingers |
| Rollback | Restore the previous unit revision (versioned in the repository) | Previous `compose.yaml` |

| Rule | |
|---|---|
| N−1 compatibility | Every version runs against the previous database version. That makes a rollback possible without restoring data. |
| Schema evolution | Three steps: (1) expand, write both shapes, (2) switch, read the new shape, (3) clean up in the release after next |
| Plugins | The core checks every plugin's contract version at startup. Incompatible ones are disabled and reported — they do **not** prevent startup. |
| Search index | On an index schema change: new index, switch by alias, remove the old one after confirmation — without a search outage |
| No auto-update | `AutoUpdate=disabled`; images are digest-pinned. An update is always a deliberate act. |

## 6.13 Backup and restore

| Subject | Method | Frequency | Verification |
|---|---|---|---|
| PostgreSQL | `pg_dump --format=custom` **inside the container** (not over the volume) plus WAL archiving | daily full, WAL continuous | weekly automated restore into a throwaway container, then a consistency check |
| Volumes generally | Export through the runtime (`podman volume export`, for Docker through a helper container) — **never** by copying directly out of the storage directory, because the files belong to the `subuid` range and ownership is lost on restore | daily | weekly spot check |
| Blobs (Nextcloud) | Backed up through Nextcloud; plus a manifest reconciliation (every referenced hash exists) | daily | weekly spot check |
| OpenSearch | **no backup** — rebuildable from PostgreSQL | — | rebuild rehearsed quarterly |
| RabbitMQ | No message backup; the outbox is the truth | — | — |
| Valkey | No backup (cache and sessions only; loss means re-login) | — | — |
| Quadlet units / `compose.yaml` | **Versioned in the repository**, not only on the host | on change | part of the deployment |
| Configuration and secrets | Separate from the data backup, encrypted, stored in a different place | on change | annually |

**Recovery objectives:** RPO ≤ 15 min (WAL), RTO ≤ 2 h. A backup that has not
been restored automatically counts as non-existent.

## 6.14 Development environment

| Aspect | Decision |
|---|---|
| Start | One command, ≤ 10 min including the first build — either `make dev-podman` or `make dev-docker`, both rootless |
| Profile | `minimal` by default; OpenSearch and RabbitMQ switchable by profile |
| Test data | A reproducible data set (fixed random seed) with types, a location tree, 5 000 items, images |
| Integration tests | Testcontainers against **the same image digests** as production; runs under Podman (through the Docker-compatible socket) and under Docker |
| Frontend | Vite dev server proxying to `api` |
| Plugin development | An example plugin in Java **and** Python in the repository, startable locally without a container |
| CI | The smoke suite runs against **both** container runtimes and against `kind`; a change that works under only one runtime fails the build |
