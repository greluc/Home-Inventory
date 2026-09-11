<!--
Thank you for the contribution. Please fill in what applies and delete what does
not — an honest short description beats a complete but padded one.
-->

## What this changes

<!-- One or two sentences. What is different after this is merged? -->

## Why

<!-- The problem, not the solution. Link the issue if there is one. -->

Closes #

## Documentation moved with it

`docs/` is the specification of this project, not a description of it. A change
that leaves it stale is incomplete.

- [ ] The **requirement** this implements or changes is updated
      (`REQ-…`, in [`docs/requirements/`](../docs/requirements/README.md))
- [ ] The affected **architecture chapter** is updated
- [ ] A new **ADR** is written, if a load-bearing decision was made or changed
- [ ] `CHANGELOG.md` has an entry, if this is user-visible
- [ ] Nothing in the documentation now contradicts this change

<!-- If none of these apply, say why in one line. "Typo fix" is a fine answer. -->

## Contract impact

- [ ] No contract changed
- [ ] **REST API** — and the change is additive, or a new major version is opened
- [ ] **Plugin contract** (`home_inv.plugin.v1`) — `buf breaking` passes
- [ ] **Event schema** — consumers stay compatible
- [ ] **Database schema** — the migration is backward compatible and holds no lock longer than 5 s

## Security

- [ ] No new endpoint, or every new endpoint has a permission check **and**
      negative tests for `403` (no permission) and `404` (foreign tenant)
- [ ] No new table, or it carries `tenant_id` and RLS with `FORCE`
- [ ] No secret, token, key or personal data added to the repository,
      to a log line, or to an error response
- [ ] No deployment description gained `privileged`, `cap_add`, a host network,
      a mounted container socket or a port below 1024

## Checks

- [ ] Every commit carries a DCO sign-off (`git commit -s`)
- [ ] The CLA is signed (first contribution only)
- [ ] Commit messages follow Conventional Commits
- [ ] Everything I wrote — commits, comments, documentation — is in English
- [ ] Tests cover the change; they fail without it

## Anything a reviewer should look at first

<!--
Optional, and the most useful box on this form. Where are you unsure? What did
you decide against? Which part deserves a second opinion?
-->
