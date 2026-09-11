# ADR-0040 — `Cross-Origin-Embedder-Policy` is dropped; `COOP` and `CORP` stay

**Status:** Accepted · **Date:** 2026-09-11
**Resolves:** O15 in [ADR-0000](0000-open-points.md)
**Amends:** [12 §12.9](../architecture/12-security.md), `REQ-SEC-061`,
[09 §9.4](../architecture/09-extensibility-and-plugins.md), `REQ-PLG-012`

## Context

The header set in [12 §12.9](../architecture/12-security.md) sends
`Cross-Origin-Opener-Policy: same-origin` **and**
`Cross-Origin-Embedder-Policy: require-corp`. Together those two make
`self.crossOriginIsolated` true, which is the only thing `require-corp` is for.

### What cross-origin isolation buys

After Spectre (2018) browsers withdrew the two primitives that make a timing
side channel practical, and hand them back only to a page that can prove nothing
unvetted shares its renderer process:

| Re-enabled | Needed by |
|---|---|
| **`SharedArrayBuffer`** | WebAssembly **threads** — no SAB, no `pthread`, no `atomics`, no parallel WASM |
| **`performance.now()` at full resolution** | Otherwise coarsened to ~100 µs, which *is* the mitigation |
| `measureUserAgentSpecificMemory()` and similar | Memory diagnostics |

`require-corp` is that proof: every cross-origin subresource must carry a
`Cross-Origin-Resource-Policy` saying it consents to being loaded here.

### Nothing in this design asks for it

| Where isolation would be needed | What we actually do |
|---|---|
| Threaded WASM | The only WASM is **ZXing as a fallback** in a web worker ([10 §10.3](../architecture/10-identification-and-labels.md)) — a few milliseconds of scalar work on one camera frame, single-threaded, as the shipped build is. A threaded build would fail **loudly** at instantiation, not silently |
| Microsecond timing in the browser | Nothing measures at that resolution. The performance budgets (`REQ-NFR-001`…`003`) are server-side p95 |
| SQLite-WASM with OPFS sync handles | Local web storage is **IndexedDB** ([11 §11.8](../architecture/11-offline-synchronisation.md)), which needs no isolation |
| Threaded codecs in the apps | The apps use SQLDelight on native SQLite and native cameras — not a browser question at all |

And the argument that settles it: **cross-origin isolation protects against
reading cross-origin data you were not meant to see. The only cross-origin
resources this page embeds come from its own media host** — the operator's own
deployment, serving the very tenant's own files that the page is displaying to
that tenant's own user. There is nothing in the process to steal.

### What it costs, twice, and both fail silently

1. **The media host, from stage 0.** `HOMEINV_MEDIA_BASE_URL` is deliberately a
   separate origin ([12 §12.7](../architecture/12-security.md)) so a malicious
   upload cannot touch the application origin. Under `require-corp` every image
   on every list and detail view needs a CORP value that permits it, or **no
   image loads** — a console message nobody reads. This half was caught and
   documented; it is nonetheless paid from the first release (`REQ-MED-010` is
   stage 0).
2. **The plugin panel, and this half nobody caught.** Under `require-corp` a
   cross-origin **document** the page frames must send `require-corp` *itself*;
   a CORP header on it is not enough. A third-party author who does not know
   this ships a panel that renders as an empty frame, with no way to find out
   why — the failure lands on someone who cannot debug it.

## Options

| Option | For | Against |
|---|---|---|
| **Drop COEP; keep COOP and CORP** | Removes both silent failure modes, one of which lands on third parties · gives up a property nothing uses · COOP keeps doing the work it does independently | Gives up genuine defence-in-depth, and reversing it later means re-auditing both obligations |
| Keep it and write the panel obligation into the plugin contract | Defence-in-depth against Spectre-class bugs on devices with relaxed site isolation | Pays both costs permanently for a capability nothing exercises, and makes a third party's first contact with our contract a header they never heard of |
| Leave it open until stage 3 | Costs nothing to decide later | The media-host half is paid from stage 0 regardless, and a decision deferred three times is not better the fourth |

## Decision

**`Cross-Origin-Embedder-Policy` is not sent.** The application is not
cross-origin isolated, and no part of the design requires it to be.

