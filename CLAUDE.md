# CLAUDE.md

Working notes for Claude Code (and human contributors) in this repo. Keep this file
current when the architecture, the build, or the rules below change.

## What this is

**Home Inventory** — a self-hosted inventory system for **physical and digital** items:
type-dependent metadata configured at runtime, arbitrarily nested storage locations,
photos, QR/barcode identification, label printing, offline-capable clients, and a
versioned, extensible API.

Technically: Java 25 + Spring Boot 4 + Spring Modulith, PostgreSQL 18, OpenSearch,
RabbitMQ, Valkey, React PWA, Kotlin Multiplatform apps. Multi-tenant, reachable from the
internet, runs **rootless** under Podman/Quadlet or Docker/Compose. AGPL-3.0-or-later
(plugin API: Apache-2.0).

Repository: <https://github.com/greluc/Home-Inventory>.

Display name is *Home Inventory*; **every technical identifier uses the short form**:
image `home-inv`, package root `de.greluc.homeinv`, protobuf `home_inv.plugin.v1`,
env prefix `HOMEINV_`, containers `homeinv-*`, plugin IDs
`de.greluc.homeinv.plugin.*`. The **repository name is the single long-form
exception** — do not "correct" the short identifiers to match it, and name the
container image explicitly in CI, because derived from the repository it would
read `home-inventory`.

> **Status: design phase. There is no code yet.** The deliverable in this repo today is
> the architecture and the requirements catalogue. Do not invent build commands, file
> paths, or class names that do not exist — if something is not in `docs/`, it has not
> been decided.

## The documentation is the project (HARD RULE — read before every task)

`docs/` is the single source of truth for this system. It is not a description of the
code; **right now it is the entire product**, and once code exists the two move together
in the same unit of work.

- **Read before you work, not after.** Enter through [`docs/README.md`](docs/README.md) —
  it maps every document and lists every decision with its ADR. Then read the chapters
  your task touches. Do not re-derive from first principles what is already written down.
- **When code and documentation disagree, the code is right** — and the document is then
  wrong and gets fixed in the same session, saying so and dated. Never leave a known
  contradiction standing.
- **Every change updates the documentation in the same unit of work.** A feature, an
  endpoint, an env var, a permission, a metric, a migration, a decision — each moves with
  its notes. Documentation is never "caught up later".
- **If you notice a gap or a stale statement — fix it immediately**, even when it lies
  outside the task you were given. A drifted document is worse than none, because it still
  reads as authoritative.
- **No secrets and no personal data in `docs/`.** This is a public AGPL repository: no
  passwords, tokens, keys, `.env` contents, no names, e-mail addresses or real host names
  beyond the documented examples. If a fact cannot be written without a credential, write
  the *shape* of it and point at where the value lives.

Reading order for a first session: [`docs/README.md`](docs/README.md) →
[`01 Introduction and Goals`](docs/architecture/01-introduction-and-goals.md) →
[`03 Solution Strategy`](docs/architecture/03-solution-strategy.md) →
[`04 Building Blocks`](docs/architecture/04-building-blocks.md) → the chapter your task
touches.

## Requirements, specs and decisions (binding)

Docs-as-code, exactly like the Basetool repos:

- **Requirements** live in [`docs/requirements/`](docs/requirements/README.md) as
  `REQ-<AREA>-NNN` (400 of them). IDs are stable and never reused; a dropped requirement
  is marked `Withdrawn`, not deleted.
- **Decisions** live in [`docs/adr/`](docs/adr/README.md) as numbered ADRs. **Every
  architecturally significant decision gets one, before or with the change that implements
  it.** An ADR is never rewritten when the decision changes — a new one supersedes it, and
  the old one is marked (see ADR-0015 → 0021/0022 for the pattern).
- **Every change updates the requirements in the same PR.** A behaviour change with no
  matching requirement change is incomplete.
- **Requirements must always be honoured.** Code must not silently contradict one. If a
  change *must* violate a requirement, it needs **prior approval by the repository owner
  (@greluc)** AND the requirement must be **amended first**. When in doubt, stop and ask.
- Open decisions are collected in [`ADR-0000`](docs/adr/0000-open-points.md), together
  with decided-but-not-yet-done work. As of 2026-09-11 **no open decision remains** —
  check it before proposing something that looks undecided, because it probably is not.

## Rules that are load-bearing (do not silently soften these)

Each of these is a decision with an ADR behind it. They look like details and are not.

1. **Module boundaries are machine-enforced.** Spring Modulith + ArchUnit. A building
   block reaches another only through its published `api` package — never internal types,
   never a foreign database schema. No cycles. `domain` code has no framework dependency.
   Entities never leave their block; only `*View` types do.
   ([ADR-0002](docs/adr/0002-modular-monolith.md), REQ-NFR-019…024)
2. **Tenant isolation has two independent lines.** Application authorization *and*
   PostgreSQL Row-Level-Security with `FORCE`. The app role has **no** `BYPASSRLS`, **no**
   table ownership, **no** DDL rights. The tenant context comes from the authenticated
   principal via `SET LOCAL app.tenant_id` — **never** from a request parameter. A missing
   context yields zero rows, not foreign data.
   ([ADR-0003](docs/adr/0003-multi-tenancy.md), REQ-SEC-001…009)
