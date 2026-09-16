# Contributor Licence Agreement

Contributions to Home Inventory need **two** things: a DCO sign-off on every
commit, and a signed CLA once per contributor.

| | What it does |
|---|---|
| **DCO** (`git commit -s`) | You certify that you have the right to submit this code. Per commit, no signature ceremony. |
| **CLA** (this directory) | You grant the project the rights it needs to keep distributing your contribution, including under a different licence later. Once per contributor. |

They are not redundant. The DCO is about **provenance** — where the code came
from. The CLA is about **rights** — what may be done with it afterwards.

## Which one do I sign

| Situation | Document |
|---|---|
| You contribute on your own behalf | [Individual CLA](individual-cla.md) |
| Your employer owns your work, or you contribute on behalf of a company | [Entity CLA](entity-cla.md) — signed by someone authorised to bind the company. You may still need the individual one as well. |

If you are employed and contributing in your own time, check your employment
contract before assuming the individual agreement is enough. Many contracts
assign to the employer anything written in the field of their business,
regardless of when or on whose hardware. We would rather you find that out now
than after a merge.

## Why a CLA at all

We will not pretend this is free of cost. A CLA is a real barrier — some people
will not sign one, and the project will lose contributions because of it. The
reason it is asked anyway
([ADR-0018](../docs/adr/0018-licensing.md)):

The core is AGPL-3.0-or-later. Without a CLA, that choice is permanent: with
contributions from many copyright holders, relicensing later would require
every one of them to agree, and in practice that means it never happens. The CLA
keeps one door open — a commercial dual licence, or a move to a different licence
if the AGPL turns out to be the wrong call.

**What the CLA does not do:** it does not take your copyright away. You keep it.
You may use your own contribution however you like, including in a competing
project. What you grant is a licence, not ownership.

## How to sign

On your first pull request a check reports that no signature is on file and says
what to do. You read the applicable document and write one comment on the pull
request:

```
I have read the CLA Document and I hereby sign the CLA
```

That is the whole ceremony — nothing to print, scan or e-mail. The signature is
recorded against your GitHub account in `signatures/version1/cla.json` on the
`cla-signatures` branch of this repository, where anybody can read it and where
it outlives whatever tooling collected it. It covers every later contribution.

**The check blocks the merge; the comment is what clears it.** If you are
contributing on behalf of an employer, the [entity agreement](entity-cla.md) is
the one that applies, and many people need both.

## A necessary caveat

> **These documents are drafts and have not been reviewed by a lawyer.**
>
> They were written on 2026-09-16 and are version 1.0 of each.
>
> They follow the structure of the widely used Apache Software Foundation CLAs,
> adapted for this project. They have not been checked by anyone qualified to
> check them, and nothing here is legal advice.
>
> Before the first third-party contribution is accepted, they need review by a
> lawyer familiar with German copyright law — in particular because German law
> does not permit transferring copyright itself (§ 29 UrhG), only granting rights
> of use, which is why these documents are written as licence grants throughout.
>
> If you are about to sign one and something in it concerns you, say so in the
> pull request. That is a useful thing to hear, not an inconvenience.
