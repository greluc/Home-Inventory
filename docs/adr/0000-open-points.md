# ADR-0000 — Open Points

**Status:** ongoing · **Date:** 2026-09-11

A collection point for decisions not yet taken.

> **As of 2026-09-11: no open decision remains.** All ten original points are
> decided; the table stays as a record of what was decided, when and how. New
> open points are added here.

## Outstanding work (no open decisions)

These items are **decided but not yet carried out**. They do not belong in the
table below, but they must not get lost either:

| # | Outstanding | By when |
|---|---|---|
| A1 | ~~Translating the documentation into English~~ — **done 2026-09-11.** The whole corpus was translated and the files renamed to English names (REQ-CON-002, REQ-CON-012). | — |
| A2 | ~~Verifying the label geometries~~ — **done 2026-09-11.** Starter catalogue created ([`docs/reference/label-media.yaml`](../reference/label-media.yaml)): six formats verified, one explicitly marked as derived. **Remaining:** add the printable width of the Brother DK rolls from the SDK data sheet. | Open only for new formats |
| A3 | ~~Verifying the Podman package versions~~ — **done 2026-09-11.** Checked across both package families: Debian 13 ships Podman **5.4.2**, Fedora 43–45 **5.8.4–6.1.0**, the RHEL family from 9.5 resp. 10 **≥ 5.0**. The version matrix, the per-family differences, the finding about `uidmap`/`passt`/`dbus-user-session` being mere *Recommends* and the SELinux analysis are in [06 §6.2](../architecture/06-deployment-view.md). | — |

| A4 | **Licence compliance for Lucide.** The set is `ISC AND MIT` — see below; the obligations are known and the steps are listed there. Nothing is due while Lucide is only a package dependency. Due **with the first vendored SVG** and **with the first distributed build**. | See A4 notes |

### A4 notes — what Lucide obliges us to do

[Lucide](https://github.com/lucide-icons/lucide) is a fork of Feather, and its
`LICENSE` is **one file containing two licences**:

- **ISC**, © Lucide Contributors — for the set as a whole
- **MIT**, © Cole Bemis 2013–present — for roughly 150 named icons inherited
  from Feather, listed by name in that same file

Both are permissive and both combine one-way into AGPL-3.0-or-later without
friction. Neither demands attribution in the user interface, a "powered by", or
any change to our own licence. What they do demand is that **the copyright and
permission notice appears in all copies** — and that is the part that is easy to
fail, because a minified bundle is a copy and the `LICENSE` sitting in this
repository does not travel with it (REQ-CON-013).

Concretely, when the time comes:

1. **Ship the `LICENSE` whole and verbatim.** Copying only the ISC half is the
   obvious mistake and it under-attributes the ≈150 MIT icons. The list of icon
   names is part of the licence text, not decoration.
2. **Record the set in the SBOM as `ISC AND MIT`** (REQ-CON-010), not as ISC.
3. **Only when SVGs are vendored** into `web/`: add `LICENSES/ISC.txt` and
   `LICENSES/MIT.txt` and annotate the files in [`REUSE.toml`](../../REUSE.toml).
   Without that they are swept up by the `web/**` default and silently declared
   AGPL-3.0-or-later — a false statement about someone else's work.
   **Do not add those two licence files early**: `reuse lint` reports a licence
   file that nothing references as an error, so they belong in the same commit
   as the icons.
4. **Keep the notice in the built artifacts** — bundle, container image, app
   packages — and reachable from the running installation beside the version and
   source link (REQ-CON-009, REQ-CON-013).

## Decided points

| # | Point | Why it was open | Latest decision date |
|---|---|---|---|
| O1 | ~~Product name~~ — **decided:** *Home Inventory*; technical identifiers consistently in the short form `home-inv` / `homeinv` / `de.greluc.homeinv`. The **repository** is `https://github.com/greluc/Home-Inventory` — the one long-form exception, set when the origin was created. The container image is named explicitly (`ghcr.io/greluc/home-inv`), because derived from the repository it would read `home-inventory`. | — | **done** |
| O2 | ~~Domain for code resolution~~ — **decided:** the base URL is **deployment configuration** (`HOMEINV_PUBLIC_BASE_URL`), with confirmation before the first print, a startup warning, an operations banner, `HOMEINV_LEGACY_BASE_URLS` and a documented migration path ([10 §10.2.1](../architecture/10-identification-and-labels.md)). | — | **done**; what remains is only which value you set for your own instance |
| O3 | ~~Mandatory virus scanning?~~ — **decided:** ClamAV is a fixed part of the stack, the scan is **fail-closed** ([ADR-0024](0024-malware-scan.md)). | — | **done** |
| O4 | ~~Languages~~ — **decided:** UI in German and English (English as the fallback); **the entire documentation in English**. | — | **done**, translation carried out |
| O5 | ~~Retention periods~~ — **decided:** configurable per tenant within fixed bounds; application logs and IP addresses remain instance-wide (REQ-PRIV-010). | — | **done** |
| O6 | ~~Curated plugin directory~~ — **decided:** a curated list (`PLUGINS.md`) in the repository, admission by pull request, **without** distribution, counter-signature or security assurance (REQ-PLG-014). | — | **done** |
| O7 | ~~Contribution model~~ — **decided:** **DCO and CLA**. Requires a CLA assistant in the pull request flow and a durable record of signatures ([ADR-0018](0018-licensing.md)). | — | **done** |
| O8 | ~~Valuation~~ — **decided:** **current value and replacement value** are kept and reported separately, plus an insurance report (REQ-LIFE-009/014/015/016). | — | **done** |
| O9 | ~~Push notifications~~ — **decided:** Firebase and APNs, but as a **plugin** and with a **content-free payload** ([ADR-0023](0023-push-notifications.md)). The core keeps its "no outbound route" rule. | — | **done** |
| O10 | ~~Accessibility level~~ — **decided:** WCAG 2.2 AA as the goal, verified automatically (`axe` in CI, keyboard operation, contrast, focus indication), **without** a formal conformance statement. | — | **done** |
