# 03 — Security and Privacy

Security is quality goal **Q1** and takes precedence in every conflict of goals.
Basis: [12 Security](../architecture/12-security.md).

Unless noted otherwise, the requirements in this chapter are **priority M** —
they are not negotiable, and no stage counts as finished while those that apply
to it are open.

---

## SEC-A — Tenant isolation

| ID | Requirement | Stage | Acceptance |
|---|---|---|---|
| REQ-SEC-001 | Every domain table carries `tenant_id` and has RLS with `ENABLE` **and** `FORCE`. | 1 | An automated check; a new table without RLS fails the build |
| REQ-SEC-002 | The application role has **no** `BYPASSRLS`, **no** table ownership and **no** DDL rights. | 1 | A privilege check at startup, aborting on deviation |
| REQ-SEC-003 | Migrations run under a separate role that is active only at startup. | 1 | Separate credentials, documented |
| REQ-SEC-004 | The tenant context is set per transaction through `SET LOCAL` from the authenticated context — **never** from a request parameter. | 1 | Code review and a test |
| REQ-SEC-005 | A missing tenant context yields **zero rows**, not foreign data. | 1 | A test without a set context |
| REQ-SEC-006 | No connection returns to the pool with the tenant context still set. | 1 | A test across the pool |
| REQ-SEC-007 | An automated **isolation proof** checks every table with two tenants and a wrongly set context. | 1 | Part of every CI run |
| REQ-SEC-008 | Cross-tenant administration goes through explicit, logged `SECURITY DEFINER` functions with their own permission check. | 1 | The list of such functions is documented |
| REQ-SEC-009 | The search index also filters mandatorily on `tenantId`; hits are always re-loaded from PostgreSQL. | 1 | A test with a tampered index entry |

## SEC-B — Authentication

| ID | Requirement | Stage | Acceptance |
|---|---|---|---|
| REQ-SEC-010 | Passwords are hashed with **Argon2id** (≥ 19 MiB, t=2, p=1; raisable by configuration). | 0 | Parameters verified; upgraded on login |
| REQ-SEC-011 | Minimum length 12, no forced complexity, no forced rotation, checked against known breached passwords. | 1 | A known breached password is rejected |
| REQ-SEC-012 | Rate limiting on login attempts **per account and per IP**, with increasing delay. | 0 | A load test |
| REQ-SEC-013 | Comparison runs in constant time; for unknown accounts a dummy hash is computed. | 0 | Timing measurement shows no exploitable difference |
| REQ-SEC-014 | Second factor through TOTP and WebAuthn/passkeys; recovery codes single-use and stored hashed. | 1 | Both verified |
| REQ-SEC-015 | The second factor is mandatory for `OWNER` and `ADMIN`. | 1 | Assigning the role without it is rejected |
| REQ-SEC-016 | Access tokens ≤ 10 min; refresh tokens rotating; detected reuse terminates all sessions. | 1 | A reuse-detection test |
| REQ-SEC-017 | Web sessions through a `__Host-` cookie with `Secure`, `HttpOnly`, `SameSite=Strict`, plus a CSRF token for state-changing calls. | 0 | Cookie attributes verified |
| REQ-SEC-018 | Password reset: a single-use token, valid 30 min, terminates all sessions, notifies the old address. | 1 | The flow verified |
| REQ-SEC-019 | Apps authenticate through OAuth 2.1 (code + PKCE) in the system browser; **no password in the app**. | 3 | The flow verified |
| REQ-SEC-020 | No automatic linking of an OIDC account to an existing one by e-mail address alone. | 3 | Linking requires re-authentication |
| REQ-SEC-021 | Critical operations require re-confirmation of the second factor. | 1 | The list of operations documented and tested |

## SEC-C — Authorization

| ID | Requirement | Stage | Acceptance |
|---|---|---|---|
| REQ-SEC-022 | Authorization happens **exclusively in the application layer**; access adapters decide nothing. | 0 | ArchUnit forbids database and repository access in the access blocks |
| REQ-SEC-023 | The default is **deny**. An endpoint without `@RequiresPermission` and without an explicit `@PublicEndpoint` marker fails the build. | 0 | A CI rule |
| REQ-SEC-024 | The check runs against the **loaded** object, never against the ID alone. | 0 | Code review and a test per endpoint |
| REQ-SEC-025 | Foreign or invisible objects return `404`, not `403`. | 0 | The responses are indistinguishable |
| REQ-SEC-026 | Every endpoint has negative tests for "no permission → 403" and "foreign tenant → 404". | 1 | A coverage check in CI |
| REQ-SEC-027 | `sensitive` fields are **removed** per role, not masked — identically in REST and GraphQL. | 1 | A shared test for both surfaces |
| REQ-SEC-028 | Nobody can grant permissions they do not hold themselves. | 1 | A test |

