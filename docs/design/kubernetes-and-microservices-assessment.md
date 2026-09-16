# Assessment — Kubernetes as the only runtime, and a full microservices split

**Date:** 2026-09-15 · **Status:** Assessed and closed — **the plan is unchanged.**

> **Outcome, 2026-09-15.** This was read and decided the same day: the system
> stays a **modular monolith** ([ADR-0002](../adr/0002-modular-monolith.md)) and
> keeps **all three ways of running it** — rootless Podman with Quadlet, rootless
> Docker with Compose, and the Helm chart as an equal
> ([ADR-0015](../adr/0015-deployment.md), [ADR-0021](../adr/0021-podman-quadlet.md),
> [ADR-0022](../adr/0022-rootless.md)). **No ADR is superseded and no requirement
> is amended**, because nothing in the corpus changes.
>
> The document stays as the record of the question and of what the answer rested
> on. Two things in it remain live and are worth re-reading before the chart is
> written rather than after: the eight items in Part A.2 that do not translate to
> Kubernetes 1:1 — stage-2 work under `REQ-NFR-052` and `REQ-SEC-087`, unchanged
> by this outcome — and the ten extraction triggers in
> [03 §3.6.4](../architecture/03-solution-strategy.md), none of which has fired.

This is a design brief, not an ADR and not a plan. It answers four questions that
were asked together and that have four different answers. Where it states a
number it was measured or read out of a source on the date above; where a fact
could not be established without building a cluster, it says so rather than
estimating.

Had any of it been acted on, the decisions would have become ADRs
([ADR-0066](../adr/) onwards) and the affected requirements would have been
amended **first** — the rule in [CLAUDE.md](../../CLAUDE.md) that a change must
never silently contradict a requirement applies to a change of this size above
all others, because it touches the two most load-bearing decisions in the corpus.

---

## 0. The questions, and the answers in one paragraph each

| # | Question | Short answer |
|---|---|---|
| **Q-A** | Can the system run on Kubernetes, MicroK8s first? | **Yes, and it is already designed for it.** [ADR-0015](../adr/0015-deployment.md) maintains a Helm chart *as an equal*; [06 §6.8](../architecture/06-deployment-view.md) already specifies the objects; `REQ-NFR-052` and `REQ-SEC-087` are stage 2. The chart is currently empty. Eight things do not translate 1:1 and are enumerated in Part A.2 — one of them, rootless, is a genuine conflict with a stage-0 requirement, not a detail. |
| **Q-B** | Should Podman/Quadlet and Docker/Compose be dropped so Kubernetes is the *only* path? | **Not recommended, and not now.** On a **single node** Kubernetes delivers exactly one capability the other two lack — rolling updates without downtime. High availability, the one thing the project's own comparison table names as Kubernetes' advantage, needs at least three nodes. The cost is 21 requirements, 16 ADRs, 7 of the 14 architecture chapters, 50 files, the integration-test strategy and the ten-minute contributor path. See Part A.4. |
| **Q-C** | Should the modular monolith become a full microservices architecture? | **No, on the evidence available.** [ADR-0002](../adr/0002-modular-monolith.md) and [03 §3.6](../architecture/03-solution-strategy.md) assessed this in full and named ten blocks that may be extracted, each with a trigger. **Not one trigger has fired.** Since the decision, 52 110 lines of code have been written that assume one transaction and one RLS session, so the cost of splitting has gone **up**, not down. Part B. |
| **Q-D** | MicroK8s or Minikube for the homelab? | **MicroK8s.** Minikube's own documentation describes it as a tool for "application developers and new Kubernetes users"; it is a development environment, not a host for a long-running service. k3s is the serious third option and is named rather than glossed. Part C. |

---

## 1. Method, and what this assessment is not based on

**Read:** the whole `docs/` corpus, with [03 §3.6](../architecture/03-solution-strategy.md),
[06](../architecture/06-deployment-view.md), [12](../architecture/12-security.md),
[14](../architecture/14-quality-risks-glossary.md),
[ADR-0002](../adr/0002-modular-monolith.md), [-0015](../adr/0015-deployment.md),
[-0021](../adr/0021-podman-quadlet.md), [-0022](../adr/0022-rootless.md),
[-0026](../adr/0026-core-outbound-via-plugins.md), [-0027](../adr/0027-egress-enforcement.md),
[-0037](../adr/0037-per-plugin-network-segments.md), [-0041](../adr/0041-migration-as-its-own-service.md),
[-0042](../adr/0042-edge-is-not-internal.md), [-0043](../adr/0043-blobstore-as-its-own-service.md),
[-0044](../adr/0044-internal-is-not-a-trust-boundary.md) and [-0045](../adr/0045-wal-archive-volume.md)
read in full.

**Measured** in the working tree on 2026-09-15, not estimated:

| Thing | Figure |
|---|---|
| Java sources in `app/` | 383 files, 52 110 lines |
| Building-block packages under `de.greluc.homeinv` | 17 |
| Test classes / of those, integration tests | 89 / 67 |
| `deploy/services.yaml` | 1 061 lines |
| `deploy/generate.py` | 1 230 lines, rendering 30 files |
| Generated Quadlet files / `compose.yaml` | 28 / 674 lines |
| Smoke suite | 734 lines across three scripts |
| Requirements / ADRs | 427 / 66 |
| Files naming a container runtime (outside `.git`, build output, worktrees) | **50**, of which 24 in `docs/` |
| Requirements naming a container runtime | **21** |

**Verified externally** against primary sources dated 2026 — the full list with
URLs is in Part F. Nothing about Kubernetes, MicroK8s, Minikube or the operators
below is written from memory.

**Not established, and therefore not claimed anywhere in this document:**

- The actual memory footprint of MicroK8s plus this stack on one 8 GB host. No
  cluster was built. Canonical publishes 540 MB for MicroK8s itself; that is a
  vendor figure for the control plane alone, before the CNI, CoreDNS, the ingress
  controller, `metrics-server` and any operator, and before `REQ-NFR-009`'s
  ≈ 5.2 GB of reservations in the `standard` profile. **This must be measured
  before anything is committed to.**
