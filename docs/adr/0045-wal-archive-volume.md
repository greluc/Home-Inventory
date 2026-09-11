# ADR-0045 — The WAL archive is a volume of its own, or the recovery point objective is fiction

**Status:** Accepted · **Date:** 2026-09-11
**Amends:** [06 §6.13](../architecture/06-deployment-view.md),
[13 §13.8](../architecture/13-operations-and-observability.md),
[`deploy/services.yaml`](../../deploy/services.yaml), `REQ-NFR-014`

## Context

[06 §6.13](../architecture/06-deployment-view.md) describes the PostgreSQL backup as
*"`pg_dump --format=custom` **inside the container** … plus WAL archiving"*, at a cadence
of *"daily full, WAL continuous"*, and states a recovery point objective of
**RPO ≤ 15 min (WAL)**. `REQ-NFR-014` makes that a priority-**M** requirement.

Nothing anywhere provides it. [`deploy/services.yaml`](../../deploy/services.yaml)
declared six volumes — `pgdata`, `osdata`, `mqdata`, `kvdata`, `cvddata`, `blobdata` —
and none for WAL. No `archive_command`, no `archive_mode`, no target path, no retention
run for the archive, no row in the backup table, nothing in the restore runbook.

The gap is not a detail of wording, because this design makes it hard to fix later by
accident:

- the container filesystem is **read-only** (`REQ-SEC-085`), so PostgreSQL cannot
  archive anywhere it is not given;
- **bind mounts into host directories are forbidden** ([ADR-0022](0022-rootless.md)),
  because the files would belong to the `subuid` range;
- backups go **through the runtime** and never by copying out of the storage directory
  (`REQ-NFR-066`), so the archive has to be a runtime-managed volume to be restorable at
  all with correct ownership.

Without the archive the real recovery point is the last daily dump. That is up to
**24 hours**, on a system whose own asset table calls the inventory *"not recoverable"*
([12 §12.1](../architecture/12-security.md)) and whose fifth quality goal is data
integrity.

## Options

| Option | RPO | Cost |
|---|---|---|
| **A second runtime-managed volume `pgwal`, archived to by `archive_command`** | ≤ 15 min, as stated | One volume, one backup row, one pruning run, one line in the restore runbook |
| Drop the claim: daily dump only, `REQ-NFR-014` relaxed to 24 h | 24 h | Nothing to build — and a day of inventory work lost in the case the backup exists for |
| pgBackRest or WAL-G as its own service | ≤ 15 min, plus incremental backups and a retention engine | Another service, another image to track, another ~100 MB in a budget sized to 8 GB, and a second backup mechanism beside the dump |
| Stream to a remote target | ≤ 15 min, off-host | An outbound connection from a **core** container, which [ADR-0026](0026-core-outbound-via-plugins.md) forbids outright. It would have to become a plugin, and a plugin holding the database's WAL is a worse idea than the problem |

## Decision

**A second volume, `pgwal`, mounted at `/var/lib/homeinv/wal-archive`, with PostgreSQL
archiving into it.**

| Setting | Value |
|---|---|
| `wal_level` | `replica` |
| `archive_mode` | `on` |
| `archive_command` | `test ! -f <archive>/%f && cp %p <archive>/%f` |
| `archive_timeout` | `900` — 15 minutes, so an **idle** instance still closes a segment inside the objective rather than holding the last one open indefinitely |
| Backup | `pgwal` is exported through the runtime with the daily dump and restored with it |
| Pruning | Segments older than the newest verified base backup are removed by the daily housekeeping run; the archive's size is a metric, because an archive that stops being pruned fills the volume and stops PostgreSQL |
| Verification | The weekly automated restore (`REQ-NFR-015`) replays the archive to a point in time, not merely the dump. A restore that only proves the dump proves nothing about the objective |

## Rationale

The volume is the smallest thing that makes an existing requirement true, and every
alternative either abandons the objective or adds a service.

`archive_timeout` deserves its own line because it is the half people leave out. Without
it a quiet instance can sit on an unfilled 16 MB segment for hours, and the measured RPO
is then "however long since the last write burst" rather than 15 minutes. With it the
objective holds on an idle system too, at the cost of some mostly-empty segments — which
is the right trade for a household inventory, where quiet periods are the normal state.

Pruning is named here rather than left to the operator because the failure mode is
specific and bad: when `archive_command` cannot write, PostgreSQL retains WAL in `pgdata`
and eventually refuses to write at all. A full archive volume takes the database down.

## Consequences

- **A seventh volume**, and a seventh row in the backup table of
  [06 §6.13](../architecture/06-deployment-view.md).
- **Two new housekeeping entries** in [13 §13.8](../architecture/13-operations-and-observability.md):
  prune the WAL archive daily, and publish its size as a metric.
- **A new alert**: WAL archiving failing, or the archive volume above 80 % — both lead to
  the database stopping, which is the one component with no fallback
  ([13 §13.6](../architecture/13-operations-and-observability.md)).
- **The restore runbook changes**: recovery is now dump **plus** archive replay, and the
  rehearsal verifies a point-in-time recovery. The runbook said "restore the dump", which
  would have met the RTO and missed the RPO.
- **`REQ-NFR-014` becomes testable** rather than aspirational, and gains an acceptance
  criterion that names the replay.
- **No budget change.** A volume is not a service.
