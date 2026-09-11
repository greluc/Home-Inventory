# 14 — Quality Scenarios, Test Strategy, Risks, Glossary

## 14.1 Quality scenarios

Every scenario is phrased so that it is **either met or not met**. Vague goals
like "nicely maintainable" deliberately do not appear.

### Q1 Security

| # | Trigger | Expected reaction | Measure |
|---|---|---|---|
| S1 | A developer writes a query without a tenant filter | The query returns zero rows, not foreign data | The automated isolation proof across **every** table |
| S2 | A plugin without `core:item:write` attempts a write | Rejection, log entry, the plugin stays active | A test in the testkit |
| S3 | User A requests the ID of an item of tenant B | `404`, indistinguishable from "does not exist" | A test per endpoint |
| S4 | A photo with GPS data is uploaded | The stored file contains no GPS data | An automated test with a reference image |
| S5 | A new endpoint is added without a permission check | **The build fails** | A CI rule |
| S6 | 10 000 login attempts from one network | Blocked after the threshold, an alert, no resource exhaustion | A load test |
| S7 | A dependency receives a critical vulnerability | Reported within 24 h, the build blocked until it is fixed | CI, daily |
| S8 | An attacker escapes from a plugin container | They are an unprivileged user without `sudo` on the host, with no access to other services | Rootless on both runtimes, CI check per REQ-SEC-083 ff. |
| S9 | Someone adds a service with `privileged` or port 80 | **The build fails** | A CI check across Compose, Quadlet and Helm |

### Q2 Modularity · Q3 Extensibility · Q4 Maintainability

| # | Trigger | Expected reaction | Measure |
|---|---|---|---|
| M1 | A block reaches into another's internal classes | The build fails, naming the violation | Spring Modulith + ArchUnit |
| M2 | A cycle between blocks appears | The build fails | The Modulith check |
| M3 | A new barcode plugin is attached | Runs **without a single line of core change** | The reference plugin in CI, the sequence from [09 §9.10](09-extensibility-and-plugins.md) |
| M4 | A tenant administrator needs a new field | Without a release, while running, ≤ 2 min | An acceptance test |
| M5 | `media` is to run as its own service | Possible without changing any other block | A design review per release |
| M6 | A new contributor starts the system locally | ≤ 10 min, one command | An onboarding test |
| M7 | A bug is reported | The affected block is unambiguously derivable from the report | `traceId` and `logger` |

### Q5 Data integrity · Q6 Usability · Q7 Scalability

| # | Trigger | Expected reaction | Measure |
|---|---|---|---|
| D1 | Two devices edit the same item offline | A conflict record with both versions; nothing is lost | The property-based tests from [11 §11.9](11-offline-synchronization.md) |
| D2 | A database outage mid-write | No half state; event and data are either both present or both absent | A fault injection test |
| D3 | A user deletes 500 items by accident | Fully recoverable within the trash retention period | An acceptance test |
| D4 | Restoring from backup | Complete and consistent, RTO ≤ 2 h | Weekly, automated |
| U1 | Capture an item using a pre-printed label | ≤ 3 interactions until the record is saved | An acceptance test against the clock |
| U2 | Operating without a network in the cellar | Full functionality except enrichment and cross-tenant search | An acceptance test in flight mode |
| U3 | Scanning a label from 50 cm with a phone camera | Recognised in < 1 s | A device test with printed samples |
| P1 | 1 M items, full-text search | p95 < 500 ms | A load test with a generated data set |
| P2 | 1 M items, detail view | p95 < 150 ms | A load test |
| P3 | Rendering a label sheet with 500 codes | < 20 s, the core stays responsive | A load test |
| P4 | Seeding a device with 100 000 items | < 5 min over Wi-Fi, < 300 MB locally | A device test |

## 14.2 Test strategy

| Level | Scope | Tools | Goal |
|---|---|---|---|
| **Domain logic** | Aggregates, invariants, resolution rules — **without** Spring, without a database | JUnit 5, AssertJ | Fast and numerous; ≥ 90 % in `domain` |
| **Building block** | One block with its neighbours stubbed | `@ApplicationModuleTest` | The boundaries hold |
| **Integration** | A real database, a real broker, a real index | Testcontainers with **the same digests** as production | No illusion from stubs |
| **Contract** | OpenAPI, GraphQL, protobuf, event schemas | `oasdiff`, `buf breaking`, schema comparison | No unannounced break |
| **Architecture** | Module boundaries, layering, permission coverage, RLS on every table | ArchUnit, Modulith, project rules | Decay becomes visible immediately |
| **Security** | Isolation, authorization, uploads, headers | Project test suites, ZAP | [12 §12.12](12-security.md) |
| **Sync** | Property-based, n devices, random sequences | jqwik, a shared suite for web and KMP | No silent data loss |
| **End to end** | The ten most important user flows | Playwright | No regression in daily use |
| **Load** | A generated inventory of 1 M items | k6 or Gatling | The Q7 scenarios |
| **Plugin** | The contract test suite | `homeinv-plugin-testkit` | Third-party plugins behave correctly |

