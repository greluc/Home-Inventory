// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! What a blob's address is, for the stores that implement the `BlobStore` port.
//!
//! `plugins/blobstore-s3/` and `plugins/blobstore-nextcloud/` both have to
//! answer the same question before they touch anything — *is this a content
//! address?* — and two implementations of that question are two chances to
//! disagree about it. The address is the SHA-256 of the content in lower-case
//! hexadecimal ([ADR-0032]), and a store that accepted anything else would be
//! content-addressed in name only.
//!
//! [ADR-0032]: ../../../docs/adr/0032-per-tenant-blob-addressing.md

/// How long a content address is: SHA-256 as hexadecimal.
pub const ADDRESS_LENGTH: usize = 64;

/// Whether a string is a content address as this contract spells one.
///
/// Lower-case deliberately: the same bytes written in upper case would be a
/// second name for one object, and a store keyed on the text would then hold
/// two.
///
/// # Arguments
///
/// * `value` — what the caller sent as `BlobRef.sha256`
pub fn is_content_address(value: &str) -> bool {
    value.len() == ADDRESS_LENGTH
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_content_address_is_sixty_four_lower_case_hex_digits() {
        assert!(is_content_address(&"0a".repeat(32)));
        assert!(!is_content_address(&"0A".repeat(32)));
        assert!(!is_content_address(&"0g".repeat(32)));
        assert!(!is_content_address(&"ab".repeat(31)));
        assert!(!is_content_address(""));
    }
}
