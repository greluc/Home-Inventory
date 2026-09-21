// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! SHA-256 and HMAC-SHA256, which two plugins now need identically.
//!
//! `plugins/webhook/` signs a delivery with it (`REQ-API-010`) and
//! `plugins/blobstore-s3/` signs every request with it four times over — AWS
//! Signature Version 4 is a chain of HMACs and nothing more. That is the second
//! user, which is the moment
//! [ADR-0072](../../../docs/adr/0072-first-party-plugins-live-here.md) names for
//! moving a thing here.
//!
//! # Why this is thirty lines rather than a dependency
//!
//! RFC 2104 is `H((K ^ opad) || H((K ^ ipad) || message))` and nothing else. The
//! `hmac` crate would pin a `digest` version that has to match the one `sha2`
//! brings, a coupling that breaks on every digest release for no gain — and
//! these containers are budgeted the way the two core Rust services are. The RFC
//! 4231 vectors are below; an implementation that passes them is the algorithm.

use sha2::{Digest, Sha256};

/// How many bytes SHA-256 consumes at a time. The key is padded to it.
const BLOCK: usize = 64;

/// The SHA-256 of some bytes.
///
/// # Arguments
///
/// * `bytes` — what to hash
///
/// # Returns
///
/// The digest, raw. [`crate::encoding::hex`] turns it into what a header carries.
pub fn sha256(bytes: &[u8]) -> [u8; 32] {
    let mut out = [0_u8; 32];
    out.copy_from_slice(&Sha256::digest(bytes));
    out
}

/// HMAC-SHA256, RFC 2104.
///
/// # Arguments
///
/// * `key` — any length; longer than the block is hashed first, shorter is padded
/// * `message` — what is authenticated
///
/// # Returns
///
/// The tag, raw.
pub fn hmac_sha256(key: &[u8], message: &[u8]) -> [u8; 32] {
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

/// Hashes a stream of pieces without holding them.
///
/// S3 needs the digest of an object that arrives in chunks and is never in
/// memory whole: a part is hashed for its own signature while the whole object
/// is hashed across every part, and the result is compared with the address the
/// core declared before the upload is completed.
#[derive(Default)]
pub struct Running(Sha256);

impl Running {
    /// A fresh digest.
    pub fn new() -> Self {
        Self(Sha256::new())
    }

    /// Adds the next piece.
    ///
    /// # Arguments
    ///
    /// * `bytes` — the piece, in order
    pub fn update(&mut self, bytes: &[u8]) {
        self.0.update(bytes);
    }

    /// The digest of everything added, consuming the state.
    pub fn finish(self) -> [u8; 32] {
        let mut out = [0_u8; 32];
        out.copy_from_slice(&self.0.finalize());
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::encoding::hex;

    fn tag(key: &[u8], message: &[u8]) -> String {
        hex(&hmac_sha256(key, message))
    }

    /// RFC 4231, test case 1.
    #[test]
    fn rfc_4231_case_one() {
        let key = [0x0b_u8; 20];
        assert_eq!(
            tag(&key, b"Hi There"),
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
        );
    }

    /// RFC 4231, test case 2: a key shorter than the block, a longer message.
    #[test]
    fn rfc_4231_case_two() {
        assert_eq!(
            tag(b"Jefe", b"what do ya want for nothing?"),
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
        );
    }

    /// RFC 4231, test case 6: a key LONGER than the block, which is hashed first.
    #[test]
    fn rfc_4231_case_six() {
        let key = [0xaa_u8; 131];
        assert_eq!(
            tag(
                &key,
                b"Test Using Larger Than Block-Size Key - Hash Key First"
            ),
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"
        );
    }

    /// The empty digest, which S3 sends for every request that has no body.
    #[test]
    fn the_digest_of_nothing() {
        assert_eq!(
            hex(&sha256(b"")),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
    }

    #[test]
    fn a_running_digest_equals_the_whole() {
        let mut running = Running::new();
        running.update(b"the quick brown ");
        running.update(b"fox");
        assert_eq!(running.finish(), sha256(b"the quick brown fox"));
    }
}