3. **Authorization lives only in the application layer.** REST, GraphQL and gRPC are
   adapters that decide nothing. An endpoint without `@RequiresPermission` and without an
   explicit `@PublicEndpoint` marker must fail the build.
   ([ADR-0010](docs/adr/0010-api-surfaces.md), REQ-SEC-022…028)
4. **Everything runs rootless** — not just non-root inside the container, but an
   unprivileged user on the host. No `privileged`, no `cap_add`, no host network, no
   container socket mounted anywhere, no port below 1024.
   ([ADR-0022](docs/adr/0022-rootless.md), REQ-SEC-083…090)
5. **The core has no outbound route to the internet.** Every external call goes through a
   plugin in the `plugins` network segment with an explicit host allowlist — object
   storage, SMTP, OIDC, webhooks and push alike, with no exception
   ([ADR-0026](docs/adr/0026-core-outbound-via-plugins.md)). The allowlist is enforced
   by an egress proxy, because no container runtime can express a hostname rule on its
   own ([ADR-0027](docs/adr/0027-egress-enforcement.md)).
6. **In-process plugins are off by default and are not sandboxable.** Java 25 has no
   `SecurityManager`; a classloader separates namespaces, not privileges. Third-party code
   runs out-of-process over gRPC/mTLS, period.
   ([ADR-0006](docs/adr/0006-plugin-runtime.md))
7. **The type system must not become a programming language.** No formula fields, no
   scripting in label templates, no rules with database or network access. Anything beyond
   declarative field definitions is a plugin.
   ([ADR-0020](docs/adr/0020-configuration-as-data.md), REQ-CORE-031)
8. **Attribute storage is variant D**: JSONB is the source of truth, `item_attr_index` is
   an application-maintained side table. **No DDL at runtime, ever** — the app role does
   not even have the rights for it.
   ([ADR-0004](docs/adr/0004-attribute-storage-model.md))
9. **`HOMEINV_PUBLIC_BASE_URL` is printed onto physical labels.** It is the only
   configuration value that writes itself into the physical world, and printed labels
   cannot be recalled. Never change its handling without reading
   [`10 §10.2.1`](docs/architecture/10-identification-and-labels.md) first.
10. **Derived stores may fail, never lie.** OpenSearch, `item_attr_index`, thumbnails and
    caches are derived and rebuildable; search results are always re-loaded from
    PostgreSQL so a stale index cannot leak across a tenant boundary.
11. **Nothing is lost silently.** Deletion is two-stage, sync conflicts are stored records
    rather than discarded writes, and the audit log is append-only with a hash chain.
12. **A label geometry counts as verified only when measured, not when calculated.** Size
    and count do not determine where the slack sits. See
    [`docs/reference/label-media.yaml`](docs/reference/label-media.yaml).
13. **Money is an in-house value type, and no money library is added.** `BigDecimal` +
    `java.util.Currency` in `platform`. Arithmetic across currencies throws; `double` and
    `float` are forbidden on the money path; every total is per currency. JavaMoney was
    evaluated and rejected — its conversion modules schedule background fetches to ECB and
    IMF endpoints, which collides with rule 5.
    ([ADR-0025](docs/adr/0025-money-representation.md), REQ-NFR-070)

## Language

- **Documentation, code, API, database, protocols, commit messages, PRs, issues and every
  comment: English.** This holds regardless of the language the user speaks to you in.
- **The UI ships German and English**, English as fallback. Every user-visible string comes
  from a resource bundle — no hardcoded text in components, templates or Java.
- Field labels in tenant type definitions are multilingual data, not code.
- The whole corpus is English as of 2026-09-11, file names included. **Do not
  introduce a German-language document** — a CI check enforces it (REQ-CON-012).

## Where things go

```
api/ app/ cla/ deploy/ docs/ plugin-api/ plugin-sdk/ proto/ web/
```

Every directory has a `README.md` stating what belongs there and which stage
fills it — read it before putting a file somewhere. Two boundaries are not
negotiable:

- **`plugin-api/`, `plugin-sdk/` and `proto/` are Apache-2.0** and must never
  depend on a core module. A file that lands there by accident makes the promise
  in ADR-0018 false.
- **`deploy/services.yaml` is the source of truth** for the deployment topology.
  The Quadlet units and `compose.yaml` are generated from it; editing them by
  hand fails the drift check.

## Build, run, test

**Nothing to build yet.** When the implementation starts, this section gets the real
commands. The plan (from [`docs/architecture/`](docs/architecture/)):

- Gradle 9 with Kotlin DSL, always through the wrapper (`./gradlew`), never the IDE test
  runner. Dependency versions live in the **version catalog**
  (`gradle/libs.versions.toml`) — edit that, not `build.gradle.kts`.
- Integration tests run against **the same image digests as production** via Testcontainers.
  H2 or any other substitute database is forbidden: JSONB, `ltree` and RLS behave
  differently, which is exactly where the bugs would be.
