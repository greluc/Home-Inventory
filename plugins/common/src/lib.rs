// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! What every first-party plugin in Rust needs, and needs identically.
//!
//! ADR-0072 said when this crate would exist: *"The second one moves it into a
//! crate the plugins share, which is the point at which a shared crate costs
//! less than the duplication does."* `plugins/smtp/` is that second one, and
//! these are the pieces more than one of them had written out: the mTLS identity,
//! the CONNECT tunnel, base64 and hex, the delivered-key set, HMAC-SHA256 and the
//! calendar arithmetic behind a UTC stamp.
//!
//! What is deliberately **not** here: anything about what a plugin does. The
//! contract types are generated per crate from `proto/`, the ports are each
//! plugin's own, and a helper that guessed at "what a notification looks like"
//! would be an SDK — which is stage 3, written against the published contract
//! rather than extracted from two users of it (`REQ-PLG-009`).
//!
//! `blobstore/` and `egress-proxy/` do not depend on this crate and will not:
//! they are **core services** rather than plugins ([ADR-0043], [ADR-0050]), and
//! a shared crate between the two sides would be the dependency the licence
//! separation exists to prevent.

pub mod blob;
pub mod encoding;
pub mod http;
pub mod keys;
pub mod mac;
pub mod proxy;
pub mod time;
pub mod tls;
