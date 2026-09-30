# Individual Contributor Licence Agreement

**Home Inventory** · Version 1.0 · 2026-09-16

> **Not reviewed by a lawyer.** This document follows the structure of the Apache
> Software Foundation's Individual CLA and is written in this project's own
> words, as a **grant of rights of use** rather than a transfer of copyright —
> German law does not permit transferring copyright itself (§ 29 UrhG). It needs
> review by somebody qualified before the first third-party contribution is
> accepted. Nothing here is legal advice, and if something in it concerns you,
> say so in the pull request rather than signing around it.

You keep your copyright. What you give here is permission — broad, irrevocable
permission, but permission.

## 1. What the words mean

**"You"** is the person signing. **"The project"** is Home Inventory, and
**"the maintainer"** is its copyright holder, currently Lucas Greuloch.

**"Contribution"** is anything you deliberately submit to the project for
inclusion: code, documentation, translations, designs, configuration, test data.
A pull request is a submission. So is a patch in an issue. A comment saying "you
could do it this way" is **not** — a contribution is something you offer *as*
work to be included, and if that is ever unclear, say so in writing and nothing
here applies to it.

## 2. Copyright licence

You grant the maintainer and everyone who receives the software a **perpetual,
worldwide, non-exclusive, irrevocable, royalty-free** licence to use your
contribution: to reproduce it, to modify it, to create derived works from it, to
distribute it, to sublicense it, and to do so **under any licence terms**,
including terms different from the project's current ones.

That last clause is the reason this document exists. It is what makes it possible
to relicense the project later, or to offer a commercial licence alongside the
AGPL, without tracking down every past contributor
([ADR-0018](../docs/adr/0018-licensing.md) explains the reasoning). It is also
the clause worth reading twice before signing.

## 3. Patent licence

You grant the same people a perpetual, worldwide, non-exclusive, irrevocable,
royalty-free **patent licence** covering any patent claim you own or control
that your contribution would otherwise infringe — but only to the extent your
contribution, alone or combined with the project, infringes it.

If you begin patent litigation alleging that the project or a contribution to it
infringes a patent, the patent licence granted to **you** under this agreement
ends on the day you file.

## 4. What you are stating

- The contribution is **your own work**, or you have the right to submit it and
  to grant the licences above.
- If your employer has rights in work you create, you have either their
  permission to contribute, or they have waived those rights for this
  contribution, or they have signed the [entity agreement](entity-cla.md).
- Every third-party element in your contribution that you did not write is
  **identified as such**, with its licence and its origin, in the contribution
  itself.
- You are not aware of any claim, lien or agreement that would conflict with
  what you grant here.

## 5. What you are not promising

The contribution is provided **as is**. You make no warranty of any kind about
it — not of merchantability, not of fitness for a purpose, not of
non-infringement beyond what section 4 says you know.

## 6. Keeping it accurate

If anything in section 4 stops being true — you discover a third-party element
you had not noticed, or your employer's position changes — tell the maintainer.
An honest correction is not a problem. A stale statement is.

## 7. How this is recorded

Signing happens in the pull request: the automation comments on your first one
and the signature is recorded against your GitHub account in this repository, so
it can be read by anybody and survives the tooling that collected it. It is asked
**once** and covers every later contribution.

Every commit additionally carries a DCO `Signed-off-by:` trailer, which is a
different statement — about where the code came from rather than about what may
be done with it (`REQ-CON-003`, `REQ-CON-011`).
