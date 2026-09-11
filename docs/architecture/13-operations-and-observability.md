# 13 — Operations and Observability

## 13.1 The principle

The system is operated part-time by one person. One hard rule follows:

> **Every alert must lead to an action.** What is merely "interesting" belongs on
> a dashboard, not in a notification. A system that wakes you at night for no
> reason gets muted — and then it is missing when it counts.

There are therefore exactly **two** urgencies: *act now* and *look at it next
time*.

## 13.2 Logging

| Aspect | Decision |
|---|---|
| Format | JSON lines, one event per line, to `stdout` |
| Collection | Podman/Quadlet: **journald** (`journalctl --user -u homeinv-api`) · Docker: `json-file` with rotation · Kubernetes: container logs. The operations documentation gives the commands for each way of running it. |
| Mandatory fields | `timestamp`, `level`, `logger`, `message`, `traceId`, `spanId`, `tenantId`, `actorId`, `requestId` |
| Correlation | `traceId` appears in every error response — a user report carrying it leads straight to the operation |
| Levels | `ERROR` only when someone must act · `WARN` for degraded operation · `INFO` for state changes · `DEBUG` off, switchable per logger at runtime |
| Personal data | No passwords, tokens, keys or `sensitive` values. A test with known test values verifies this. IP addresses removed after 7 days. |
| Tenant separation | `tenantId` on every line, so that analysis can be tenant-scoped |
| Retention | 30 days on the application side, rotation through the container runtime |

## 13.3 Metrics

