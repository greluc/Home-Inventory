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
//! # Why this is thirty lines rather than a dependency
//!
//! RFC 2104 is `H((K ^ opad) || H((K ^ ipad) || message))` and nothing else. The
//! `hmac` crate would pin a `digest` version that has to match the one `sha2`
//! brings, a coupling that breaks on every digest release for no gain — and this
//! container is budgeted the way the two other Rust services are. The RFC 4231
//! vectors are below; an implementation that passes them is the algorithm.

use sha2::{Digest, Sha256};

/// How many bytes SHA-256 consumes at a time. The key is padded to it.
const BLOCK: usize = 64;

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

/// HMAC-SHA256, RFC 2104.
fn hmac_sha256(key: &[u8], message: &[u8]) -> [u8; 32] {
    // A key longer than the block is hashed first; a shorter one is padded with
    // zeroes. Both are the RFC's own rule rather than a choice.
    let mut block = [0_u8; BLOCK];
    if key.len() > BLOCK {
        let digest = Sha256::digest(key);
        block[..digest.len()].copy_from_slice(&digest);
    } else {
        block[..key.len()].copy_from_slice(key);
    }

    let mut inner_key = [0_u8; BLOCK];
    let mut outer_key = [0_u8; BLOCK];
    for index in 0..BLOCK {
        inner_key[index] = block[index] ^ 0x36;
        outer_key[index] = block[index] ^ 0x5c;
    }

    let mut inner = Sha256::new();
    inner.update(inner_key);
    inner.update(message);
    let inner_digest = inner.finalize();

    let mut outer = Sha256::new();
    outer.update(outer_key);
    outer.update(inner_digest);

    let mut out = [0_u8; 32];
    out.copy_from_slice(&outer.finalize());
    out
}

/// Lower-case hex, which is how every webhook receiver spells a digest.
fn hex(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(char::from_digit((byte >> 4) as u32, 16).expect("nibble"));
        out.push(char::from_digit((byte & 0x0f) as u32, 16).expect("nibble"));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// RFC 4231, test case 1.
    #[test]
    fn rfc_4231_case_one() {
        let key = [0x0b_u8; 20];
        assert_eq!(
            sign_raw(&key, b"Hi There"),
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
        );
    }

    /// RFC 4231, test case 2: a key shorter than the block, a longer message.
    #[test]
    fn rfc_4231_case_two() {
        assert_eq!(
            sign_raw(b"Jefe", b"what do ya want for nothing?"),
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
        );
    }

    /// RFC 4231, test case 6: a key LONGER than the block, which is hashed first.
    #[test]
    fn rfc_4231_case_six() {
        let key = [0xaa_u8; 131];
        assert_eq!(
            sign_raw(
                &key,
                b"Test Using Larger Than Block-Size Key - Hash Key First"
            ),
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"
        );
    }

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

    #[test]
    fn hex_is_lower_case_and_two_characters_per_byte() {
        assert_eq!(hex(&[0x00, 0x0f, 0xff]), "000fff");
    }
}