**Test data:** a reproducible generator (fixed random seed) produces tenants,
types with realistic fields, location trees, items, media, codes and change
histories — in three sizes (small, medium, large). It is part of the repository,
not a script on somebody's machine.

## 14.3 Risks

| # | Risk | Impact | Likelihood | Countermeasure | Early warning sign |
|---|---|---|---|---|---|
| R1 | **Scope too large for one person.** Full offline sync, three API surfaces, OpenSearch, RabbitMQ, a plugin system and two apps add up to a multi-year project. | The project stalls unfinished | **high** | Four stages, each usable on its own. Stage 0 is deliberately small. After each stage the rest is re-assessed. | Stage 0 takes longer than planned |
| R2 | **Offline sync is underestimated.** The most expensive and most error-prone part. | Silent data loss — the worst failure this system can have | high | Property-based tests from the start; the conflict archive; stage 3, not earlier; falling back to "read cache + outbox" stays possible at any time | Test failures that cannot be reproduced |
| R3 | **Module boundaries decay.** The shared kernel grows, `api` packages get bypassed. | Modularity only on paper | medium | Machine verification from day one; size monitoring of `platform`; the rule "three users, no decision" | `platform` grows between releases |
| R4 | **In-process plugins become the norm.** More convenient, faster — and without a sandbox. | The first security hole in third-party code | medium | Off by default; no tenant path; a permanent notice in the UI; every example out-of-process | A first-party plugin "absolutely has to" be in-process |
| R5 | **The type system becomes a programming language.** Conditional fields, calculations, rules — and suddenly the configuration is Turing-complete. | Not validatable, not securable, not maintainable | medium | An explicitly bounded set of field capabilities; anything beyond is a plugin | A request for "calculated fields with a formula" |
| R6 | **The three API surfaces drift apart.** | A hole in one, closed in the other | medium | Authorization only in the application layer; GraphQL read-only; ArchUnit forbids data access in the access blocks | A check appears in a controller |
| R7 | **OpenSearch and RabbitMQ as operational load.** Two additional services with their own versions and failure modes. | Operational effort above what is available | medium | Ports with a PostgreSQL fallback; the `minimal` profile stays runnable and is tested in CI | Frequent interventions because of these services |
| R8 | **The attribute model is not enough for reporting.** JSONB plus the side table covers filters and sorting; complex aggregates over attributes are more awkward than in a relational model. | Reports become slow or ugly | low | OpenSearch aggregations for reporting; targeted materialisation where needed | Reports taking more than 2 s |
| R9 | **Label geometries are wrong.** Half a millimetre of offset ruins sheets. | Wasted material, loss of confidence | medium | A verification flag per format, a calibration sheet, a to-scale preview | User reports about offset |
| R10 | **External metadata sources disappear** (API change, shutdown, rate limit). | Enrichment stops working | high | Plugins, several sources per scheme, a cache, visible degradation — the core never depends on them | A rising error rate for a resolver |
| R11 | **Media in Nextcloud is slower than a local filesystem.** WebDAV over HTTPS per file. | Noticeable on bulk operations | medium | Cache derivatives locally, upload asynchronously, batch; switching to S3 is a configuration change | Upload duration p95 > 5 s |
| R12 | **The AGPL deters contributors**, or leaves plugin authors uncertain. | A smaller ecosystem | low | Licensing the plugin **API** separately under Apache-2.0 so plugins stay freely licensable; documented clearly | Questions about it in issues |
| R13 | **The two container runtimes drift apart.** Quadlet units and `compose.yaml` describe the same topology; a hand correction in one is forgotten in the other. | One path works, the other breaks at the operator's site — and that only shows up there | **high** | The service matrix as the single source, both artifacts **generated** from it; an identical smoke suite in the CI matrix; a hand-written deviation fails the build | A bug report reproducible under only one runtime |
| R14 | **Rootless setup as a barrier to entry.** `subuid`, `linger` and cgroup delegation are three places where an installation quietly half-works. | Operators give up, or unknowingly run without resource limits | medium | A setup script that **checks the prerequisites and aborts when they are missing** rather than warning; dedicated runbooks for the three typical failure pictures | Questions about containers not starting after a VM reboot |
| R15 | **UID mapping on volumes.** The classic rootless pitfall — usually noticed during a restore, i.e. at the worst possible moment. | The restore fails exactly when it is needed | medium | The mapping mode fixed and documented per service; a "restart against an existing volume" test; backups exclusively through the runtime, never through `cp`; the weekly restore verification surfaces it | Write errors after a runtime update |

