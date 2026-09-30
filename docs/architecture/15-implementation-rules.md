<!--
SPDX-FileCopyrightText: Lucas Greuloch
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# 15 — Implementation Rules

The rules the code follows that no single class states. Each one was learned
from a failure or forced by a tool, and each is invisible at the place where it
is applied: a line that wraps an `Instant` before binding it looks like noise
unless the reader knows the driver cannot type one.

The code carries no explanatory comments (see [CLAUDE.md](../../CLAUDE.md),
*Code comments*). Its reasoning lives here, in the ADRs and in the requirement
rows. A rule below that stops being true is removed in the same change that
makes it untrue.

---

## 15.1 Tenant context and row-level security

| Rule | Why |
|---|---|
| The tenant context is established **before** a transaction begins. | `TenantAwareTransactionManager` issues `SET LOCAL app.tenant_id` in `doBegin`, from the context current at that moment. A context set inside a running transaction is never seen. |
| Every transaction writes the setting, with or without a tenant. | A pooled connection would otherwise carry the previous caller's tenant into the next statement. |
| A read that has to see tenant rows runs inside a transaction. | Outside one the setting is empty and `FORCE` row-level security answers with nothing, which reads like a missing row. Spring Data `@Query` methods are not transactional by default and carry `@Transactional(readOnly = true)`. |
| A statement under an established context carries **no** tenant predicate of its own. | The policy scopes it; a second predicate is a second place the tenant is decided, and the second one is the one that goes wrong. |
| Work with no tenant context — the hourly anchor, the expired-upload sweep, the login's tenant lookup — goes through a documented `SECURITY DEFINER` function. | `SECURITY DEFINER` alone does not bypass `FORCE`; the function is owned by `homeinv_bootstrap`, a `NOLOGIN` role with a policy of its own on the one table it reads. `MigrationRulesTest` refuses an undocumented definer. |
| Nobody may `CREATE` in the `public` schema; `USAGE` stays. | A table created there by accident would carry no policy; `ltree` lives there and every role needs its type. |
| The migrator holds `CREATE` on the database. | Flyway creates the `flyway` schema before the first migration. |

## 15.2 Transactions and the persistence context

| Rule | Why |
|---|---|
| A transactional method is never reached through `this`, and a `final` class is never annotated. | A self-invocation bypasses the Spring proxy and a `final` class cannot be proxied: the annotation then does nothing while the tests, which call through the bean, still pass. A boundary needed inside a bean lives in a bean of its own. |
| Native SQL that depends on a row written through JPA runs after a flush (`saveAndFlush`, `flush`). | Hibernate does not see a native statement and may hold the write until commit, after the statement that needed it. |
| A bulk operation runs each entry in its own transaction. | One entry's exception would otherwise mark the shared transaction rollback-only and lose the others at commit. |
| A quota is claimed by incrementing first and checking second. | Check-then-write lets two requests both find room; the rollback of the failed one returns its claim. |
| A row that is issued and never edited (a data key, an idempotency record) is replaced by delete and insert. | The application role holds no `UPDATE` on those tables, deliberately. |
| An audit timestamp is truncated to microseconds before it is hashed, and rendered as ISO-8601 in UTC. | `timestamptz` keeps microseconds; hashing nanoseconds would make every verification report tampering. |

## 15.3 SQL and JDBC

| Rule | Why |
|---|---|
| A `java.time.Instant` is never bound directly; it is wrapped in a `Timestamp` or an `OffsetDateTime`. | The PostgreSQL driver cannot infer its SQL type and answers "bad SQL grammar" — usually only on the second page, where the cursor binds one. |
| An `ltree` value is bound with `setObject(…, Types.OTHER)`. | `setString` sends a `varchar`, which has no implicit cast to `ltree`. |
| A set of values of varying size is bound as `= any(?)` with an array. | A generated `IN` list is a new prepared statement per size and the one place a value would shape the SQL. |
| Each attribute filter is its own `exists` subquery. | A join per filter multiplies the rows when two filters match one item, and the `distinct` that repairs it discards the sort. |
| Sorting on an optional attribute uses `LEFT JOIN` and `NULLS LAST` in both directions. | An item without the field stays in the list, at the end, whichever way the column is sorted. |
| Dynamic SQL is assembled from fixed fragments. | The only variable part is a sort expression resolved from the tenant's allowlist ([ADR-0004](../adr/0004-attribute-storage-model.md), CLAUDE.md). |
| A referenced row is checked explicitly before a write. | A missing or foreign reference is then a 404, not a foreign-key violation surfacing as a 500. |
| Decimal amounts are compared with `compareTo`, never `equals`. | `BigDecimal.equals` is scale-sensitive: `1` and `1.0` would differ and turn a retry into a conflict. |
| `jsonb` dirty checking is textual equality. | Hibernate decides from it whether to write; a false "unchanged" would lose a write. |
| A query that expects one row where several may exist uses `LIMIT 1`. | `JdbcClient.optional()` throws on more than one row. |

