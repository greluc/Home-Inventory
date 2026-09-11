# ADR-0041 — Migration is its own one-shot service, on every runtime

**Status:** Accepted · **Date:** 2026-09-11
**Amends:** [05 §5.11](../architecture/05-runtime-view.md),
[06 §6.11](../architecture/06-deployment-view.md),
[06 §6.12](../architecture/06-deployment-view.md),
[07 §7.9](../architecture/07-data-model.md),
[`deploy/services.yaml`](../../deploy/services.yaml)

## Context

Four documents described the same migration and did not agree, and the service
matrix — the one that generates the units — described none of them:

| Says | Where |
|---|---|
| Flyway runs **at application startup**, under the migration role | [05 §5.11](../architecture/05-runtime-view.md) |
| `HOMEINV_DB_MIGRATION_USER`/`_PASSWORD_FILE` are required, *"for Flyway only, active only at startup"* | [06 §6.11](../architecture/06-deployment-view.md) |
| The upgrade order is *"Migration → `worker` → `api` → `web`"* — a step of its own | [06 §6.12](../architecture/06-deployment-view.md) |
| Under Kubernetes it is a **pre-upgrade `Job`** | [06 §6.8](../architecture/06-deployment-view.md) |
| — nothing. **No core service held `db-migration-password`**, and no migration service existed | `deploy/services.yaml` |

So the generated Quadlet units and `compose.yaml` would have described a stack
that cannot migrate, while Kubernetes did it a third way. Whatever is chosen, the
matrix has to say it, because the matrix is what runs.

The choice matters beyond tidiness. `homeinv_migrator` **owns the tables and
holds DDL rights** ([07 §7.5](../architecture/07-data-model.md)) — it is the one
role in the system for which row-level security is not the safety net, because
an owner with `FORCE RLS` disabled on its own schema changes is precisely what
`homeinv_app` is kept away from. Where that credential lives decides what a
compromise of a long-running, internet-facing process is worth.

## Options

| Option | For | Against |
|---|---|---|
| `api` migrates at startup, holding the credential | Follows [05 §5.11](../architecture/05-runtime-view.md) literally · no new service · one fewer ordering constraint | A permanently running process, reachable from the reverse proxy, holds DDL credentials for its whole life. A compromise of `api` reaches `homeinv_migrator`, and with it the ability to `ALTER` or drop a policy — the second line of defence that [ADR-0003](0003-multi-tenancy.md) exists to provide |
| **A one-shot migration service on every runtime** | No long-running container ever holds the credential · all three paths behave identically · [06 §6.12](../architecture/06-deployment-view.md)'s stated order becomes literally true · Kubernetes stops being the odd one out | A fourth service in the matrix and one more start-order dependency · [05 §5.11](../architecture/05-runtime-view.md) has to be rewritten, because migration stops being part of startup |
| A pre-start unit that mounts the credential only for the migration | Keeps the credential's lifetime short without a new service | Secret lifetime inside a running container is not something a container runtime enforces; the file is gone but the process that read it is not. It buys the appearance of the property, not the property |

## Decision

**A one-shot `migrate` service, present and equal on all three runtimes.**

| Property | Value |
|---|---|
| Image | **the same image as `api` and `worker`**, with `SPRING_PROFILES_ACTIVE=migrate`. The version matrix stays at one, exactly as for the api/worker split ([ADR-0015](0015-deployment.md)) |
| Networks | `internal` only. It talks to PostgreSQL and to nothing else |
| Secrets | **`db-migration-password` — the only service that *uses* it.** Not `data-key`, not `jwt-key`: Flyway performs DDL and reads no tenant data. `postgres` also mounts it, to **create** the role in its first-initialisation script; the image knows only `POSTGRES_PASSWORD_FILE` for the superuser. Creating a role with a password and connecting with it are different things, and the property that matters is that no long-running **application** service does either |
| Lifetime | Runs, migrates, exits. Success is exit 0 |
| Quadlet | `[Service] Type=oneshot`, `RemainAfterExit=yes`; `api` and `worker` carry `Requires=`/`After=homeinv-migrate.service` |
| Compose | `restart: "no"`, and `api`/`worker` use `depends_on: { migrate: { condition: service_completed_successfully } }` |
| Kubernetes | The pre-upgrade `Job` that already existed ([06 §6.8](../architecture/06-deployment-view.md)) — now the same thing as the other two rather than a special case |

### The application still *checks* the schema, it just no longer *changes* it

`api` and `worker` run with `HOMEINV_DB_MIGRATE_ON_START=false` and connect only
as `homeinv_app`. At startup they **validate** the schema version and **refuse to
start** if it is not the one this build expects.

That check is the part that makes the split safe rather than merely tidier.
Without it, a forgotten migration step is discovered as a column that is not
there, at request time, in production. With it, `/readyz` never turns true and
the rollout stops — which is exactly what
[05 §5.11](../architecture/05-runtime-view.md) already promised (*"`/readyz`
turns true only after … completed migration"*), now delivered by verification
instead of by performing the migration itself.

### What does not change

- Migrations stay backward compatible across three releases — expand, switch,
  clean up ([06 §6.12](../architecture/06-deployment-view.md), `REQ-NFR-056`).
  Splitting the step out does not license a breaking migration; the N−1 rule is
  what makes running the migration *before* the new code safe.
- The CI checks in [07 §7.9](../architecture/07-data-model.md) are unchanged.
- `homeinv_app` still has no DDL rights and no `BYPASSRLS` (`REQ-SEC-002`).

## Rationale

The deciding argument is not the schedule but the credential. Everything else in
this design works to keep the blast radius of a compromised `api` small — RLS as
an independent second line ([ADR-0003](0003-multi-tenancy.md)), no outbound route
([ADR-0026](0026-core-outbound-via-plugins.md)), plugin credentials held by
plugins ([ADR-0027](0027-egress-enforcement.md)), rootless containment
([ADR-0022](0022-rootless.md)). Leaving the one role that can rewrite the RLS
policies mounted inside that same internet-facing process for its whole lifetime
undoes a meaningful part of that, for the sake of not adding a service.

The second argument is the one risk R13 names: three descriptions of one
topology drift. Kubernetes already did this correctly with a `Job` while the
other two paths were assumed to do it at startup and in fact did it nowhere.
Making all three the same shape is the same move the service matrix exists to
make.

## Consequences

- **A fourth core service**, ~0 MB at rest (it exits). It appears in the sizing
  table with a note rather than a reservation.
- **`db-migration-password` is removed from `api`.** It was added there earlier
  the same day, when the gap in the matrix was first closed the other way; this
  supersedes that.
- **[05 §5.11](../architecture/05-runtime-view.md) is rewritten**: the startup
  sequence validates the schema instead of migrating it, and a separate row
  describes the migration service.
- **A new requirement, `REQ-SEC-101`**: no long-running service holds the
  migration credential, and the application refuses to start against a schema it
  does not expect. Both halves are testable — the first by reading the generated
  artifacts, the second by starting `api` against a deliberately old schema.
- **One more start-order dependency**, which is the kind Quadlet handles well
  (`Requires=`/`After=`) and Compose handles adequately
  (`service_completed_successfully`). `REQ-NFR-067`'s ten cold starts now cover
  it.
- **A failed migration blocks the whole stack**, by design: `api` and `worker`
  do not start, `/readyz` never turns true, and the reverse proxy keeps the old
  instances in rotation. That is the desired failure, and runbook 8 (*"an upgrade
  fails → roll back"*) already covers it.