## 14.4 Deliberately accepted technical debt

What is built more simply on purpose, together with the trigger that makes it
worth revisiting:

| Simplification | Trigger for building it out |
|---|---|
| No full-text search inside PDF attachments | Users repeatedly search for invoice contents |
| Depreciation only straight-line | A need for further methods arises |
| No multi-currency conversion with historical rates | A tenant records in several currencies |
| No accessibility verification beyond WCAG 2.2 AA | Use with assistive technology |
| Local search only prefix and substring | Users miss typo tolerance offline |
| No high availability | More than one household depends on it operationally |
| No hosted plugin directory with distribution | More than ~10 third-party plugins |
| No USB device access (rootless) — label printers are attached over the network | A specific printer is only reachable over USB; then document the `udev` rule and group membership |

## 14.5 Glossary

| Term | Meaning |
|---|---|
| **Aggregate** | A set of domain objects with a root that enforces the invariants and forms the transaction boundary (`Item`, `Location`) |
| **Base version** | The state a device went offline from — the prerequisite for the three-way compare |
| **BlobStore** | The port for binary storage; adapters for filesystem, S3 and Nextcloud |
| **Building block** | A functionally cut module inside the monolith with its own schema and a published `api` package |
| **Capability** | An individually grantable permission of a plugin; without a grant nothing is possible |
| **Cursor** | An opaque, signed pointer for pagination (API) resp. reconciliation position (sync) |
| **Enrichment** | Supplementing metadata from external sources by code; produces **proposals**, not direct changes |
| **Foreign code** | A code on the object that we did not issue (ISBN, EAN, GTIN) |
| **HLC** | Hybrid logical clock — orders events despite wrong device clocks |
| **Item type** | A type defined at runtime with its own fields; versioned and inheritable |
| **`item_attr_index`** | The derived side table holding the searchable attribute values; no runtime DDL |
| **linger** | The systemd setting (`loginctl enable-linger`) without which user services end at logout and do not start at boot — the most common rootless mistake |
| **Modular monolith** | One deployment, many machine-verified module boundaries |
| **Outbox** | The table events are written to in the same transaction as the data; a relay publishes them afterwards |
| **Port** | A narrow interface behind which the replaceable parts live |
| **Public code** | The printed short code (`7Q2-M4X-9KD`) — not the UUID |
| **Quadlet** | Describes containers as systemd units (`.container`, `.network`, `.volume`); systemd starts and supervises them directly, without a daemon |
| **RLS** | Row-level security in PostgreSQL — the second line of defence, independent of the application |
| **rootless** | Containers run inside a user namespace of an unprivileged host user; container `root` is nobody special on the host |
| **Service matrix** | `deploy/services.yaml` — the one description of the deployment topology from which the Quadlet units and `compose.yaml` are generated and the Helm chart is validated |
| **subuid / subgid** | The range of host UIDs and GIDs a rootless container maps its internal IDs into (`/etc/subuid`) |
| **Tenant** | A bounded data space with its own members, types, roles and quotas |
| **Tombstone** | A marker for a deletion so that devices can follow it during reconciliation |
| **Pre-printed label** | A printed label whose code is not yet assigned |
| **UUIDv7** | A time-ordered UUID; index-friendly and generatable offline |

## 14.6 References

| Topic | Source |
|---|---|
| Structure | [arc42](https://arc42.org) |
| Decision format | Michael Nygard, *Documenting Architecture Decisions* |
| Functional comparison | [Homebox](https://homebox.software) · [InvenTree](https://inventree.org) |
| Error format | RFC 9457 *Problem Details for HTTP APIs* |
| Deprecation | RFC 9745 *Deprecation Header* · RFC 8594 *Sunset Header* |
| Password and session guidance | OWASP ASVS · OWASP Cheat Sheets |
| API design | Google AIP · Zalando RESTful API Guidelines |
| Resumable upload | [tus.io](https://tus.io) |
| Code alphabet | Crockford Base32 |
