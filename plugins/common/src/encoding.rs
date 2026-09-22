// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Base64 and hex, without a dependency for a table lookup.
//!
//! Both are needed twice over — a PEM block, an SMTP `AUTH PLAIN`, a MIME body,
//! a signature header — and both are thirty lines. The alternative is two crates
//! in an image budgeted at 32 MB, for arithmetic that has not changed since 1987.

/// The standard alphabet, RFC 4648 §4.
const STANDARD: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

/// Encodes bytes as base64 with padding.
///
/// @param input the bytes
/// @return the encoded text
pub fn base64(input: &[u8]) -> String {
    let mut out = String::with_capacity(input.len().div_ceil(3) * 4);
    for chunk in input.chunks(3) {
        let bytes = [
            chunk[0],
            *chunk.get(1).unwrap_or(&0),
            *chunk.get(2).unwrap_or(&0),
        ];
        let triple = ((bytes[0] as u32) << 16) | ((bytes[1] as u32) << 8) | bytes[2] as u32;
        out.push(STANDARD[(triple >> 18 & 0x3F) as usize] as char);
        out.push(STANDARD[(triple >> 12 & 0x3F) as usize] as char);
        out.push(if chunk.len() > 1 {
            STANDARD[(triple >> 6 & 0x3F) as usize] as char
        } else {
            '='
        });
        out.push(if chunk.len() > 2 {
            STANDARD[(triple & 0x3F) as usize] as char
        } else {
            '='
        });
    }
    out
}

/// Encodes bytes as base64 wrapped at a line length, as MIME requires.
///
/// RFC 2045 puts the limit at 76 characters. A body encoded as one long line is
/// accepted by most servers and mangled by some, and the ones that mangle it are
/// the ones an operator cannot change.
///
/// @param input the bytes
/// @param width how many characters per line
/// @return the encoded text, with `\r\n` between lines as a mail body needs
pub fn base64_wrapped(input: &[u8], width: usize) -> String {
    let encoded = base64(input);
    let mut out = String::with_capacity(encoded.len() + encoded.len() / width * 2);
    for (index, chunk) in encoded.as_bytes().chunks(width).enumerate() {
        if index > 0 {
            out.push_str("\r\n");
        }
        out.push_str(std::str::from_utf8(chunk).expect("base64 is ascii"));
    }
    out
}

/// Lower-case hex.
///
/// @param bytes the bytes
/// @return two characters per byte
pub fn hex(bytes: &[u8]) -> String {
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

    /// RFC 4648 §10, every padding case.
    #[test]
    fn base64_matches_the_standard_vectors() {
        assert_eq!(base64(b""), "");
        assert_eq!(base64(b"f"), "Zg==");
        assert_eq!(base64(b"fo"), "Zm8=");
        assert_eq!(base64(b"foo"), "Zm9v");
        assert_eq!(base64(b"foob"), "Zm9vYg==");
        assert_eq!(base64(b"fooba"), "Zm9vYmE=");
        assert_eq!(base64(b"foobar"), "Zm9vYmFy");
    }

    #[test]
    fn a_wrapped_body_breaks_at_the_width_and_nowhere_else() {
        let wrapped = base64_wrapped(&[0_u8; 120], 76);
        let lines: Vec<&str> = wrapped.split("\r\n").collect();
        assert_eq!(lines.len(), 3);
        assert!(lines.iter().all(|line| line.len() <= 76));
        assert_eq!(wrapped.replace("\r\n", ""), base64(&[0_u8; 120]));
    }

    #[test]
    fn hex_is_lower_case_and_two_characters_per_byte() {
        assert_eq!(hex(&[0x00, 0x0f, 0xff]), "000fff");
    }
}