- Whether `hostUsers: false` works on the operator's kernel. It requires Linux
  6.3 or newer; the answer is a `uname -r` away and is not guessed here.
- Whether the connectivity suite's six assertions can be reproduced as
  NetworkPolicy assertions without loss. That is an experiment, not a reading.

---

## 2. Where the system stands today

Three facts frame everything that follows.

1. **Kubernetes is not a new idea in this project.** [ADR-0015](../adr/0015-deployment.md)
   decided a Helm chart maintained "as an equal", tested against `kind`;
   [06 §6.8](../architecture/06-deployment-view.md) lists the objects down to the
   `PodDisruptionBudget`; `REQ-SEC-087` names the `securityContext` keys;
   [`deploy/helm/README.md`](../../deploy/helm/README.md) explains why the chart
   is *validated* against the service matrix rather than generated from it. The
   chart is stage 2 and currently **empty**. So Q-A is largely a question of
   *doing* stage-2 work, not of deciding something new.
2. **The deployment topology is unusually specific**, and that specificity is
   what a migration has to carry: six network segments including one per
   installed plugin, exactly one published host port, exactly two containers on a
   non-internal segment, an egress proxy as the only route outward, a management
   listener bound to one interface, and a connectivity suite that *proves* all of
   it by refusing connections rather than by asserting a flag
   ([ADR-0037](../adr/0037-per-plugin-network-segments.md),
   [ADR-0042](../adr/0042-edge-is-not-internal.md),
   [ADR-0044](../adr/0044-internal-is-not-a-trust-boundary.md), `REQ-SEC-102`).
3. **Stage 1 is only partly built** ([04 Roadmap](../requirements/04-roadmap-and-stages.md),
   status dated 2026-09-14): the plugin runtime does not exist, notifications do
   not exist, OpenSearch is not wired, the audit hash chain is not built,
   import/export is not built, there is no load suite, OpenTelemetry is not
   wired, and `deploy/` carries no backup tooling. Risk **R1** — "scope too large
   for one person" — is rated *high* in the register. Every hour spent on
   infrastructure is an hour not spent there.

---

# Part A — Kubernetes

## A.1 What translates cleanly

Most of it. The application was written against 12-factor rules that Kubernetes
happens to want: configuration from the environment, secrets from files, no local
state, working health endpoints, one image for two roles.

| Today | On Kubernetes | Notes |
|---|---|---|
| `api`, `worker` (same image, different profile) | two `Deployment`s | `REQ-NFR-053` stays true unchanged. HPA on CPU for `api`, on queue length for `worker` (KEDA), exactly as [06 §6.8](../architecture/06-deployment-view.md) already specifies |
| `migrate`, `bootstrap` (one-shot) | `Job`, migration as a pre-upgrade hook | [ADR-0041](../adr/0041-migration-as-its-own-service.md) explicitly says this is *"not a Kubernetes special case"* — the design already anticipated it. `REQ-SEC-101` (only `migrate` and `postgres` see the migration secret) maps onto volume mounts cleanly |
| `web` (nginx-unprivileged, the ingress) | `Deployment` + one `Service` | The single published port becomes one `NodePort`/`LoadBalancer`, or an `Ingress`. Keeping `web` as the only publisher preserves [ADR-0042](../adr/0042-edge-is-not-internal.md) and the CSP delivery of [ADR-0038](../adr/0038-csp-delivery-and-first-paint.md) untouched |
| `postgres` | `StatefulSet`, or **CloudNativePG** | CNPG 1.30 deploys PostgreSQL 18.4 by default. It also solves backup/PITR, which is otherwise work (A.2 ⑤) |
| `opensearch` | `StatefulSet` + Helm chart, or the operator | The OpenSearch Kubernetes Operator reached **3.0 alpha in early 2026** with a beta to follow — not a GA dependency yet (risk R20) |
| `rabbitmq` | RabbitMQ **Cluster Operator** | Maintained by the RabbitMQ team; deploys 4.2.6 by default |
| `valkey` | `StatefulSet`, or the **official Valkey Helm chart** | Published January 2026 by the Valkey project, explicitly as an answer to the Bitnami catalogue change. Supports ACL auth and TLS, which `REQ-SEC-104` needs |
| `blobstore` | `StatefulSet` + PVC | Rust, tiny, mTLS with a pinned fingerprint — unaffected ([ADR-0043](../adr/0043-blobstore-as-its-own-service.md), [ADR-0050](../adr/0050-blobstore-service-in-rust.md)) |
| `clamav` | `Deployment` + PVC for signatures | Fail-closed behaviour is application-side and unaffected |
| `egress-proxy` | `Deployment` per plugin, or a sidecar per plugin pod | This is a **design decision**, not a translation — see A.2 ② |
| Resource reservations/limits | `requests`/`limits` | 1:1. The over-commit of [06 §6.7](../architecture/06-deployment-view.md) becomes a scheduling question rather than a kernel one |
| Health checks | `livenessProbe` / `readinessProbe` | `REQ-NFR-045` already says `/livez` checks nothing external — which is precisely what a liveness probe must do |
| Secrets as `*_FILE` | projected `Secret` volumes | **Better** than today: `REQ-NFR-054`'s one documented exception (`OPENSEARCH_INITIAL_ADMIN_PASSWORD`) stays the only one |
| `internal: true` networks | `NetworkPolicy`, default deny | Requires a CNI that enforces policy; MicroK8s ships Calico, which does. `REQ-SEC-102` and the connectivity suite `deploy/smoke/connectivity.sh` are what would prove it here as they prove it today |
| Hostname-based egress | unchanged: **the proxy** | [ADR-0027](../adr/0027-egress-enforcement.md) *already analysed* that `NetworkPolicy` cannot express hostnames and chose the proxy partly because it "cannot be reproduced under Kubernetes" otherwise. That analysis still holds: FQDN egress selectors are a live upstream proposal (NPEP-133) and a Cilium/Calico extension, not core API |

That last row is worth pausing on: **the most Kubernetes-hostile requirement in
the system was solved two decisions ago, for the right reason, and a migration
inherits the solution.**