## SEC-D — Input and output

| ID | Requirement | Stage | Acceptance |
|---|---|---|---|
| REQ-SEC-029 | Input is validated against JSON Schema and Bean Validation; **unknown fields lead to rejection**. | 0 | A test with an extra field |
| REQ-SEC-030 | No binding of request data onto entities; a dedicated input type per use case. | 0 | An ArchUnit rule |
| REQ-SEC-031 | Parameterised SQL only; dynamic SQL only through a builder with an allowlist for field and sort names. | 0 | ArchUnit forbids string concatenation in SQL |
| REQ-SEC-032 | User input is canonicalised (Unicode NFC), control characters removed, lengths bounded. | 0 | A test with special characters |
| REQ-SEC-033 | `dangerouslySetInnerHTML` is forbidden; user-generated Markdown is sanitised server-side, HTML removed. | 0 | A lint rule and a test |
| REQ-SEC-034 | URLs from user input: `http`/`https` only, no internal address ranges, never fetched server-side, output with `rel="noopener noreferrer"`. | 1 | A test with SSRF patterns |
| REQ-SEC-035 | Regular expressions from field definitions are checked for catastrophic backtracking and executed with a time limit. | 1 | A test with a known ReDoS pattern |
| REQ-SEC-036 | The expression language in label templates is not Turing-complete and accesses only an allowlist of fields. | 2 | A test with injection patterns |

## SEC-E — Uploads

| ID | Requirement | Stage | Acceptance |
|---|---|---|---|
| REQ-SEC-037 | A size limit is enforced **before** reading (default 25 MB). | 0 | A test with an oversized file |
| REQ-SEC-038 | Type detection through magic bytes; the extension and reported MIME type are discarded. | 0 | A test with a renamed file |
| REQ-SEC-039 | An allowlist of permitted formats; **SVG is rejected**. | 0 | A test |
| REQ-SEC-040 | A pixel limit before decoding (default 100 MP) against decompression bombs. | 0 | A test with an image bomb |
| REQ-SEC-041 | Every image is **re-encoded**; embedded payloads are destroyed in the process. | 0 | A test with a polyglot file |
| REQ-SEC-042 | EXIF including GPS is stripped; retention only on an explicit setting. | 0 | A test with a reference image |
| REQ-SEC-043 | Media is served from a **dedicated hostname**, with `Content-Disposition: attachment`, `nosniff` and a CSP sandbox, without cookies. | 1 | Headers verified |
| REQ-SEC-044 | Access only through signed, short-lived URLs (≤ 15 min), bound to the user and the media ID. | 1 | A test with an expired and with a foreign signature |
| REQ-SEC-045 | PDFs are never displayed inline. | 1 | Verified |

## SEC-F — Secrets and encryption

| ID | Requirement | Stage | Acceptance |
|---|---|---|---|
| REQ-SEC-046 | `sensitive` fields are encrypted with AES-256-GCM, a data key per tenant, a master key from the environment. | 1 | A database dump contains no plaintext values |
| REQ-SEC-047 | The encryption uses **additional authenticated data** composed of `tenantId`, `entityId` and `fieldKey`. | 1 | A relocated ciphertext cannot be decrypted |
| REQ-SEC-048 | Tokens are stored in the database only as a hash. | 1 | Verified |
| REQ-SEC-049 | Key rotation is possible without touching the data; the master key supports two active versions. | 1 | The rotation verified |
| REQ-SEC-050 | Logs contain no passwords, tokens, keys or `sensitive` values. | 0 | A test with known test values |
| REQ-SEC-051 | `gitleaks` runs in CI and as a pre-commit hook. | 0 | A finding fails the build |
| REQ-SEC-052 | Nextcloud access uses an app password with access to exactly one folder. | 1 | Documented and verified |

## SEC-G — Plugins

| ID | Requirement | Stage | Acceptance |
|---|---|---|---|
| REQ-SEC-053 | Third-party plugins run **out-of-process** only, in their own container with their own service account. | 3 | Network rules verified |
| REQ-SEC-054 | Plugin containers reach neither PostgreSQL nor OpenSearch, RabbitMQ or Valkey. | 3 | A network test |
| REQ-SEC-055 | Outbound connections only to the hosts named in the manifest, enforced in the network. | 3 | A test with a divergent target |
| REQ-SEC-056 | Connections use mTLS with a pinned certificate fingerprint. | 3 | A test with a foreign certificate |
| REQ-SEC-057 | Every `CoreApi` call re-checks the capability, the tenant scope and activation — no trust from the connection alone. | 3 | A test after revocation |
| REQ-SEC-058 | In-process plugins are off by default, permitted only when signed, and not installable by tenant administrators. | 3 | A test |
| REQ-SEC-059 | The administration UI permanently shows when in-process code is running, with publisher and fingerprint. | 3 | Visual check |