Exposed as a Prometheus endpoint at `/actuator/prometheus` on a **separate
management port (8090) that is not published to the host and is bound to the
`internal` segment only** — `management.server.port` **and
`management.server.address`** in Spring Boot,
`{ container: 8090, host: null, bindTo: internal }` in
[`deploy/services.yaml`](../../deploy/services.yaml). Prometheus scrapes it from
inside `internal`; the health endpoints of [13.5](#135-health-endpoints) live on
the same port, which is how the operator view (`REQ-NFR-072`) reads them.

> **The binding is the half that was missing, and it is the load-bearing half.**
> `host: null` makes the port unreachable from the host and from the internet. It
> does **not** make it unreachable from a container on the same segment — and
> `api` and `worker` sat on the shared `plugins` segment, so any plugin could read
> the per-tenant counts and byte volumes listed below without holding a single
> capability. An unpublished port is hidden from the host, not from a neighbour.
> Since [ADR-0037](../adr/0037-per-plugin-network-segments.md) each plugin has its
> own segment and the management listener binds to `internal`, where no plugin is.
> `REQ-SEC-099` states it and the connectivity test checks it.

Since no monitoring stack exists, an optional `compose.monitoring.yaml` with
Prometheus, Grafana and ready-made dashboards ships with the product.

| Group | Metrics |
|---|---|
| **HTTP** | Requests per second, duration (p50/p95/p99) by route and status, error rate |
| **Domain** | Items per tenant, locations, media and bytes per tenant, scans per hour, print jobs by state |
| **Database** | Connection pool utilisation, query duration, slow queries, lock wait time, table and index size, bloat, **WAL archive fill level and archiving failures** ([ADR-0045](../adr/0045-wal-archive-volume.md)) |
| **Outbox** | Unpublished entries, age of the oldest, relay throughput — **the most important metric in the system**, because a backlog here lets every derived store go stale |
| **RabbitMQ** | Queue length, consumer lag, dead-letter count, retries |
| **Search** | Index lag, query duration, fallback rate (share of searches served from PostgreSQL), document count |
| **Sync** | Active devices, push size, conflicts per day, share of expired cursors (full seedings), `change_log` size |
| **Plugins** | Calls, duration, error rate and circuit state **per plugin** |
| **Egress** | Permitted and refused connections per plugin and target host — the source for the per-resolver disclosure `REQ-ENR-009` requires, which is evidence rather than a restatement of the manifest |
| **Media** | Upload duration, derivative backlog, `BlobStore` errors, storage used |
| **Security** | Failed logins, triggered rate limits, `403`/`404` on foreign objects, access to unknown codes, second-factor events |
| **JVM** | Memory, collector pauses, threads, virtual threads, class loading |

## 13.4 Distributed tracing

OpenTelemetry (Java agent, automatic). A trace spans: HTTP ingress → use case →
database → outbox → RabbitMQ → worker → plugin call. Exactly the chains you
cannot reconstruct without tracing.

| Decision | |
|---|---|
| Sampling | 100 % for errors and slow requests, 5 % otherwise |
| Enrichment | `tenantId`, `actorId`, plugin ID as attributes — never domain data |
| Target | Configurable over OTLP; inactive when unconfigured |

## 13.5 Health endpoints

| Endpoint | Meaning | Behaviour |
|---|---|---|
| `/livez` | The process is alive | Checks **nothing** external — otherwise a database outage starts a restart loop |
| `/readyz` | Ready for traffic | Database reachable, migration complete, Modulith verified |
| `/health` | Detail for the operator | `UP`/`DEGRADED`/`DOWN` per dependency, visible to administrators only |

## 13.6 Service level objectives and degradation levels

| Objective | Value |
|---|---|
| Availability | 99 % per month (~7 h of outage tolerated) — realistic for self-operation |
| Reading a detail view | p95 < 150 ms |
| Search | p95 < 500 ms |
| Writing an item | p95 < 300 ms |
| Index lag | p95 < 2 s |
| Image derivatives after upload | p95 < 30 s |
| Recovery point / time | RPO ≤ 15 min, RTO ≤ 2 h |

### What happens on which failure

| Failed | Effect | Still possible |
|---|---|---|
| **OpenSearch** | Search falls back to PostgreSQL, facets restricted, the response carries `meta.degraded: true` with `degradedReason: "search-fallback"` ([ADR-0039](../adr/0039-degraded-response-signalling.md)) | everything else |
| **RabbitMQ** | The outbox backs up, derived data goes stale | **All** reads and writes — the user notices nothing beyond delayed thumbnails |
| **Valkey** | Sessions invalid (re-login) · rate limiting falls back to per-instance counting, so with *n* instances the effective limit is *n* times the configured one · **in-flight OIDC logins fail** (state and PKCE verifier live here, [ADR-0029](../adr/0029-session-cookie-and-oidc-state.md)) | everything else — **idempotency is unaffected**, because those records live in PostgreSQL, not here ([ADR-0009](../adr/0009-messaging-and-events.md)) |
| **`plugin-blobstore-*` / Nextcloud** | Uploads are queued, already loaded images come from the cache | everything else |
| **`plugin-smtp`** | No invitations, no password-reset mail, no security notifications. The administration UI states it rather than failing silently. A `minimal` installation runs without this plugin by design ([ADR-0028](../adr/0028-plugin-runtime-stage-1.md)) | everything else |
| **`egress-proxy`** | **Every** outbound call of every plugin stops at once — storage, mail, enrichment, webhooks, push — **and the ClamAV signature update** ([ADR-0036](../adr/0036-scanner-egress.md)), which is why the proxy now runs in `minimal` too. Reads and writes are unaffected; uploads queue as above, and keep being scanned against the signatures already loaded. A prolonged outage therefore shows up as the 48-hour signature-age warning before it shows up anywhere else. It is a deliberate chokepoint ([ADR-0027](../adr/0027-egress-enforcement.md)): one visible failure beats per-plugin enforcement that cannot be verified | everything that does not leave the deployment |
| **ClamAV** | **Uploads are rejected** (`503`), already accepted blobs stay `PENDING_SCAN` and unretrievable; a background run catches the scan up. The only degradation level that blocks a user function entirely — deliberately, see [ADR-0024](../adr/0024-malware-scan.md) | everything except uploads |
| **One plugin** | Only the function depending on it, visibly marked | everything else |
| **PostgreSQL** | **Total outage.** The only component without a fallback — and the reason its backup is the only one that really counts | nothing |

## 13.7 Alerts

**Act now:**

| Alert | Threshold |
|---|---|
| PostgreSQL unreachable | 1 min |
| `/readyz` not succeeding | 3 min |
| Outbox backlog | > 1 000 entries **or** the oldest > 15 min |
| 5xx error rate | > 2 % over 5 min |
| Disk space | < 15 % free |
| Backup failed, or the restore verification failed | immediately |
| **WAL archiving failing**, or the archive volume above 80 % | 15 min — when `archive_command` cannot write, PostgreSQL retains WAL in `pgdata` and eventually refuses to write at all. A full archive volume takes down the one component with no fallback |
| Signs of a tenant breach (the isolation check in operation) | immediately |
| Conspicuous failed logins | > 100/min overall or > 20 per account |
| ClamAV unreachable (uploads blocked) | 5 min |
| Malware found in an upload | immediately |
| `egress-proxy` unreachable (every plugin's outbound calls stop) | 5 min |
| A plugin attempts a host **not** in its manifest | immediately — it is either a misconfiguration or a plugin doing something it did not declare, and both are worth knowing at once |

**Look at it next time:**

ClamAV signatures older than 48 h · a non-empty `PENDING_SCAN` queue · index lag
persistently > 10 s · a plugin circuit open for more than 1 h · a non-empty
dead-letter queue · conflicts > 20/day · unusual `change_log` growth · a tenant
quota above 90 % · a deprecated endpoint still in use · a dependency with a new
vulnerability.

## 13.8 Recurring tasks

| Task | Cadence | Purpose |
|---|---|---|
| Backup | daily | see [06 §6.13](06-deployment-view.md) |
| **Restore verification** | weekly | Restore the backup into a throwaway container, check consistency, publish the result as a metric |
| Empty the trash | daily | Finally remove deleted records after the retention period; the tombstone stays |
| Prune `change_log` **partitions** | daily | Detach a monthly partition once **every** tenant's retention has elapsed for its whole range. This is bulk reclamation at the instance maximum — it is **not** what enforces a tenant's retention period, and the two runs used to read as though it were ([ADR-0046](../adr/0046-truncatable-audit-chain.md)) |
| Prune the **WAL archive** | daily | Remove segments older than the newest verified base backup, and publish the volume's fill level as a metric ([ADR-0045](../adr/0045-wal-archive-volume.md)) |
| Audit partitions | monthly | Create and archive |
| Orphaned blobs | weekly | Check reference counts, remove unreferenced ones — **with a grace period**, never immediately |
| Expired tokens and invitations | hourly | |
| Evaluate reminders | hourly | Warranty, maintenance, returns, minimum stock |
| Carry quota usage forward | hourly | |
| Reconcile `item_attr_index` | nightly | Sample against `attributes`, deviations as a metric |
| Reconcile location paths | nightly | `path` against `parent_id` |
| Check search index lag | continuous | |
| Plugin health | per minute | |
| Deregister dormant devices | daily | |
| Update ClamAV signatures (`freshclam`) | daily | A scanner with stale signatures gives false confidence. The fetch goes through the `egress-proxy`, which runs in every profile for this one reason and carries the mirror as a fixed allowlist entry ([ADR-0036](../adr/0036-scanner-egress.md)); a failed fetch appears in the proxy's access log attributed to `clamav`, so the 48-hour alert has a cause and not only a symptom |
| Catch-up scan for `PENDING_SCAN` blobs | hourly | Release or discard uploads accepted during a scanner outage |
| Enforce per-tenant retention periods | daily | `change_log`, audit, trash, conflict archive — within the fixed bounds, by **row deletion inside live partitions** under `homeinv_housekeeping`. For the audit log it also writes an `audit.chain_truncation` row and marks the affected anchors `pruned`, so a recorded truncation stays distinguishable from an unexplained gap ([ADR-0046](../adr/0046-truncatable-audit-chain.md)) |
| Dependency check | daily | |

Every run is **repeatable and cancellable**, logs start, end and scope, and runs
under its own service account with its own permissions.

## 13.9 Operating with multiple tenants

| Task | Tooling |
|---|---|
| Create, suspend, resume, delete tenants | Administration UI |
| Set and monitor quotas | Per tenant; instance-wide defaults |
| Tenant-scoped reporting | Occupancy, activity, cost (storage), error rate |
| Export a single tenant | Complete, including media, without touching the others |
| Restore a single tenant | From the full backup into a throwaway instance, then a targeted import — **the path is documented and rehearsed**, because a selective restore from a full dump is otherwise improvisation under time pressure |
| Impersonation ("view as user") | For the instance operator only, with the second factor again, **always** in the audit log, visible to the affected user, time-limited |

## 13.10 Runbooks

For each of these situations there is a written, rehearsed runbook:

1. The database is unreachable
2. Disk space is full (including immediate measures without data loss)
3. The outbox is backing up
4. The search index is inconsistent → rebuild without an outage through an alias switch
5. Recovery after data loss (full and single tenant) — dump **plus** WAL replay to a
   point in time, which is what the RPO of 15 minutes actually means
   ([ADR-0045](../adr/0045-wal-archive-volume.md))
6. Suspicion that an account is compromised
7. A plugin is behaving suspiciously → disable and preserve evidence
8. An upgrade fails → roll back to the previous version
9. A plugin's certificate has expired
10. Nextcloud is unreachable
11. A tenant is exhausting resources
12. Key rotation for field encryption
13. Containers do not start after a VM reboot (missing `linger`)
14. Resource limits are not taking effect (missing cgroup v2 delegation)
15. A volume is not writable after a restore (UID mapping)
16. WAL archiving has stopped and the archive volume is filling
17. A tenant's audit chain reports *truncated* — reading `audit.chain_truncation` to tell
    a recorded retention run from an unexplained gap
    ([ADR-0046](../adr/0046-truncatable-audit-chain.md))

## 13.11 What the operator must be able to see

An operator UI shows, without detouring through log files:

- System state per dependency with its degradation level
- Outbox and queue backlog
- The plugin list with state, error rate and granted capabilities per tenant
- The tenant list with occupancy and quota usage
- The last backup and the last **successful restore verification**
- Open conflicts across all tenants
- Security events of the last 24 h
- **The way it is being run and the runtime version** (Podman/Docker/Kubernetes)
  and the evidence that it is running rootless
- Active versions: application, API, plugin contract, database schema