## A.2 The eight things that do not translate

### ① Rootless — a direct conflict with a stage-0 requirement

`REQ-SEC-083` reads: *"No container **and no container daemon** runs as `root` on
the host."* [ADR-0022](../adr/0022-rootless.md) is explicit that non-root
*inside* the container and rootless *on the host* are two different properties
and that conflating them is the common error.

**MicroK8s does not satisfy the second half.** Its `kubelite` and `containerd`
run as systemd services configured with `sudo`; the snap is installed
`--classic`. This is not a MicroK8s peculiarity — it is true of essentially every
mainstream distribution. Kubernetes' own answer, `KubeletInUserNamespace`, is
**beta as of v1.37** and ships in Usernetes, rootless k3s (experimental),
rootless kind and minikube-on-rootless-Docker — not in MicroK8s.

What Kubernetes *can* deliver is the property that scenario **S8** actually cares
about — "an attacker escapes from a plugin container; they are an unprivileged
user without `sudo` on the host". **User namespaces went GA in Kubernetes v1.36**
(released April 2026); `hostUsers: false` gives each pod a private 65 536-UID
range mapped to unprivileged host UIDs. Requirements: containerd ≥ 2.0 (MicroK8s
1.36 ships **containerd 2.2.3** and runc 1.4.2), and **Linux 6.3+** for idmap
mounts on the filesystems involved.

So the honest statement is:

> Kubernetes cannot deliver ADR-0022 **as written**. It can deliver most of what
> ADR-0022 was **written for** — the blast radius of an escape — through
> `hostUsers: false`, while the node components themselves keep running as root.

Three ways out, and this is a decision for the repository owner, not a detail:

| Option | Consequence |
|---|---|
| **Amend `REQ-SEC-083`** to separate *workload* rootlessness (mandatory, `hostUsers: false` + `restricted` PodSecurity) from *node-component* rootlessness (out of scope on Kubernetes) | Honest and verifiable. The claim in `REQ-NFR-072` that the operator view shows "evidence that it is rootless" then needs a Kubernetes-specific definition of what that evidence is |
| Use a rootless Kubernetes (Usernetes, rootless k3s) | Keeps the requirement literally true; buys an experimental/beta foundation for a system that is meant to be operable by strangers. Contradicts Q1's own reasoning about choosing the well-travelled safe path |
| Keep Podman/Quadlet as the supported path and treat Kubernetes as the optional one | The status quo of [ADR-0015](../adr/0015-deployment.md), which is what it already says |

**This single point is the strongest argument against making Kubernetes the only
path.** Dropping the other two runtimes would mean that the project's most
prominently stated security property no longer holds anywhere in it.

### ② Per-plugin segments and how the proxy knows who is calling

[ADR-0037](../adr/0037-per-plugin-network-segments.md) replaced source-address
identity with something stronger: one network segment per plugin, the proxy
holding *one interface per segment*, and the caller identified by **which
interface the connection arrived on**. It did so because on a shared segment,
plugin A could use plugin B's forwarding port and reach B's declared destination
— for `plugin-smtp`, the mail server carrying invitation tokens.

A Kubernetes pod has one interface. The mechanism does not exist. Two
translations, and they are not equivalent:

| Translation | Strength | Cost |
|---|---|---|
| One `NetworkPolicy` per plugin + the proxy identifies by **source pod IP** | Back to the shape ADR-0037 rejected, but with a CNI that pins a pod's source address to its endpoint (Calico does). Needs proof, not assumption | Low |
| **One proxy instance per plugin** — a sidecar in the plugin's pod, or its own `Deployment` per plugin | **Stronger than today.** The proxy holds exactly one plugin's allowlist, so "which plugin is calling" is not a question that can be answered wrongly | One container per plugin (16/32 MB each, per [06 §6.7](../architecture/06-deployment-view.md)); a sidecar shares the pod's network namespace with untrusted code, so the sidecar's own listener must be the only thing reachable on it |

The sidecar variant is the natural Kubernetes shape and the one to prototype. It
needs an ADR of its own, because it changes an accepted decision's mechanism.

### ③ Storage — and this is where MicroK8s on one node hurts

`REQ-NFR-062`: *"Runtime-managed volumes only; no bind mounts into host
directories."* `REQ-NFR-066`: volumes are backed up *through the runtime*, never
by copying out of the storage directory. Both come from
[ADR-0022](../adr/0022-rootless.md) and exist because UID mapping on rootless
volumes is risk **R15** — "usually noticed during a restore, i.e. at the worst
possible moment".

MicroK8s' default is the `hostpath-storage` addon, and its own documentation is
blunt: it is for development, volumes are **bound to the node** and cannot be
moved, a volume **can grow beyond the capacity in its claim**, access mode is
`ReadWriteOnce`, and the data sits in `/var/snap/microk8s/common/default-storage`
— a host directory, which is what `REQ-NFR-062` forbids. The addons page says
plainly it is "not suitable for a production environment or clusters".

So on single-node MicroK8s, `REQ-NFR-062` is not met by the default storage class
in spirit (a host directory) and `REQ-NFR-066`'s mechanism disappears
(`podman volume export` has no counterpart). Alternatives — OpenEBS LocalPV,
Longhorn, Ceph/Rook — either re-introduce the same node-binding on one node or
need several nodes to mean anything.

**Consequence:** the backup design changes from volume-level to database-level.
That is arguably an improvement — `pg_basebackup` plus WAL replay, or CNPG's
built-in continuous archiving, is a better fit for `REQ-NFR-014`'s
RPO ≤ 15 min / RTO ≤ 2 h than exporting a volume ever was — but
[ADR-0045](../adr/0045-wal-archive-volume.md) (the `pgwal` volume),
`REQ-NFR-066` and `REQ-NFR-015` (weekly automated restore verification) all have
to be rewritten, and `deploy/` has no backup tooling yet in either shape.

### ④ Start order