| Header | Status |
|---|---|
| `Cross-Origin-Opener-Policy: same-origin` | **stays.** It severs window references to cross-origin openers and works entirely on its own — it is the one of the pair that earns its place here |
| `Cross-Origin-Resource-Policy: same-site` on the application | **stays** |
| **CORP on media responses** | **stays, and stays load-bearing** — see the note below |
| `Cross-Origin-Embedder-Policy: require-corp` | **removed** |

> **CORP is not a consequence of COEP, and dropping COEP does not make it
> optional.** The two are easy to conflate. CORP is enforced on every cross-origin
> no-cors subresource load regardless of COEP: a media response sending
> `Cross-Origin-Resource-Policy: same-site` from a **different registrable
> domain** than the application is blocked either way. What COEP changed is the
> other direction — it made an *absent* CORP header fatal. So:
>
> | Media host | CORP value | Without COEP |
> |---|---|---|
> | subdomain of the app's registrable domain | `same-site` | loads |
> | different registrable domain | `same-site` | **still blocked** |
> | different registrable domain | `cross-origin` | loads |
> | any | header absent | loads — but then anyone may hot-link our media |
>
> The startup check that warns when the media host is not same-site with the
> application host therefore **stays exactly as it was** (`REQ-SEC-061`). CORP is
> kept deliberately, to stop foreign sites embedding tenant media, not as a
> leftover of COEP.

### When it comes back

Written down so the reversal is a decision and not a rediscovery: **the moment
anything in the web client wants WebAssembly threads or a high-resolution timer**
— a threaded image or video decoder, a WASM SQLite with OPFS sync handles, a
parallel scan pipeline — cross-origin isolation becomes a prerequisite, COEP
returns, and **both obligations return with it**. Re-auditing them then costs
exactly what carrying them today would have cost, which is why paying it now
buys nothing.

## Rationale

The header is not wrong; it is inert here. It is written to keep unvetted
cross-origin resources out of the renderer process, and this application embeds
none — its one cross-origin origin is a hostname the same operator runs, serving
the same tenant's own files to the same user who is already looking at them.

Against an empty benefit sits a real cost, and the shape of the cost is what
decides it. The media half is merely fiddly, and we had already caught it. The
panel half is worse in kind: it makes a third-party plugin author's first
encounter with this system a blank iframe caused by a header nobody told them
about. This project's stated posture towards plugin authors is a *"stable,
documented, versioned contract"* ([01 §1.4](../architecture/01-introduction-and-goals.md));
a mandatory header discovered by debugging is the opposite.

**The counter-argument is real and is recorded rather than dismissed.** COEP is
genuine defence-in-depth, and Chrome relaxes site isolation on low-memory Android
devices — exactly the device class this product is designed around
([ADR-0033](0033-dark-as-default-appearance.md): the cellar, the phone, one hand).
On such a device, site isolation may not separate our renderer from another
site's. What that protection would guard here is still a media host we run and a
panel origin we run, so the residual exposure it removes is close to nothing —
but the protection itself is not imaginary, and anyone revisiting this should
weigh it rather than assume it was overlooked.

## Consequences

- **[12 §12.9](../architecture/12-security.md) loses one line** and gains the
  reasoning above, so the absence is deliberate rather than an omission a future
  reader "fixes".
- **`REQ-SEC-061` drops COEP** from its list and keeps HSTS, `nosniff`,
  `Referrer-Policy`, `Permissions-Policy`, COOP and CORP — including the
  same-site startup check, whose justification changes from "COEP requires it" to
  "CORP is enforced anyway, and we want it".
- **`REQ-PLG-012` and [09 §9.4](../architecture/09-extensibility-and-plugins.md)
  lose the panel COEP requirement.** The panel contract is back to what it
  should have been: own origin, `sandbox`, `postMessage` with a verified origin,
  a fixed message schema, and the panel's own `frame-ancestors`.
- **`self.crossOriginIsolated` is `false`.** A CI assertion records that as the
  expected state, so a library that silently needs SAB fails a test rather than a
  user's scan.
- **The header comparison in CI shrinks by one line** and gains a negative case:
  a policy that *adds* COEP fails, because adding it re-imposes two obligations
  that nothing else in the corpus states any more.
- **O15 is closed.** ADR-0000 has no open decision again.