## 15.4 Paging and cursors

| Rule | Why |
|---|---|
| A page is read as `limit + 1` rows. | The extra row answers "is there another page" without a second count that could disagree. |
| A cursor is issued only when the page was full. | A short page is the last one, and a cursor for it costs a client a request to learn that. |
| The last row's sort key travels in a per-call holder (`LastRow`), never in a field. | Adapters are singletons; a field would be shared by every request in flight. |
| A cursor is bound to its query: filters, sort and location are hashed, with separators, into the signed payload. | A cursor from another query would resume a sequence that never existed; it is refused as a malformed request. The sort value inside is base64, so a value containing the separator cannot split the payload (`CursorCodecTest`, `ItemFilteringIT`, `ItemSortingIT`). |
| A page size above the maximum is refused, not clamped; the service clamps as a second line. | A client asking for 5 000 and silently getting 200 pages wrongly for ever (`InputHardeningIT`, `MediaListingIT`). |

## 15.5 Answers a client can rely on

| Rule | Why |
|---|---|
| An operation whose outcome already holds succeeds: deleting what is gone, returning what is returned, assigning what is assigned, restoring what is live. | A client retrying a request whose answer it never saw must not be told it failed. A log line is written only when something changed. |
| Unknown, used, withdrawn and expired tokens are one answer — invitations, resets, revocations, second-factor codes, service-account tokens. | Telling them apart would reveal that something was issued. |
| A resource of another tenant is a 404, never a 403. | A denial confirms that it exists. |
| An anonymous request to any path is a 401 before routing. | A stranger cannot map the surface by watching which paths answer 404 (`ErrorContractIT`). |
| An error never echoes input. | A parser's message can quote the payload; a status description written by our own service may be logged, a blob address may not. |
| A body with two representations of success (`200`/`201`) declares both explicitly. | springdoc derives one response from the return type. |

## 15.6 Security in code

| Rule | Why |
|---|---|
| Known and unknown accounts cost the same work: the password is verified in every branch, a reset token is minted before the lookup, a passkey failure is one message whatever caused it. | A measurable difference answers "does this address have an account here". |
| The reason for a refused sign-in is logged and never returned. | An operator needs "wrong password" and "locked" apart; the caller must not have them. |
| A signature is checked before an expiry. | Checking the expiry first answers faster for an expired link than for a forged one. |
| No locale-sensitive case folding on a security path. | Upload sniffing compares bytes, booleans use `parseBoolean`, closed sets use a `switch`, hostnames are `IDN.toASCII` first and lower-cased second. A fold can map one character onto another. |
| Text somebody else chose reaches a log only through `LogSafe`. | A newline in it would forge a record ([12 §12.12](12-security.md)). |
| A condition an attacker can trigger repeatedly is logged once, on its transition. | Rate-limit delays, open circuits and missing grants must not let a failing caller fill the log. |
| A JVM-mandatory algorithm that is missing (`HmacSHA1`, `HmacSHA256`, `SHA-256`) is a startup failure. | Without it nothing can be authenticated or named, and continuing would hand out values that mean nothing. |
| A key file is read as raw bytes or as one base64 line. | A secret manager hands keys over as text; hashing the literal base64 characters would work and be wrong. |
| A value from a nullable getter is read once into a local. | Two calls are two values to an analyser, and the second is the one dereferenced. |
| An e-mail address is stored with a lower-cased local part. | Every provider treats it case-insensitively; storing it as typed allows a second account for the same person. |

## 15.7 Background work and messaging

