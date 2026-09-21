// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! AWS Signature Version 4, which is what "S3-compatible" means in practice.
//!
//! Every request carries `Authorization: AWS4-HMAC-SHA256 …`, and the value is
//! four chained HMACs over a canonical form of the request. MinIO, Garage,
//! Backblaze B2's S3 endpoint and AWS itself all verify the same thing, which is
//! why this file and not an SDK: the AWS SDK for Rust brings a runtime, a
//! credential provider chain, a retry policy and a region resolver, none of
//! which a plugin with one route out through a CONNECT tunnel can use.
//!
//! # The payload hash is free here
//!
//! SigV4 signs the SHA-256 of the body, and a content-addressed store already
//! knows it: `BlobRef.sha256` **is** the hash of the object
//! ([ADR-0032](../../../docs/adr/0032-per-tenant-blob-addressing.md)). For a
//! single-request upload the header is therefore the address the core sent —
//! computed again from the bytes rather than trusted, because a store that
//! believed the caller's hash would be content-addressed in name only.
//!
//! # What is signed
//!
//! The minimum a request needs: `host`, `x-amz-date`, and for S3 also
//! `x-amz-content-sha256`. Signing more headers is allowed and buys nothing
//! here — nothing else on these requests is worth binding, and every extra
//! header is another thing two implementations can disagree about.

use homeinv_plugin_common::encoding::hex;
use homeinv_plugin_common::mac::{hmac_sha256, sha256};

/// The algorithm's own name, in the two places it appears.
const ALGORITHM: &str = "AWS4-HMAC-SHA256";

/// An access key and its secret.
///
/// Held as `String` rather than borrowed: they come either from a mounted file
/// or from the call envelope, and their lifetimes differ.
#[derive(Clone)]
pub struct Credentials {
    /// The access key id, which travels in the clear inside `Credential=`.
    pub access_key_id: String,
    /// The secret, which never travels at all.
    pub secret_access_key: String,
}

impl std::fmt::Debug for Credentials {
    /// Prints the key id and never the secret.
    ///
    /// A `{:?}` on a configuration struct is how a secret reaches a log line, and
    /// the derive would have put this one there the first time anybody debugged a
    /// signature (REQ-SEC-050).
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("Credentials")
            .field("access_key_id", &self.access_key_id)
            .field("secret_access_key", &"<redacted>")
            .finish()
    }
}

/// What is being signed.
pub struct Signable<'a> {
    /// `GET`, `PUT`, `HEAD`, `DELETE`, `POST`.
    pub method: &'a str,
    /// The path, already percent-encoded, beginning with `/`.
    pub path: &'a str,
    /// The canonical query string: sorted, encoded, without the `?`, or empty.
    pub query: &'a str,
    /// The `Host` header's value, including a non-default port.
    pub host: &'a str,
    /// The SHA-256 of the body as lower-case hex, or of nothing for a bodyless
    /// request.
    pub payload_sha256: &'a str,
    /// `(name, value)` of any further header to sign, names lower-case.
    pub extra: &'a [(String, String)],
}

/// The `Authorization` header for one request.
///
/// # Arguments
///
/// * `signable` — the request, canonicalised by the caller
/// * `credentials` — whose keys sign it
/// * `region` — the credential scope's region, `us-east-1` where a store has none
/// * `service` — `s3` for everything here; a parameter because the AWS test
///   vectors use `service` and a signer that hard-coded it could not be checked
///   against them
/// * `date` — `YYYYMMDD`, the scope's day
/// * `stamp` — `YYYYMMDDTHHMMSSZ`, the value of `x-amz-date`
///
/// # Returns
///
/// The complete header value.
pub fn authorization(
    signable: &Signable<'_>,
    credentials: &Credentials,
    region: &str,
    service: &str,
    date: &str,
    stamp: &str,
) -> String {
    let mut headers: Vec<(String, String)> = vec![
        ("host".to_string(), signable.host.to_string()),
        ("x-amz-date".to_string(), stamp.to_string()),
    ];
    headers.extend(signable.extra.iter().cloned());
    // Sorted by name, because the canonical form is defined that way and a
    // sender that sorted differently signs a different request than the server
    // verifies.
    headers.sort_by(|left, right| left.0.cmp(&right.0));

    let signed_headers = headers
        .iter()
        .map(|(name, _)| name.as_str())
        .collect::<Vec<_>>()
        .join(";");

    let mut canonical = String::new();
    canonical.push_str(signable.method);
    canonical.push('\n');
    canonical.push_str(signable.path);
    canonical.push('\n');
    canonical.push_str(signable.query);
    canonical.push('\n');
    for (name, value) in &headers {
        canonical.push_str(name);
        canonical.push(':');
        // Trimmed and with runs of spaces collapsed, which is the canonical
        // form's rule. None of our values contain either, and following the
        // rule costs one line.
        canonical.push_str(
            value
                .split_whitespace()
                .collect::<Vec<_>>()
                .join(" ")
                .as_str(),
        );
        canonical.push('\n');
    }
    canonical.push('\n');
    canonical.push_str(&signed_headers);
    canonical.push('\n');
    canonical.push_str(signable.payload_sha256);

    let scope = format!("{date}/{region}/{service}/aws4_request");
    let to_sign = format!(
        "{ALGORITHM}\n{stamp}\n{scope}\n{}",
        hex(&sha256(canonical.as_bytes()))
    );

    let signature = hex(&hmac_sha256(
        &signing_key(&credentials.secret_access_key, date, region, service),
        to_sign.as_bytes(),
    ));

    format!(
        "{ALGORITHM} Credential={}/{scope}, SignedHeaders={signed_headers}, Signature={signature}",
        credentials.access_key_id
    )
}