`REQ-NFR-067` names the mechanism, not just the goal: `Requires=`/`After=` with
`Notify=healthy` under Quadlet, `depends_on` with a health check under Compose,
verified by ten consecutive cold starts. Kubernetes has neither. It converges:
pods crash-loop until their dependencies answer. `initContainers` and probes
express *some* of it — "run `migrate` to completion before `api` starts" maps
onto a pre-upgrade `Job` cleanly — but "a cold start without startup errors" is
not a property Kubernetes offers, and the requirement's acceptance criterion
would have to become "converges within N seconds, with no error that is not a
retry".

### ⑤ Backup, restore, and the weekly proof

Covered in ③. The affected set: `REQ-NFR-014`, `-015`, `-066`, `-068`,
[ADR-0045](../adr/0045-wal-archive-volume.md), [06 §6.13](../architecture/06-deployment-view.md),
[13](../architecture/13-operations-and-observability.md). None of it is
impossible on Kubernetes; all of it is different, and none of it exists yet in
either shape, which is the one piece of good news — writing it once for
Kubernetes is cheaper than writing it twice.

### ⑥ The assertions that keep the topology honest

`REQ-SEC-102` is enforced by *reading the deployment descriptions* and counting
members of non-internal segments, and additionally by the connectivity suite
running the stack. On Kubernetes there is no "segment membership" to count: every
pod is on the cluster network and reachability is decided by policy. The
assertion has to be re-expressed as *"exactly two workloads have a
`NetworkPolicy` permitting egress beyond the cluster"* — which is checkable, but
it is a different check against a different artefact, and the six properties of
[06 §6.7](../architecture/06-deployment-view.md) each need a Kubernetes-native
formulation.

The smoke suite's value does not change: a refused connection is still evidence
where a flag is a claim. `REQ-NFR-064` already requires it to run against `kind`.

### ⑦ The generator, and what `services.yaml` is for

Today: one matrix generates 30 files, CI compares every one, and risk **R13**
(two hand-maintained topologies diverge) is structurally closed. The Helm chart
is the exception — *"too different in shape to derive"*, so it is validated
instead.

If Kubernetes becomes the only runtime, the generator's 1 230 lines have one
consumer left that it cannot generate. Either the matrix starts generating chart
values (rejected once, for a stated reason), or `generate.py` shrinks to a
validator, or `services.yaml` stops being the source of truth and the chart
becomes it. The third option deletes the mechanism that `REQ-NFR-063` exists to
provide. **R13 disappears** — there would be one description — which is a real
simplification and should be counted on the credit side.

### ⑧ Tests and image builds — the reading that would break the build

The integration strategy is Testcontainers against **the same image digests as
production** (`REQ-NFR-027`, [14 §14.2](../architecture/14-quality-risks-glossary.md)),
and 67 integration tests rest on it. Testcontainers requires a **Docker-API-compatible
runtime**; Podman works with caveats (rootless needs Ryuk disabled). Kubernetes
is not such a runtime.

Likewise image building: Kaniko, the usual in-cluster builder, was **archived by
Google in June 2025**. The live options are BuildKit, Buildah, or — for this
project, most cheaply — Jib, which builds from Gradle with no daemon at all.

So the sentence "we would only use Kubernetes and no Docker/Podman any more" has
to be read as **"Kubernetes is the only deployment target"**. A container runtime
stays on the developer machine and in CI, or `./gradlew build` stops working. Any
other reading would mean replacing the test strategy as well, which is not a
deployment change — it is a second project.

## A.3 What Kubernetes actually buys, on one node

The project's own comparison ([06 §6.9](../architecture/06-deployment-view.md))
gives Kubernetes exactly two wins over the rootless runtimes: **high
availability** and **automatic horizontal scaling**. Measured against a
single-node MicroK8s homelab:

| Claimed benefit | On one node | Evidence |
|---|---|---|
| High availability | **Not delivered.** MicroK8s enables HA only at **three or more nodes** (dqlite quorum). `hostpath` PVs are node-bound, so the data stays on the node that created it | MicroK8s HA docs, hostpath-storage docs |
| Automatic horizontal scaling | Works, and is bounded by the one node's RAM. The load target is 1 M items and a dozen concurrent users — "an order of magnitude below what a single JVM instance handles" | [03 §3.6.1](../architecture/03-solution-strategy.md) |
| **Rolling update without downtime** | **Genuinely new.** Quadlet and Compose restart in place; a `Deployment` with `maxSurge` does not — if there is RAM for a second `api` pod for the duration | — |
| Self-healing / restart on failure | Already delivered by systemd (`Restart=on-failure`) and Compose | [ADR-0021](../adr/0021-podman-quadlet.md) |
| Declarative configuration, CronJobs for housekeeping and backup | Modest gain over systemd timers, which the operator already knows | [06 §6.8](../architecture/06-deployment-view.md) |
| Operators for PostgreSQL / RabbitMQ / OpenSearch | **Real value** — CNPG's PITR alone answers a requirement that has no tooling today. Costs: CRDs, operator versions, and one more upgrade axis in the version matrix | Part F sources |
| "It disciplines the architecture" | Already banked by keeping the chart honest. It does **not** require dropping the other runtimes | [`deploy/helm/README.md`](../../deploy/helm/README.md) |

And the register already carries the counter-entry: **"No high availability"** is
listed in [14 §14.4](../architecture/14-quality-risks-glossary.md) as
*deliberately accepted* technical debt, with the trigger *"more than one
household depends on it operationally"*. A single-node cluster does not change
that trigger; it just adds a control plane underneath it.

## A.4 Dropping Podman and Docker — the separate question

This is not the same question as "does Kubernetes work", and mixing them is how
a migration acquires costs nobody priced.

**What is gained**

- One deployment artefact instead of three. **R13 is closed structurally** — not
  mitigated, closed.
- The CI smoke matrix halves; `generate.py` and `deploy/expected/` lose their
  reason to exist.
- One set of operational documentation instead of "the commands for every way of
  running it" (`REQ-NFR-068`).
- Operators bring real capability the project currently lacks (backup/PITR).

**What is lost**

- `REQ-NFR-051` — two runtimes as equals, *"a fresh installation runs with one
  command on either path"* — is withdrawn.
