# ADR-0000 — Open Points

**Status:** ongoing · **Date:** 2026-09-11

A collection point for decisions not yet taken.

> **As of 2026-09-11: no open decision remains.** The architecture review of that
> day closed eight questions, surfaced four more (O11–O14), and those four are now
> decided too. The table below is the record of what was decided, when and how.
> New open points are added here.

## Outstanding work (no open decisions)

These items are **decided but not yet carried out**. They do not belong in the
table below, but they must not get lost either:

| # | Outstanding | By when |
|---|---|---|
| A1 | ~~Translating the documentation into English~~ — **done 2026-09-11.** The whole corpus was translated and the files renamed to English names (REQ-CON-002, REQ-CON-012). | — |
| A2 | ~~Verifying the label geometries~~ — **done 2026-09-11.** Starter catalogue created ([`docs/reference/label-media.yaml`](../reference/label-media.yaml)): six formats verified, one explicitly marked as derived. **Remaining:** add the printable width of the Brother DK rolls from the SDK data sheet. | Open only for new formats |
| A3 | ~~Verifying the Podman package versions~~ — **done 2026-09-11.** Checked across both package families: Debian 13 ships Podman **5.4.2**, Fedora 43–45 **5.8.4–6.1.0**, the RHEL family from 9.5 resp. 10 **≥ 5.0**. The version matrix, the per-family differences, the finding about `uidmap`/`passt`/`dbus-user-session` being mere *Recommends* and the SELinux analysis are in [06 §6.2](../architecture/06-deployment-view.md). | — |
| A4 | ~~Licence compliance for Lucide~~ — **decided and moved 2026-09-11.** It is a settled decision with dated obligations, not an open point, and belongs in an ADR: see [ADR-0034](0034-icon-set-and-no-third-party-hosts.md), which carries the full `ISC AND MIT` analysis and the four concrete steps. **Carried out 2026-09-11** when the design system vendored 121 Lucide SVGs and IBM Plex: `LICENSES/{ISC,MIT,OFL-1.1}.txt` added from the canonical SPDX texts, both asset directories annotated in [`REUSE.toml`](../../REUSE.toml). **Remaining:** the notice must travel with the built artifacts (REQ-CON-013), and the OFL Reserved Font Name question is settled in [ADR-0035](0035-design-system.md). | With the first build |
| A5 | **Verify that port publishing works from an internal network.** [ADR-0027](0027-egress-enforcement.md) gives the core no outbound route by putting every core segment on `internal`, relying on the runtime forwarding a published port into the container's namespace without a routed path outward. That must be confirmed under **rootless Podman with `pasta`** and under **rootless Docker**. If a runtime does not support it, the fallback is a minimal ingress container on a non-internal segment forwarding to the core on an internal one — the property is kept, the topology changes. Same class of item as `UserNS=auto` in [06 §6.5](../architecture/06-deployment-view.md). | Stage 0 — it decides the network layout |

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
| O11 | ~~SMTP egress enforcement~~ — **decided:** the `egress-proxy` gains a plain **TCP forwarding mode** with an exact `host:port` allowlist from the manifest, alongside its HTTP/`CONNECT` mode ([ADR-0027 §3](0027-egress-enforcement.md)). One container, one allowlist source, one access log; STARTTLS and implicit TLS pass through untouched. Rejected: a second forwarder container (two sources, two logs), a minimal MTA (largest attack surface, duplicates the notification block's retry), and an unrestricted segment for `plugin-smtp` (no enforcement for the plugin that carries invitation tokens). | The egress proxy spoke only HTTP, and `plugin-smtp` became stage-1 infrastructure through [ADR-0028](0028-plugin-runtime-stage-1.md) | **done** |
| O12 | ~~UUID in the QR fragment~~ — **decided:** the fragment **stays**, and the fourth reason in [10 §10.1](../architecture/10-identification-and-labels.md) ("information leakage") is **withdrawn**, because it contradicted the very label it was written about. Offline resolution works from *any* label, including one whose binding a device has never synchronised — that is worth more than concealing a creation timestamp. The disclosure is recorded as an accepted risk in [12 §12.3](../architecture/12-security.md) instead of going unmentioned; an operator who disagrees switches the tenant to the host-free code form. | The chapter argued against printing the UUID and then printed it | **done** |
| O13 | ~~Where idempotency records live~~ — **decided:** in **PostgreSQL, in the same transaction as the record they protect** ([ADR-0009](0009-messaging-and-events.md)) — never in a cache. The same argument the outbox rests on: two stores cannot be made consistent by hoping, and here they need not be. Valkey may cache the lookup; it is never the source. | `REQ-API-005` is priority M for mobile clients, and it hung on a store whose loss was documented as harmless | **done** |
| O14 | ~~Relative changes in the sync protocol~~ — **decided:** a change to a numeric field carries `intent: SET \| ADJUST` ([ADR-0014](0014-offline-synchronisation.md)). Deriving deltas unconditionally would have needed no protocol change and was rejected: a stocktake correction concurrent with an offline withdrawal would then count the withdrawal twice — a wrong number arrived at quietly, which is the failure class that chapter exists to prevent. Free now; after the contract is published it would have been a breaking change. | The deltas were derivable from the three-way compare, the **intent** was not | **done** |
