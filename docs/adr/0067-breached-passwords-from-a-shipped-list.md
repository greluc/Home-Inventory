# ADR-0067 — Breached passwords are checked against a shipped list, and a plugin may add to it

**Status:** Accepted · **Date:** 2026-09-16
**Depends on:** [ADR-0026](0026-core-outbound-via-plugins.md)
**Amends:** [ADR-0064](0064-the-ports-a-plugin-implements-are-apache.md) (a fifteenth port),
[ADR-0066](0066-instance-level-capability-grants.md) (a second caller of the instance-level
resolution)

## Context

`REQ-SEC-011` asks for four things of a password: at least twelve characters, no
forced complexity, no forced rotation, and **checked against known breached
passwords**. The first three are rules about a string and were built with the
password reset (`REQ-SEC-018`). The fourth is a lookup, and the usual way to do
it is a k-anonymity range query against Have I Been Pwned.

**The core cannot make that call.** It has no outbound route at all
([ADR-0026](0026-core-outbound-via-plugins.md)) — that is the property the whole
egress design exists to hold, and a password check is not the thing to spend it
on. So the question was never "HIBP or not"; it was where a list comes from when
the process asking may not fetch one.

## Options

| Option | For | Against |
|---|---|---|
| **A list in the image** | Works in every profile, `minimal` included · no network, no plugin, no operator step · the check is *always* on, which is what "checked against" means | Ages from the day it is built · a file of some size in a repository that is otherwise text |
| A plugin port only | Always current, and the operator chooses the service | An installation without the plugin has **no** check, so `REQ-SEC-011` would hold only where somebody opted in — and `minimal`, which has no plugins, is the profile most installations start with |
| **Both: the list always, a plugin in addition** | The floor holds everywhere; an operator who wants a live service can have one | Two paths to test, and a port to maintain |
| Nothing, and amend the requirement | No work | The requirement is right: the passwords that get tried are exactly the ones on such lists |

## Decision

**Both, with the list as the floor.**

1. **The list ships in the image**: SecLists
   `Passwords/Common-Credentials/Pwdb_top-100000.txt`, 100 000 entries, MIT,
   vendored gzipped (432 kB) at `app/src/main/resources/security/`. Its
   provenance and licence are recorded in `REUSE.toml`. It is read once at
   startup, and a missing file **aborts the start**: a policy that silently
   stopped checking would be a control that reports success while doing nothing.
2. **In memory it is 100 000 truncated SHA-256 hashes** in one sorted
   `long[]` — about 800 kB, against the ten megabytes a set of strings would
   take. A 64-bit truncation can collide about once in four billion at this
   size, and a collision refuses one password somebody chose, which is the
   harmless direction.
3. **A `PasswordBreachCheck` plugin port** may be consulted in addition, for an
   operator who wants a live service through the egress proxy. It is an
   addition and never a replacement: the list is checked first and its verdict
   is final.

## What the numbers actually say

Worth stating, because it is surprising and it changes what the list is for:
**about 1 470 of the 100 000 entries are twelve characters or longer.** Every
other entry is rejected by the length rule before the list is ever consulted.

The list is therefore not doing the work one might assume. It catches the long
keyboard walks — `q1w2e3r4t5y6`, `1qaz2wsx3edc`, `qwerty123456` — which are
exactly the long passwords people actually choose, and it stands ready if the
minimum length is ever lowered. The rest of its value is insurance, and it was
shipped whole rather than filtered so that the two rules stay independent: a
change to `MINIMUM_LENGTH` must not silently need a new data file.

## Consequences

- **The list ages.** It is a snapshot of what was known when the image was
  built, and nothing refreshes it. That is the honest cost of having no outbound
  route, and it is what the plugin is for. The list is updated when somebody
  updates it, like any other vendored dependency.
- **Startup reads 100 000 lines and hashes them.** Tens of milliseconds, once,
  on a path that already waits for a database.
- **The message names no list.** Somebody choosing a password is told it is
  already known to attackers and nothing about where from: the useful half is
  "choose another", and naming the source only invites an argument about it.
- **Length is reported before breach**, because "too short" is the fixable
  thing and a password that is both should be told the simpler truth.
- **The port is the fifteenth**, so `REQ-PLG-001`, `PortCatalogueTest`, the
  contract's own README and `CLAUDE.md` all say fifteen now. `ADR-0064`'s body
  still says fourteen and is left alone: it records what was true when it was
  written, and the note at its head says what changed.
- **It is the second caller of `lookupForInstance`**, after the account
  notifications of [ADR-0066](0066-instance-level-capability-grants.md). A
  password is chosen where there is often no tenant — at registration and at a
  reset from the login page — so a per-tenant resolution would leave the plugin
  silent on exactly the paths that matter. The ArchUnit rule now names two
  blocks instead of one, which is still a list and not a door.
- **The vendored file is third-party content in a public repository**, which
  means `REUSE.toml` carries its copyright and MIT licence explicitly. Without
  that entry the repository would declare somebody else's list as AGPL, which is
  the error `ADR-0000` A4 was about.