- **The self-hoster audience.** The project's own table rates Kubernetes adoption
  among self-hosters *niche* and Compose *largest*, and the whole point of
  [ADR-0015](../adr/0015-deployment.md) was that third parties can run an AGPL
  system on a Proxmox VM.
- **M6, the ten-minute local start.** A contributor would need a cluster. `kind`
  and `minikube` themselves run on Docker or Podman, so the requirement does not
  even become "install Kubernetes" — it becomes "install a container runtime
  *and* Kubernetes *and* Helm".
- The three-runtime matrix that has already earned its keep: the measurement that
  produced [ADR-0042](../adr/0042-edge-is-not-internal.md) — `edge` is not
  internal, `web` is the ingress — came from running the topology, and
  [06 §6.7](../architecture/06-deployment-view.md) notes it was measured on
  Docker only with Podman and `kind` still outstanding. Narrowing to one runtime
  removes a class of discovery, on a topology where that class of discovery has
  found real defects twice.
- `REQ-SEC-083` as written (A.2 ①).

**Scope of the edit**: 21 requirements, 16 ADRs, 7 of the 14 architecture chapters, 50
files. Several of those ADRs are *accepted decisions that would need superseding
ADRs*, not text edits: 0015, 0021, 0022, 0027 §2, 0037, 0041, 0042, 0044, 0045.

**A middle path exists and costs almost nothing:** make Helm the *primary*
documented path, keep **one** container-runtime path (Compose, the one with the
largest reach) for contributors and small self-hosters, and retire Quadlet only
if it turns out nobody uses it. That keeps `REQ-NFR-051` amendable rather than
withdrawn, keeps M6 intact, keeps Testcontainers' runtime on the developer
machine anyway, and still removes half of R13.

## A.5 Risks specific to this migration

Proposed IDs continue the register in [14 §14.3](../architecture/14-quality-risks-glossary.md),
which ends at R17.

| # | Risk | Impact | Likelihood | Countermeasure | Early warning sign |
|---|---|---|---|---|---|
| **R18** | **The rootless claim quietly weakens.** `REQ-SEC-083` is amended to fit Kubernetes, and "rootless" keeps being stated in the README and the operator view while meaning something narrower | The project's most prominent security claim becomes marketing | **high** if not handled deliberately | Amend the requirement *explicitly and visibly*, with the Kubernetes definition written out; make the operator view state which of the two properties holds | The word "rootless" appears unqualified in a Kubernetes context |
| **R19** | **Single-node Kubernetes is bought for HA and does not deliver it.** Three nodes are needed; the machine remains the single point of failure | Complexity without the benefit it was chosen for | high | Decide explicitly that the purchase is rolling updates and operators, not HA — or plan three nodes | A sentence claiming HA appears in the docs while one node is deployed |
| **R20** | **Operator supply chain.** The OpenSearch operator is at 3.0 **alpha**; Bitnami's public catalogue went legacy on 2025-08-28 and its Docker Hub charts stopped being updated; Kaniko was archived in June 2025; ingress-nginx retires in March 2026 | A dependency of the deployment stops receiving fixes | medium | Prefer first-party charts (Valkey's own, RabbitMQ's operator, CNPG); keep digest pinning; never take a Bitnami image or chart | A chart's upstream has no release in six months |
| **R21** | **The topology's proofs get weaker in translation.** Six connectivity properties become NetworkPolicy assertions; one of them (per-plugin proxy identity) has no direct equivalent | A security property is believed but no longer proved | medium | Port the connectivity suite **before** the chart is called done; keep the "refused connection is evidence" rule | An assertion is replaced by a manifest grep |
| **R22** | **Control-plane overhead eats the memory budget.** `REQ-NFR-009` promises `standard` inside 8 GB at ≈ 5.2 GB reservations; a control plane, CNI, DNS, ingress, metrics-server and operators are added to that | The documented hardware floor becomes wrong | medium | **Measure first.** Publish a separate Kubernetes sizing row rather than reusing the Compose figures | Pods pending on `Insufficient memory` |
| **R23** | **The contributor path breaks.** M6 (≤ 10 min, one command) with a cluster in the loop | Nobody but the author can run the system | medium–high if Compose is dropped | Keep one container-runtime path; or ship a scripted MicroK8s bootstrap and measure it against the ten minutes | The onboarding test is quietly deleted |
| **R24** | **`HOMEINV_PUBLIC_BASE_URL` changes during the migration** (`REQ-IDENT-015`). It is printed onto physical labels, and a printed label cannot be recalled ([10 §10.2.1](../architecture/10-identification-and-labels.md)) | Physical labels stop resolving | low now, **high later** | Keep the hostname identical across the migration; never let an ingress rename it. Relevant from stage 2 onwards — i.e. exactly when the chart is also due | A new ingress hostname appears in a values file |

---

# Part B — Full microservices

## B.1 The decision that exists, and whether the ground moved

[ADR-0002](../adr/0002-modular-monolith.md) and
[03 §3.6](../architecture/03-solution-strategy.md) did not wave microservices
away; they assessed nine criteria and published the finding for each. Re-checking
those findings against today:

| Criterion (03 §3.6.1) | Then | Today | Moved? |
|---|---|---|---|
| Independent scalability | Compute-heavy work is already asynchronous; scales by adding `worker` instances | Unchanged. `api` and `worker` are already separate roles and already scale independently | No |
| Independent delivery by teams | One developer | One developer | No |
| Technology diversity | Everything but plugins belongs on the JVM | Two Rust services exist (`blobstore`, `egress-proxy`) — *at the process boundaries the design already planned* | No — it confirms the design |
| Fault isolation | Only foreign code is genuinely risky, and it is already isolated | The plugin runtime is not built yet; when it is, it is out-of-process over gRPC/mTLS | No |
| Data consistency | *Create item* is six blocks in one `COMMIT` | **Now implemented that way**, including `audit` called synchronously inside the item transaction under one lock (`REQ-SEC-070`) | Moved **against** splitting |
| Operational effort | Part-time single-person operation | Unchanged, and stage 1 is behind | Moved **against** splitting |
| Local developability | ≤ 10 min, one command (M6) | Unchanged requirement | No |
| Attack surface | Every service is an endpoint, a secret, a trust relationship | Unchanged, and Q1 is still the top goal | No |
| Expected load | 1 M items, a dozen users — an order of magnitude below one JVM | Unchanged | No |