## SEC-H — Transport, headers, rate limiting

| ID | Requirement | Stage | Acceptance |
|---|---|---|---|
| REQ-SEC-060 | CSP without `unsafe-inline` and without `unsafe-eval`, with nonces and `require-trusted-types-for 'script'`. | 0 | A CI test against the expected policy |
| REQ-SEC-061 | HSTS, `nosniff`, `Referrer-Policy`, `Permissions-Policy`, COOP, CORP and COEP are set. | 0 | A header check in CI |
| REQ-SEC-062 | CORS restricted to the configured frontend origin; never `*`, never reflecting the request. | 0 | A test |
| REQ-SEC-063 | `X-Forwarded-*` is honoured only from a configured address list. | 0 | A test with a forged header |
| REQ-SEC-064 | Rate limiting per user, tenant and IP; login and code resolution with their own, stricter limits. | 1 | The response is `429` with `Retry-After` and `RateLimit-*` |
| REQ-SEC-065 | Payload and time limits: JSON ≤ 1 MB, bulk operations ≤ 500 entries, a request timeout of 30 s. | 0 | A test |
| REQ-SEC-066 | GraphQL: depth ≤ 10, cost analysis before execution, persisted queries in production, introspection disabled. | 1 | A test with an expensive query |
| REQ-SEC-067 | Error messages contain no internals (paths, SQL, stack traces) and do not reveal the existence of foreign resources. | 0 | Verified across all error paths |

## SEC-I — Traceability

| ID | Requirement | Stage | Acceptance |
|---|---|---|---|
| REQ-SEC-068 | Every mutating action creates an audit entry with actor, tenant, action, resource, diff, IP, client and correlation ID. | 1 | A coverage check |
| REQ-SEC-069 | The audit log is **append-only**; the application role has only `INSERT` and `SELECT`. | 1 | A privilege check |
| REQ-SEC-070 | Every entry carries the hash of its predecessor; tampering is detectable. | 1 | A verification run over the chain |
| REQ-SEC-071 | The log answers: "what did this account do in this period?" | 1 | The query exists and is verified |
| REQ-SEC-072 | Impersonation ("view as user") is available only to the instance operator, with the second factor again, time-limited, in the audit log and visible to the affected user. | 1 | The flow verified |
| REQ-SEC-073 | Plugin writes appear with the plugin as the actor. | 3 | Distinguishable |

## SEC-J — Supply chain and verification

| ID | Requirement | Stage | Acceptance |
|---|---|---|---|
| REQ-SEC-074 | Dependencies are pinned (version catalog, lock files, `npm ci`). | 0 | A reproducible build |
| REQ-SEC-075 | Vulnerability scanning on every push and daily; "high" and "critical" fail the build. | 0 | CI |
| REQ-SEC-076 | SAST (CodeQL, SpotBugs with `find-sec-bugs`, ESLint security) on every push. | 0 | CI |
| REQ-SEC-077 | Container images: a distroless base, referenced by digest, `trivy`-scanned, signed with `cosign`. | 1 | The signature is verifiable |
| REQ-SEC-078 | CI permissions minimal; `pull_request_target` forbidden; no secrets for foreign pull requests. | 0 | A workflow review |
| REQ-SEC-079 | Licence checking of dependencies for AGPL compatibility; permissive licences only in the plugin API module. | 1 | CI |
| REQ-SEC-080 | A nightly DAST baseline run against the test environment. | 2 | The report exists |
| REQ-SEC-081 | `SECURITY.md` with a contact, a GPG key and coordinated disclosure (90 days). | 1 | Present |
| REQ-SEC-082 | Immediate measures exist as administrative functions: terminate all sessions, revoke tokens, disable a plugin, suspend a tenant. | 1 | Each verified |

## SEC-K — Runtime isolation and rootless

