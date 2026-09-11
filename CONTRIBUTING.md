# Contributing to Home Inventory

Thank you for taking the time. This document tells you what the project expects,
so that your work does not bounce on a formality.

## Where the project stands

**Stage 0 is being implemented.** The architecture, the decisions behind it and
the requirements catalogue came first and are complete — deliberately: for a
system with multi-tenancy, offline sync and third-party plugin code, the
expensive mistakes are made before the first line of code. They are now the
specification the implementation is measured against, and they move with it in
the same pull request.

The most valuable contributions right now are therefore:

- **Finding a hole in the design.** Read [`docs/`](docs/README.md) and tell us
  where it does not hold. An issue that says "your three-way merge loses data in
  this sequence" is worth more than a pull request.
- **Challenging a decision.** Every one is written down as an
  [ADR](docs/adr/README.md) with its alternatives. If an alternative was
  dismissed too easily, say so — the ADR format exists precisely to make that
  arguable.
- **Sharpening a requirement.** A requirement that cannot be tested is a bad
  requirement. See [`docs/requirements/`](docs/requirements/README.md).

Once implementation starts this section will be replaced by build and test
instructions.

## Before you start

- Read the [Code of Conduct](CODE_OF_CONDUCT.md). It applies everywhere in this
  project.
- For anything larger than a typo, **open an issue first**. It is a poor trade
  for both of us if you build something for a week that does not fit the design.
- For a security issue, do **not** open an issue — see [SECURITY.md](SECURITY.md).

## The two things every contribution needs

### 1. A DCO sign-off on every commit

Every commit carries a `Signed-off-by:` trailer certifying the
[Developer Certificate of Origin 1.1](https://developercertificate.org/). Use the
flag; never type the trailer by hand:

```bash
git commit -s -m "feat(catalog): add value list inheritance"
```

`-s` derives the trailer from your `git config user.name` and `user.email`, which
is why it is correct and a hand-written one so often is not. The trailer must
match the commit author. If you forget it and have not pushed yet:

```bash
git commit --amend --signoff --no-edit    # the last commit
git rebase --signoff main                 # the whole branch
```

### 2. A signed CLA

In addition to the DCO, third-party contributions need a signed Contributor
Licence Agreement — see [`cla/`](cla/README.md). The DCO attests where the code
came from; the CLA grants the rights that a later relicensing or a commercial
dual licence would require ([ADR-0018](docs/adr/0018-licensing.md)).

We know this is a barrier, and we would rather name it than bury it. It is asked
once, not per pull request. A bot will comment on your first pull request with
the link.

## Commits and pull requests

| Rule | Detail |
|---|---|
| **Conventional Commits** | `feat(inventory): …`, `fix(sync): …`, `docs(adr): …`, `refactor`, `test`, `build`, `ci`, `chore`. The scope is the building block or the area. |
| **English, always** | Commit messages, branch names, PR titles and bodies, issue text, code comments and Javadoc — regardless of the language you write to the maintainer in. The only user-visible German lives in the UI resource bundles. |
| **One concern per pull request** | A refactor mixed with a behaviour change is twice as hard to review and twice as hard to revert. |
| **The documentation moves with the change** | A behaviour change with no matching requirement change is incomplete. So is a decision without an ADR. This is not a nicety here: `docs/` *is* the specification. |
| **No merge commits on a branch** | Rebase onto `main`. |
| **Do not force-push a shared branch** | Once someone else has it, rewriting history costs them more than it saves you. |

## What will be checked once CI exists

The gates are already decided and written down; expect them from stage 0 on:

- Module boundaries (Spring Modulith + ArchUnit) — a violation fails the build
- Every endpoint has a permission check and negative tests for `403`/`404`
- Tenant isolation proven automatically across every table
- No `privileged`, no `cap_add`, no port below 1024, no mounted container socket
- API contracts free of unannounced breaking changes (`oasdiff`, `buf breaking`)
- `gitleaks`, dependency scanning, SAST
- Formatting and linting, all green before every push

The details are in [`docs/requirements/`](docs/requirements/README.md); the gate
list per area is in
[12 §12.12](docs/architecture/12-security.md) and
[14 §14.2](docs/architecture/14-quality-risks-glossary.md).

## Contributing a plugin

Plugins are not contributed to this repository — they live in their own. What
this repository holds is the contract (`proto/`), the API module
(`plugin-api/`, Apache-2.0 so your plugin may be licensed as you wish) and a
curated list of known plugins in [`PLUGINS.md`](PLUGINS.md). Adding a line to
that list is a pull request; it is not a distribution channel and carries no
security assurance from this project.

## Questions

Open a discussion or an issue. A question that turns out to be a gap in the
documentation is itself a contribution — and gets fixed in the documentation
rather than answered once in a thread.