**Nothing moved in favour. One criterion moved firmly against**, because the
interwoven core is no longer a design sketch — it is 52 110 lines that assume a
single transaction and a single `SET LOCAL app.tenant_id` per request.

The ten extraction triggers named in [03 §3.6.4](../architecture/03-solution-strategy.md)
— image processing above 50 % CPU permanently, reindexing degrading API latency,
enrichment latency destabilising the core, label rendering needing its own memory
limits, delivery volume justifying its own rate control, mobile connection load,
code-resolution volume competing with the API, long-running tenant exports —
**have not fired, and cannot have fired: the features behind six of them are not
built** (ADR-0002, and the stage-1 status in [04 Roadmap](../requirements/04-roadmap-and-stages.md)).

## B.2 What a split would actually cost

Three candidate cuts, from cheapest to most expensive.

### (a) The one already designed — extract on trigger

Ten blocks are cut so they can leave without restructuring: `media`, `search`,
`enrichment`, `labeling`, `notification`, `sync`, `identification`,
`portability`, each with a named trigger. Cost per extraction: an event contract
that already exists, a port that already exists, one more deployable. **This is
available today and needs no decision** — only a trigger.

### (b) A pragmatic four-way split

`api` · `worker` · a search indexer · a media processor. Note what this is: **it
is roughly what already exists.** `api` and `worker` are separate roles of one
image, and the indexer and media processor are `worker` consumers. Splitting them
into separate *images* buys per-service resource limits (already available per
role) and separate scaling (already available per role). The gain is close to
zero and the version matrix doubles — `REQ-NFR-053` exists precisely to prevent
`api` and `worker` drifting apart.

### (c) One service per building block — 18 deployables

This is the option the question asks about, and it is the one with the concrete
costs. Beyond the three flows already enumerated in
[03 §3.6.2](../architecture/03-solution-strategy.md) (*create item* across six
blocks and one `COMMIT`; *move location*, whose cycle check would span a service
boundary; *delete tenant*, which must take effect demonstrably everywhere),
splitting now also has to answer:

| What it breaks | Why it is not a detail |
|---|---|
| **Tenant isolation's second line** | RLS with `FORCE` and `SET LOCAL app.tenant_id` is per-database-session ([ADR-0003](../adr/0003-multi-tenancy.md)). Eighteen services means eighteen roles, eighteen tenant-context propagations, and the automated isolation proof "across **every** table" becomes eighteen proofs plus a cross-service one that does not exist today |
| **The audit hash chain** | Chained per tenant, and a transaction's several entries must be chained **under one lock acquisition** (`REQ-SEC-070`, [ADR-0031](../adr/0031-audit-chain-per-tenant.md)). `audit` is named *not extractable* for exactly this reason. A chain across a network boundary is a different design with different guarantees |
| **Authorization** | [ADR-0010](../adr/0010-api-surfaces.md): authorization lives **only** in the application layer, and an endpoint without `@RequiresPermission` fails the build. Across services this becomes propagated identity — a token, or a mesh identity — and the build-time guarantee turns into a runtime one |
| **GDPR erasure in one pass** | [ADR-0060](../adr/0060-the-erasure-runs-in-one-pass.md) settles that the erasure runs once, completely. Distributed, it is an orchestrated, resumable, logged multi-step process |
| **Offline sync's three-way compare** | [11](../architecture/11-offline-synchronisation.md) reconciles against a base version with conflict records. The base version would be assembled from several services |
| **Migrations** | One Flyway history per schema today, ordered by one `migrate` job ([ADR-0041](../adr/0041-migration-as-its-own-service.md)). Eighteen services means eighteen migration jobs and an ordering problem across them, plus the N−1 compatibility rule per pair |
| **The plugin boundary** | `api` and `worker` are members of *every* plugin segment and both terminate mTLS on the gRPC port ([ADR-0037](../adr/0037-per-plugin-network-segments.md), `REQ-NFR-061`). Which of eighteen services talks to plugins, and on whose certificate? |
| **The module-boundary machinery** | Spring Modulith + ArchUnit enforce boundaries *at build time* across one codebase (M1, M2, R3). Split, those checks stop existing and the discipline moves to review — which is the thing ADR-0002 chose machine verification to avoid |

## B.3 The honest case *for* splitting

It is short, and it should still be stated:

- **Blast radius of a JVM failure.** Today an OOM in label rendering and an OOM
  in the API path are the same JVM class of event. The register accepts this:
  *"an outage affects all blocks. That is accepted: the availability target is
  99 %, not 99.99 %."*
- **Per-service memory limits.** Real, and it is exactly the `labeling` trigger.
- **Independent release cadence.** Real in principle; no second team to benefit.
- **Technology choice per service.** Already exercised where it matters: two Rust
  services and an out-of-process plugin runtime.
- **Kubernetes makes running many services cheaper than Quadlet does.** True —
  and it is the one way the two questions interact. It lowers the *operational*
  cost of a split; it does nothing about the *consistency* cost, which is the
  one ADR-0002 called decisive.

## B.4 Recommendation on Q-C

**Keep the modular monolith.** Revisit per block, when that block's named trigger
fires, using the extraction path that is already designed for it. If Kubernetes
arrives, the first extraction becomes cheaper — that is an argument for doing
Kubernetes first and splitting later, never for doing both at once.

Splitting now would multiply the surface of a stage-1 backlog that is already
the largest stage in the plan and is only partly built, against risk **R1**,
rated *high*, whose stated early-warning sign is stage 1 overrunning.

---

# Part C — MicroK8s or Minikube in the homelab

The target is **one machine, single node** (as confirmed 2026-09-15).

## C.1 The facts