/// The key the string-to-sign is signed with.
///
/// Four HMACs, each keyed with the previous result: secret → date → region →
/// service → `aws4_request`. The chain is what makes a leaked signature useless
/// outside its day, its region and its service.
fn signing_key(secret: &str, date: &str, region: &str, service: &str) -> [u8; 32] {
    let initial = format!("AWS4{secret}");
    let by_date = hmac_sha256(initial.as_bytes(), date.as_bytes());
    let by_region = hmac_sha256(&by_date, region.as_bytes());
    let by_service = hmac_sha256(&by_region, service.as_bytes());
    hmac_sha256(&by_service, b"aws4_request")
}

/// Percent-encodes one path segment the way the canonical form wants it.
///
/// Unreserved characters stay, everything else becomes `%XX` in upper case. A
/// key is a blob address — hexadecimal and slashes — so in this plugin nothing
/// is ever encoded; a tenant's own prefix could contain anything, and that is
/// the case this exists for.
pub fn encode_segment(segment: &str) -> String {
    let mut out = String::with_capacity(segment.len());
    for byte in segment.as_bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => {
                out.push(*byte as char)
            }
            other => out.push_str(&format!("%{other:02X}")),
        }
    }
    out
}

/// Percent-encodes a whole key, keeping `/` as the separator it is.
pub fn encode_key(key: &str) -> String {
    key.split('/')
        .map(encode_segment)
        .collect::<Vec<_>>()
        .join("/")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn example() -> Credentials {
        // The credentials of the published AWS test suite. Not a secret and not
        // usable: they exist so that every implementation can check itself
        // against the same arithmetic.
        Credentials {
            access_key_id: "AKIDEXAMPLE".to_string(),
            secret_access_key: "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY".to_string(),
        }
    }

    /// `get-vanilla` from the AWS Signature Version 4 test suite.
    ///
    /// The one independent check available to a signer that cannot call AWS: the
    /// request, the credentials, the date and the expected header are all
    /// published. An implementation that reproduces it byte for byte is the
    /// algorithm; one that does not is wrong in a way no amount of reading
    /// catches.
    #[test]
    fn the_published_vector() {
        let signable = Signable {
            method: "GET",
            path: "/",
            query: "",
            host: "example.amazonaws.com",
            payload_sha256: &hex(&sha256(b"")),
            extra: &[],
        };
        assert_eq!(
            authorization(
                &signable,
                &example(),
                "us-east-1",
                "service",
                "20150830",
                "20150830T123600Z"
            ),
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, \
             SignedHeaders=host;x-amz-date, \
             Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31"
        );
    }

    #[test]
    fn a_different_day_is_a_different_signature() {
        let signable = Signable {
            method: "GET",
            path: "/",
            query: "",
            host: "example.amazonaws.com",
            payload_sha256: &hex(&sha256(b"")),
            extra: &[],
        };
        let one = authorization(
            &signable,
            &example(),
            "us-east-1",
            "service",
            "20150830",
            "20150830T123600Z",
        );
        let two = authorization(
            &signable,
            &example(),
            "us-east-1",
            "service",
            "20150831",
            "20150831T123600Z",
        );
        assert_ne!(one, two);
    }

    #[test]
    fn extra_headers_are_signed_and_named_in_order() {
        let extra = [(
            "x-amz-content-sha256".to_string(),
            hex(&sha256(b"body")).to_string(),
        )];
        let signable = Signable {
            method: "PUT",
            path: "/bucket/sha256/tenant/ab/cd/abcd",
            query: "",
            host: "objects.example.org",
            payload_sha256: &hex(&sha256(b"body")),
            extra: &extra,
        };
        let header = authorization(
            &signable,
            &example(),
            "eu-central-1",
            "s3",
            "20231114",
            "20231114T221320Z",
        );
        // Alphabetical, which is the canonical order rather than the order they
        // were added in.
        assert!(header.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"));
        assert!(header.contains("Credential=AKIDEXAMPLE/20231114/eu-central-1/s3/aws4_request"));
    }

    #[test]
    fn a_key_keeps_its_slashes_and_encodes_the_rest() {
        assert_eq!(
            encode_key("sha256/0191e2/ab/cd/abcd"),
            "sha256/0191e2/ab/cd/abcd"
        );
        assert_eq!(encode_key("holiday photos/a b"), "holiday%20photos/a%20b");
        assert_eq!(encode_segment("a+b=c"), "a%2Bb%3Dc");
    }
}