| Rule | Why |
|---|---|
| A scheduled task catches everything it can throw. | A task that throws stops being scheduled, and a sweep that silently stopped leaves every later request unhonoured. |
| One tenant's failure does not stop a run, and the message recorded for it carries no stack trace. | The message is shown to whoever asked. |
| Every consumer is idempotent; an idempotency key is per message, per channel and per person. | Delivery is at-least-once; a shared key would make the second of two genuine messages look like a repeat. |
| A reminder is recorded first and sent only by the call that recorded it. | The other order sends and then fails to record, which repeats. |
| A derivative that cannot be produced is logged and swallowed. | Rethrowing would redeliver the same failing file for ever; the `full` variant is unaffected (`DerivativeGenerationIT`). |
| Queues are durable and named; a media worker takes one message at a time. | An anonymous queue loses what arrived during a deployment; prefetching would park work on a busy worker. |
| An order that matters — erasure, export, import — is sorted explicitly. | An order that came from a classpath scan changes under a rebuild. |
| A notification's channel and the plugin that holds the instance grant must agree. | Otherwise a password-reset mail could leave the deployment as a webhook before failing. |

## 15.8 Media and blobs

| Rule | Why |
|---|---|
| A staged upload loses its bytes before its row. | Staged bytes with no row are cleaned by the sweep; a row pointing at bytes that are gone can never be finished (`ResumableUploadIT`). |
| A failed blob delete never rolls back the detach the user asked for. | A blob that outlives its last reference is wasted space, not a correctness problem. |
| A scanner that cannot answer never produces a clean verdict. | `ScannerUnavailableException` keeps the object `PENDING_SCAN`; a known-infected blob is marked and detached even when deleting it fails (`MediaScanIT`). |
| Nothing is decoded before the header check passes, and an oversized upload stops at the limit. | Reading to the end to report the size would accept the upload in order to refuse it. |
| A picture that cannot be read is left out of a document; an archive missing one blob is written with the gap listed. | A report with a visible gap is worth more than no report. |
| A blob store's `NOT_FOUND` surfaces from `open`, not from the first read. | The blocking gRPC stub returns a lazy iterator; a stream that throws on its first byte turns "no such blob" into a truncated file. |
| clamd is spoken to in the NUL-terminated command form, with four-byte length-prefixed chunks and never an empty one. | An empty chunk ends the stream and the file is judged on what arrived. Its signature date is parsed in the root locale, with `ppd`, as UTC, weekday dropped. |

## 15.9 Deployment generation and container runtimes

What [`deploy/generate.py`](../../deploy/generate.py) encodes, so that the Quadlet
and Compose outputs behave the same.

| Rule | Why |
|---|---|
| Operator values reach a Quadlet unit through `EnvironmentFile=`, and a `${VAR:-default}` placeholder is omitted. | systemd does not interpolate `${VAR}` in `Environment=`; it passes the characters through, and a later `Environment=` would override the file. The default belongs to the application. |
| A health check is written as an argument array **without** Compose's `CMD` prefix, and a `scratch` image's check has at least one argument. | Podman re-splits a `CMD`-prefixed array as raw text and hands a single word to `/bin/sh -c`, which a `scratch` image does not have. |
| A unit that waits for health has a start timeout above the start period plus four intervals and a minute. | systemd's default 90 s ends exactly when the check first becomes eligible to pass. |
| Containers carry no `[Install]`; the profile target does. | Enabling every container would start `standard`'s services on a `minimal` host at the next login. |
| A tmpfs mount carries mode `1777`. | Mounted without one it is root-owned `0755`, and the non-root user cannot write; `deploy/smoke/connectivity.sh` fails while any container is not healthy. |
| Volumes are named; nothing is bind-mounted. | Under a rootless user namespace host ownership is not container ownership. |
| A one-shot is `restart: "no"` and its dependants wait on `service_completed_successfully`. | Restarting a deterministic failure never reaches completion. |
| `container_name` equals the Quadlet `ContainerName=`. | Every operator command and every smoke check names one container on both runtimes. |
| Secret files are `0444` inside a `0700` directory. | Rootless Docker maps the host user to uid 0 while services run as 10001; the directory is the protection. `podman secret create` refuses an empty file, so an empty external secret is reported and skipped. |
| mTLS keys are P-256; the JWT signing key stays Ed25519. | grpc-java's key manager reads RSA, DSA and EC only. |
| PostgreSQL 18's volume is mounted at `/var/lib/postgresql`. | The image places the cluster in a version subdirectory and refuses a volume at the old path. |
| RabbitMQ users come from a configuration file. | RabbitMQ 4 refuses to start on `RABBITMQ_DEFAULT_*` variables. |
| ClamAV gets a writable `/var/lock`. | Its lock file is linked there and the root filesystem is read-only. |
| The JVM starts in exec form. | As PID 1 it receives `SIGTERM`; under a shell it waits for the kill timeout. |
| A worker binds its management listener to loopback. | The management server is a child of the web context; switching the main server off takes `/livez` and `/readyz` with it. |
| One-shot roles contact nothing but PostgreSQL while starting. | A bean that connects to Valkey or the broker in `migrate` fails the refresh and with it the deployment. `ArchitectureRulesTest` holds this. |
| The CSP header is emitted once per nginx location. | nginx inherits `add_header` only where a level defines none. |
| Base64 values are substituted with `awk`. | `/` and `+` end a `sed` expression. |