| | **MicroK8s** | **Minikube** | *(k3s, for reference)* |
|---|---|---|---|
| Stated purpose | Production and edge/IoT as well as development; Canonical markets it for "mission-critical workloads" (vendor claim) | *"Helping application developers and new Kubernetes users"* — its own documentation. A local cluster tool | Lightweight production Kubernetes, edge-first |
| Install / lifecycle | `snap install microk8s --classic --channel=1.36/stable`; transactional snap updates | `minikube start` with a driver (VM, container, bare metal) | single binary + systemd unit |
| Long-running service on a host | systemd services, starts at boot | Cluster lives inside a driver VM/container; `minikube delete` discards it. Not designed as a boot-time service | systemd service |
| Multi-node | Yes; **HA from three nodes** (dqlite) | Yes, `--nodes N`, since 1.10.1 — the Canonical comparison page claims Minikube has none, which its own docs contradict; treat vendor comparisons accordingly | Yes, with an HA datastore |
| Default storage | `hostpath-storage` addon — node-bound, `ReadWriteOnce`, can exceed its claim, "not suitable for a production environment" | host-path provisioner; CSI hostpath driver needed for multi-node | local-path provisioner |
| Ingress | Addon; **Traefik since 1.35**, with Gateway API support; an `nginx` IngressClass is kept for compatibility and routes through Traefik | Addon, ingress-nginx based | Traefik by default |
| CNI / NetworkPolicy | Calico by default, policy enforced — which is what `REQ-SEC-102` needs | Depends on driver and CNI choice; policy is not enforced by default on every setup | Flannel by default; policy via an option |
| Kubernetes version reached | **1.36** (containerd 2.2.3, runc 1.4.2) | tracks upstream closely | tracks upstream |
| Architectures | x86, ARM64, s390x, POWER9 (vendor page) | x86, ARM64, ARMv7, ppc64, s390x (vendor page) | x86, ARM64, ARMhf |

Two external facts that matter to the choice and are independent of both:

- **Upstream Kubernetes is at 1.37** (released 2026-08-26); supported minors are
  1.35–1.37. MicroK8s' newest channel is 1.36. A distribution that trails by one
  minor is normal, but it means the newest features land later — and it is worth
  checking the channel before assuming a feature is available.
- **ingress-nginx retires in March 2026** — no further releases, bugfixes or
  security fixes. MicroK8s already moved its addon to Traefik in 1.35, which is
  one concrete reason to prefer it over a stack still built on ingress-nginx.
  *This has nothing to do with this project's own `web` container, which is
  `nginx-unprivileged` serving the SPA and the CSP headers — a different thing
  entirely. Nobody should "fix" that in response to this.*

## C.2 The recommendation

**MicroK8s**, for three reasons that are about the homelab rather than about
Kubernetes:

1. **It is a host service, not a workspace.** Minikube's model is "start a
   cluster, work in it, delete it". A home inventory that holds real data and is
   reachable from the internet is the opposite of that lifecycle.
2. **It enforces NetworkPolicy out of the box** (Calico). This system's topology
   *is* its network policy — six segments and a proxy — so a distribution where
   policy enforcement is an optional extra is the wrong starting point.
3. **Traefik and Gateway API already, containerd 2.2.3 already.** The second
   makes `hostUsers: false` reachable, which is the one thing that recovers most
   of ADR-0022's intent (A.2 ①).

**k3s deserves a look before committing.** It is the other serious homelab
distribution, has an experimental rootless mode that MicroK8s does not, and does
not put the cluster behind a snap. The reason not to lead with it here is only
that MicroK8s was the stated starting point; nothing in this assessment argues
against k3s, and the Canonical comparison table should not be read as if it did.

**Minikube still has one good use**: reproducing a cluster on a laptop for chart
development, alongside `kind`, which `REQ-NFR-064` already names for CI. That is
a development tool role, and it is the role its documentation claims.

## C.3 What a MicroK8s bring-up for this stack needs

Not a plan, a checklist of what the chart would have to assume:

- addons: `dns`, `hostpath-storage` (with ③'s caveats understood), `ingress`
  *or* `metallb`, `metrics-server` (HPA needs it), `cert-manager`, `rbac`,
  `observability` if Prometheus is wanted
- a namespace labelled for the **`restricted`** PodSecurity profile in `enforce`
  mode (`REQ-SEC-087`)
- `hostUsers: false` on every pod — **check `uname -r` ≥ 6.3 first**
- default-deny `NetworkPolicy` plus explicit allowances mirroring the six
  segments, including one policy per plugin
- secrets from `ExternalSecret`/`SealedSecret` — never in the chart
  ([`deploy/helm/README.md`](../../deploy/helm/README.md))
- the migration `Job` as a pre-upgrade hook, the only workload mounting the
  migration secret (`REQ-SEC-101`)
- a measured memory budget before anything else (**R22**)

---

# Part D — Recommendation

Ranked, and each one is reversible.

1. **Build the Helm chart now.** It is already required (`REQ-NFR-052`,
   `REQ-SEC-087`, stage 2), already specified in
   [06 §6.8](../architecture/06-deployment-view.md), and it is the only way to
   learn what the eight items in A.2 actually cost. Run it on MicroK8s. Port the
   connectivity suite first — not last.
2. **Drop nothing yet.** Make the decision about Podman and Compose *after* the
   chart has run the full smoke suite on MicroK8s, with the memory measured.
   Until then the cost of dropping them is known (21 requirements, 16 ADRs, 50
   files) and the benefit is not.
3. **Decide the four Kubernetes questions explicitly, each with an ADR:**
   rootless under Kubernetes (A.2 ①), per-plugin proxy identity (A.2 ②), storage
   and backup (A.2 ③/⑤), and what `services.yaml` is for once the chart exists
   (A.2 ⑦).
4. **Do not split into microservices.** Revisit per block on its trigger. If one
   fires after Kubernetes is in place, the extraction is cheaper than it is
   today — which is an argument for the order, not for the split.
5. **If Kubernetes does become the only path**, amend `REQ-SEC-083` first, in the
   open, and fix the vocabulary everywhere it appears. A requirement that is
   quietly reinterpreted is worse than one that is deliberately changed — which
   is the same lesson risk **R16** was written for.

---

# Part E — If it is decided anyway: the documentation debt, itemised

So that no one has to rediscover it. Nothing below is optional under the
repository's own rules.

**New ADRs** (superseding, not rewriting): Kubernetes as the only runtime
(superseding [0015](../adr/0015-deployment.md) and [0021](../adr/0021-podman-quadlet.md));
rootless under Kubernetes (amending [0022](../adr/0022-rootless.md)); per-plugin
egress identity on Kubernetes (amending [0027](../adr/0027-egress-enforcement.md) §2
and [0037](../adr/0037-per-plugin-network-segments.md)); storage and backup
without runtime-managed volumes (amending [0045](../adr/0045-wal-archive-volume.md));
the topology's proofs as policy assertions (amending [0042](../adr/0042-edge-is-not-internal.md)
and [0044](../adr/0044-internal-is-not-a-trust-boundary.md)); the fate of the
service matrix.