- The smoke suite runs against **both** container runtimes (rootless Podman, rootless
  Docker) and against `kind`. A change that works under only one runtime breaks the build.
- Local start must be **one command in under ten minutes**, profile `minimal`.

## Conventions (once code exists)

- **Constructor injection only** (Lombok `@RequiredArgsConstructor`). No field `@Autowired`.
- **Records** for DTOs and immutable config wrappers; **Lombok** used generously to avoid
  boilerplate; `@Slf4j` for logging — never instantiate a logger by hand.
- **Javadoc is mandatory** on every class, interface, enum, record and public/protected
  method, and it must describe *actual* behaviour, parameters, side effects and non-obvious
  invariants. Generic boilerplate ("Gets the value", "Helper method") is forbidden. If you
  cannot write a concrete, code-specific sentence, read the implementation again.
- **Only parameterised SQL.** Dynamic SQL goes through a checked builder whose field and
  sort names come from an allowlist derived from `field_definition` — never from user input.
- Time is stored as `timestamptz` in UTC, displayed in the user's zone. Money is
  `numeric(19,4)` plus an ISO-4217 code, never floating point. Measures are stored in SI
  base units.
- Errors follow RFC 9457 (`application/problem+json`) on every HTTP surface, with a stable
  `type` URI and a `traceId`.
- **Every new feature ships with tests.** No exceptions.
- **Never use real credentials in tests or local stacks.** Anything that enters a worktree,
  a CI log, a container volume or a screenshot must be assumed leaked.

## Documentation duties

- **`CHANGELOG.md`** for every user-visible change. Entries are short and terse — one to
  three sentences on *what* changed and *why it matters to the user*. No design essays, no
  file lists, no pasted commit messages.
- `README.md`, the requirements catalogue and the affected architecture chapter move with
  the change. A change that leaves one of them stale is **incomplete**.
- Diagrams are Mermaid in plain text. No binary diagram files in the repository.

## Git

- **No destructive Git commands without explicit user instruction**: `reset --hard`,
  `clean -fd`, `push --force[-with-lease]`, `rebase` on shared branches, `branch -D`,
  `tag -d`, `stash drop`, or anything that rewrites or discards history. Read-only and
  additive operations are fine when the task needs them.
- **Every commit carries a DCO `Signed-off-by:` trailer — always `git commit -s`.** The
  signing identity is `Lucas Greuloch (greluc) <lucas.greuloch@gmail.com>`, which is also
  the project's single contact address — there is no second one. Never hand-write the
  trailer: `-s` derives it from `git config`, which is already correct, and typing it by
  hand is exactly how a wrong address gets in.
- **Third-party contributions additionally require a signed CLA**
  ([ADR-0018](docs/adr/0018-licensing.md)) — automated in the pull request, recorded durably.
- **Every commit Claude authors includes a `Co-Authored-By:` trailer naming the model**,
  e.g. `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. This is a transparency
  requirement independent of the DCO sign-off. Substitute the actual model of the session.
- Conventional Commits. PRs are assigned (`--assignee greluc`) and labelled from the
  repository's **existing** label set — never invent a label inline.
- **Commit and push only when the user asks.**

## Security (public repository)

Treat every file as world-readable at all times.

- **Nothing secret ever lands in the repo** — no keystores, no `.env*` with real values, no
  tokens in code, tests, fixtures, logs or screenshots.
- A missing secret **aborts startup** with a clear message. There is no generated default
  key, no "insecure but running" mode. Never add one to make local development easier.
- `gitleaks` runs in CI and as a pre-commit hook.
- The full threat model, the STRIDE table and the verification gates are in
  [`12 Security`](docs/architecture/12-security.md); the testable requirements in
  [`requirements/03`](docs/requirements/03-security-and-privacy.md).

## When you change…

- **a database table** → it needs `tenant_id`, RLS with `FORCE`, `version`, the audit
  columns, and a Flyway migration under the owning block's directory. The isolation proof
  test covers every table automatically — a new table without RLS fails the build.
- **an endpoint** → permission annotation, negative tests ("no right → 403", "foreign
  tenant → 404"), OpenAPI entry, and the requirement it implements.
- **an event** → the schema is versioned and checked for breaking changes in CI; every
  consumer is idempotent, because delivery is at-least-once.
- **the plugin contract** → `buf breaking` gates it; a new major version keeps the previous
  one served for at least six months.
- **a deployment detail** → it belongs in `deploy/services.yaml`, from which the Quadlet
  units and `compose.yaml` are generated and the Helm chart is validated. Hand-editing the
  generated artifacts fails the drift check.
- **the label base URL handling** → read [`10 §10.2.1`](docs/architecture/10-identification-and-labels.md).
  Printed labels cannot be recalled.
- **anything at all** → update the requirement, the architecture chapter, and the ADR if a
  decision moved.

## License

**AGPL-3.0-or-later** for the core; **Apache-2.0** for the plugin API, SDKs and protobuf
module, so plugin authors may choose their own licence. The separation is structural: the
plugin API modules have **no** dependency on core modules, and CI enforces that. Details in
[ADR-0018](docs/adr/0018-licensing.md).