The ingress configuration is generated by [`web/scripts/csp.mjs`](../../web/scripts/csp.mjs); the
rules of the `web → api` hop are [06 §6.7](06-deployment-view.md). What the generator adds:

| Rule | Why |
|---|---|
| `/healthz` is answered by nginx itself. | A check that consulted `api` would report `web` unhealthy while `api` is down, and the runtime would restart the one container that works. |
| The upstream is the literal `api:8080`. | A variable would need a `resolver` whose address differs between Podman and Docker. |
| `/graphql` is an exact location, and keeps nginx's default buffering. | A prefix would also proxy `/graphqlfoo`; a GraphQL response is one document, unlike the SSE stream under `/api/`. |
| The read timeout also exceeds the SSE heartbeat (`homeinv.events.heartbeat-seconds`). | A quiet tenant is the normal case, and a shorter timeout closes every live stream on it. |
| `/media/` adds no headers of its own. | The application sets the disposition, sandbox and resource policy for that path. |
| Hashed assets are cached for a year; the shell is never cached. | A release invalidates assets by name, and a stale shell points at assets that no longer exist. |
| The policy starts from `default-src 'none'`, and the build fails without the inline theme bootstrap. | A forgotten directive fails closed; without the bootstrap a light-preferring user sees a dark flash (ADR-0033). |

## 15.10 Testing

| Rule | Why |
|---|---|
| Integration tests connect as the application role. | Running as the owner would make every isolation test pass for the wrong reason. |
| A test that reads tenant rows opens the tenant context **and** a transaction. | 15.1: without both the answer is empty and looks like a defect. |
| Shared test configuration is imported on the base class. | A nested `@TestConfiguration` is discovered on the test class, not on its superclass. |
| A GraphQL request in MockMvc is dispatched asynchronously before it is asserted on. | The first `perform` returns an empty 200 when async starts; refusals before execution never start async, so both shapes are handled. |
| Timing assertions against Valkey read `PTTL`. | `TTL` rounds to the nearest second. |
| Keys, certificates and passwords are generated in the test. | A private key in a fixture is a private key in a public repository. The one shared test passphrase is allowlisted in `.gitleaks.toml`; a second one is derived from it, never invented. |
| A check whose discovery could come back empty asserts that it found something. | An empty list makes every assertion over it pass. |
| Durations are measured with `nanoTime`. | A wall clock can step backwards. |

## 15.11 Continuous integration

| Rule | Why |
|---|---|
| Rootless Docker on a hosted runner is installed with `FORCE_ROOTLESS_INSTALL`, `slirp4netns` and `fuse-overlayfs`, after an AppArmor profile for `rootlesskit`, and the job asserts the daemon reports `rootless`. | The installer refuses while the rootful daemon runs, Ubuntu refuses unprivileged user namespaces without a profile, and a silent fallback to the rootful socket would prove nothing. |
| `buf breaking` checks out two commits. | It compares against the previous one. |
| A one-shot's output is read from its own container log after `--force-recreate`. | An attached stream races the start; a reused container accumulates earlier runs. |
| A timed-out Quadlet unit is diagnosed from the journal. | systemd removes the container with the unit. |
| gitleaks runs a version that reads top-level `[[allowlists]]`. | Older versions ignore the array and report the allowlisted literals. |
| A version Spring Boot manages is overridden through the property name its BOM declares, read from that BOM's `pom`. | An unknown property name is ignored without a warning; `jackson2.version` moved nothing while Boot 4 reads `jackson-2-bom.version`. `./gradlew :app:dependencies` shows the version that resolved, and the vulnerability scan fails on the one that did not move. |
| A dependency job uses a warm Gradle cache. | An uncached job asks Maven Central for everything on every run and meets its rate limits. |