**Requirements to amend**: `REQ-NFR-027`, `-051`, `-052`, `-054`, `-058`, `-059`,
`-061`, `-062`, `-063`, `-064`, `-065`, `-066`, `-067`, `-068`, `-069`, `-071`,
`-075`; `REQ-SEC-047`, `-077`, `-083`, `-084`, `-086`, `-089`, `-101`, `-102`;
`REQ-PRIV-003`. Each needs its acceptance criterion rewritten, not just its
prose — several name Compose and Quadlet *as the mechanism*.

**Architecture chapters**: 02, 05, **06 (largely rewritten)**, 09, 12, 13, 14
(risk register and the accepted-debt table).

**Elsewhere**: `README.md`, `CLAUDE.md` (rules 4 and 5 name the runtimes),
`CONTRIBUTING.md`, `.github/workflows/{ci,smoke}.yml` and their README,
`.github/ISSUE_TEMPLATE/bug_report.yml`, `.pre-commit-config.yaml`,
`deploy/` in its entirety, `docs/reference/tracked-facts.yaml`, and the
`CHANGELOG` — the way a system is run is user-visible.

---

# Part F — Sources

All fetched 2026-09-15. Project-internal references are linked inline above.

**Kubernetes**
- [Kubernetes releases](https://kubernetes.io/releases/) — 1.37.0 released 2026-08-26; supported minors 1.35–1.37
- [Kubernetes v1.36: User Namespaces are finally GA](https://kubernetes.io/blog/2026/04/23/kubernetes-v1-36-userns-ga/)
- [User namespaces (concept docs)](https://kubernetes.io/docs/concepts/workloads/pods/user-namespaces/) — containerd ≥ 2.0, CRI-O ≥ 1.25, runc ≥ 1.2 / crun ≥ 1.9, Linux 6.3+
- [Running node components as a non-root user](https://kubernetes.io/docs/tasks/administer-cluster/kubelet-in-userns/) — `KubeletInUserNamespace`, beta since v1.37
- [Ingress NGINX retirement](https://www.kubernetes.io/blog/2025/11/11/ingress-nginx-retirement/) and the [Steering/Security statement](https://www.kubernetes.io/blog/2026/01/29/ingress-nginx-statement/) — maintenance ends March 2026
- [NPEP-133: FQDN selector for egress traffic](https://network-policy-api.sigs.k8s.io/npeps/npep-133-fqdn-egress-selector/) — FQDN egress is still a proposal, not core API

**MicroK8s** (Canonical — vendor source, treated as such)
- [Release notes](https://canonical.com/microk8s/docs/release-notes) — 1.36 newest; 1.35 replaced NGINX ingress with Traefik
- [High availability](https://canonical.com/microk8s/docs/high-availability) — dqlite, HA from three nodes
- [hostpath-storage addon](https://canonical.com/microk8s/docs/addon-hostpath-storage) — node-bound, RWO, can exceed its claim
- [Addons](https://canonical.com/microk8s/docs/addons) — hostpath-storage "not suitable for a production environment or clusters"
- [Ingress addon](https://canonical.com/microk8s/docs/addon-ingress) — Traefik since 1.35, Gateway API
- [Configuring services](https://canonical.com/microk8s/docs/configuring-services) — `snap.microk8s.daemon-*` under systemd, configured with `sudo`
- [MicroK8s vs k3s vs Minikube](https://canonical.com/microk8s/compare) — vendor comparison; its Minikube multi-node claim is contradicted by Minikube's own docs

**Minikube**
- [Documentation home](https://minikube.sigs.k8s.io/docs/) — "helping application developers and new Kubernetes users"
- [Multi-node tutorial](https://minikube.sigs.k8s.io/docs/tutorials/multi_node/) — since 1.10.1; default host-path provisioner does not support multi-node

**Operators and charts**
- [CloudNativePG releases](https://cloudnative-pg.io/releases/) — 1.30 deploys PostgreSQL 18.4 by default
- [RabbitMQ Kubernetes operators](https://www.rabbitmq.com/kubernetes/operator/operator-overview) — maintained by the RabbitMQ team
- [OpenSearch Kubernetes Operator](https://github.com/opensearch-project/opensearch-k8s-operator) and the [3.0 alpha announcement](https://forum.opensearch.org/t/opensearch-kubernetes-operator-3-0-alpha-now-available/27771)
- [Valkey Helm: the new way to deploy Valkey on Kubernetes](https://valkey.io/blog/valkey-helm-chart/) — project-maintained chart, January 2026
- [Bitnami catalogue change, effective 2025-08-28](https://github.com/bitnami/charts/issues/35164) — public catalogue moved to `bitnamilegacy`, Docker Hub charts no longer updated

**Build and test**
- [Testcontainers: container runtime requirements](https://java.testcontainers.org/supported_docker_environment/) — a Docker-API-compatible runtime is required
- [Kaniko archived](https://github.com/kubernetes-sigs/kernel-module-management/issues/1244) — read-only since June 2025; BuildKit, Buildah and Jib are the live alternatives

---

*Written 2026-09-15. If a fact here is older than the code, the code is right and
this document is wrong — correct it in the same session, say so, and date it.*
