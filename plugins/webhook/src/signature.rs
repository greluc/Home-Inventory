// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! The signature a receiver checks (`REQ-API-010`).
//!
//! HMAC-SHA256 over **the timestamp and the body**, keyed with the secret the
//! **tenant** configured and the core delivered in the call envelope
//! (ADR-0073). Without it a webhook is an unauthenticated POST that anybody who
//! learns the URL can forge, which is the failure mode every webhook
//! integration eventually meets.
//!
//! # Why the timestamp is inside the signed material
//!
//! Because `REQ-API-010` says so, and it says so for a reason a signature alone
//! does not answer: a captured request is a valid request for ever. With the
//! timestamp signed, a receiver rejects anything outside its tolerance window
//! and a capture is worth minutes rather than for ever. Signing the body and
//! sending the timestamp beside it would be worse than not sending one, because
//! an attacker would simply change it.
//!
//! # Where the arithmetic lives
//!
//! In `homeinv_plugin_common::mac`, since `plugins/blobstore-s3/` became the
//! second plugin to need HMAC-SHA256 — Signature Version 4 is a chain of four of
//! them. What stays here is what is specific to a webhook: WHICH bytes are
//! signed, which is the part a receiver has to reproduce.

use homeinv_plugin_common::encoding::hex;
use homeinv_plugin_common::mac::hmac_sha256;

/// Signs a timestamp and a body.
///
/// The signed material is `<timestamp>.<body>` — the seconds since the epoch, a
/// full stop, then the exact bytes that go on the wire. A separator that cannot
/// occur in a decimal timestamp is what keeps `1.{"a":1}` from being confusable
/// with `1.{` plus `"a":1}`.
///
/// # Arguments
///
/// * `secret` — the tenant's signing secret, as the envelope delivered it
/// * `timestamp` — seconds since the epoch, as the header carries it
/// * `body` — the exact bytes that go on the wire, before any encoding
///
/// # Returns
///
/// The digest as lower-case hex, which is what the header carries.
pub fn sign(secret: &[u8], timestamp: u64, body: &[u8]) -> String {
    let mut material = Vec::with_capacity(body.len() + 16);
    material.extend_from_slice(timestamp.to_string().as_bytes());
    material.push(b'.');
    material.extend_from_slice(body);
    hex(&hmac_sha256(secret, &material))
}

/// The same, for the RFC vectors, which sign a message and not a delivery.
#[cfg(test)]
fn sign_raw(secret: &[u8], message: &[u8]) -> String {
    hex(&hmac_sha256(secret, message))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_signature_changes_with_the_body() {
        let one = sign(b"secret", 1_700_000_000, b"{\"event\":\"a\"}");
        let two = sign(b"secret", 1_700_000_000, b"{\"event\":\"b\"}");
        assert_ne!(one, two);
    }

    #[test]
    fn the_signature_changes_with_the_key() {
        // The property a receiver relies on: a body signed with somebody else's
        // secret does not verify with theirs.
        assert_ne!(
            sign(b"mine", 1_700_000_000, b"body"),
            sign(b"theirs", 1_700_000_000, b"body")
        );
    }

    #[test]
    fn the_signature_changes_with_the_timestamp() {
        // The property REQ-API-010 asks for: a captured request cannot be
        // replayed with a fresh timestamp, because the timestamp is inside what
        // was signed.
        assert_ne!(
            sign(b"secret", 1_700_000_000, b"body"),
            sign(b"secret", 1_700_000_060, b"body")
        );
    }

    #[test]
    fn the_signed_material_is_the_timestamp_a_stop_and_the_body() {
        // Written out, because a receiver in another language has to reproduce
        // it exactly and this is the line that says how.
        assert_eq!(
            sign(b"secret", 1_700_000_000, b"body"),
            sign_raw(b"secret", b"1700000000.body")
        );
    }
}