| ID | Requirement | Stage | Acceptance |
|---|---|---|---|
| REQ-SEC-083 | **No container and no container daemon runs as `root` on the host.** Rootful Docker and rootful Podman are not supported ways to run this. | 0 | [ADR-0022](../adr/0022-rootless.md); a CI check |
| REQ-SEC-084 | No deployment description contains `privileged`, `cap_add`, `pid: host`, `network_mode: host`, `user: root`, `SecurityLabelDisable=true`, a mounted container socket, or a port below 1024. | 0 | A CI check across Compose, Quadlet and Helm; a finding fails the build |
| REQ-SEC-085 | Every service sets `read_only`, `cap_drop: ALL`, `no-new-privileges`, a non-root user and resource limits. | 0 | A CI check |
| REQ-SEC-086 | The smoke suite runs **rootless** under both container runtimes — a test passed only rootful does not count. | 1 | The CI matrix |
| REQ-SEC-087 | Kubernetes manifests set `runAsNonRoot`, `runAsUser`, `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem`, `capabilities.drop: [ALL]` and `seccompProfile: RuntimeDefault`; the namespace runs the `restricted` profile in `enforce` mode. User namespaces (`hostUsers: false`) are set wherever the cluster version supports them. | 2 | A chart test against `kind` with PodSecurity enabled |
| REQ-SEC-088 | Plugin containers get their **own UID range** where feasible; where that is not viable, a separate service user per plugin. | 3 | Two plugins cannot see each other at host level |
| REQ-SEC-089 | The container runtime's socket is mounted into **no** container. | 0 | A CI check |
| REQ-SEC-090 | A plugin demanding elevated privileges, host networking or host device access is **not** supported. | 3 | Part of the contract test suite in the plugin SDK |
| REQ-SEC-091 | **A malware scan is mandatory** (ClamAV through the `VirusScanner` port). "No scanner" is not a supported configuration. | 1 | Startup without a reachable scanner is reported; uploads stay blocked |
| REQ-SEC-092 | The scan is **fail-closed**: a finding → the blob is discarded, `422`, an audit entry, a notification to the tenant administrator. Scanner unreachable or a timeout (60 s) → `503`, the blob stays `PENDING_SCAN` and unretrievable. | 1 | Both cases verified with an EICAR test file and with the scanner switched off |
| REQ-SEC-093 | Signatures are updated daily; a signature age above 48 hours raises a warning. | 1 | The metric exists, the threshold takes effect |

---

## PRIV — Privacy

| ID | Requirement | Prio | Stage | Acceptance |
|---|---|---|---|---|
| REQ-PRIV-001 | The only mandatory data per user is e-mail, display name and language. | M | 0 | Registration asks for nothing more |
| REQ-PRIV-002 | There is **no telemetry** and no analytics service, neither built in nor optional. | M | 0 | No outbound connection except through enabled plugins |
| REQ-PRIV-003 | The core has **no outbound route to the internet**; external calls go through plugins only. | M | 3 | The network configuration verified |
| REQ-PRIV-004 | Access (Art. 15) and portability (Art. 20) are producible mechanically. | M | 1 | The archive is complete |
| REQ-PRIV-005 | Account and tenant deletion with a 30-day grace period, revocation, and a completion report per building block. | M | 1 | An erasure certificate is produced |
| REQ-PRIV-006 | IP addresses in application logs are removed after 7 days; in the audit log only for security-relevant events and truncated there. | M | 1 | The cleanup run verified |
| REQ-PRIV-007 | GPS data in photos is stripped by default. | M | 0 | See REQ-SEC-042 |
| REQ-PRIV-008 | Per resolver it is inspectable and switchable which data is transmitted to which host. | M | 3 | The target list in the UI |
| REQ-PRIV-009 | Templates for a processing register, a description of technical and organisational measures, and a data processing agreement ship with the documentation. | S | 1 | Present |
| REQ-PRIV-010 | Retention periods are **configurable per tenant** within fixed bounds: `change_log` 30–365 d (default 90) · audit 180 d – 5 y (default 1 y; security-relevant entries never below 180 d) · trash 7–90 d (default 30) · conflict archive 30–365 d (default 90). Application logs (30 d) and IP addresses (7 d) are instance-wide and not tenant-adjustable. | M | 1 | Values outside the bounds are rejected; for `change_log` the UI states explicitly how long a device may stay offline |
| REQ-PRIV-011 | On suspicion of personal data exposure, text templates for notification under Art. 33/34 are available. | S | 1 | Present |
| REQ-PRIV-012 | A user can view their own data without having to ask the operator for help. | M | 1 | Self-service in the UI |
| REQ-PRIV-013 | Where push through Firebase or APNs is used, the scope of the transmission (device token, timestamp, category) is disclosed in the UI; Google resp. Apple must be recorded as processors in the processing register. A template ships with the documentation. | M | 3 | The text exists and is shown before activation |
| REQ-PRIV-014 | Device tokens are stored encrypted, deleted when a device is deregistered, and discarded after 180 days without contact. | M | 3 | A database dump contains no plaintext tokens; the expiry verified |
