// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Mutual TLS, from the one file the runtime mounts.
//!
//! ADR-0043 gave this service every tenant's media and no authentication at all;
//! its access control was that its port sat on `internal`. ADR-0044 corrected
//! that on the general principle that **reachability is not authorisation** — a
//! segment that several services share is not a trust boundary, and the
//! in-deployment store holds more than any of them.
//!
//! So a caller presents a certificate signed by the deployment's own CA or it
//! does not get a connection. There is no unauthenticated mode and no flag to
//! enable one: a mode that exists is a mode somebody runs.

use std::path::Path;

use tonic::transport::ServerTlsConfig;
use tonic::transport::{Certificate, Identity};

/// Builds the server's TLS configuration from its mounted identity.
///
/// The file is a PEM bundle — the private key, this service's certificate, and
/// the CA that signed it, in that order. One file rather than three because a
/// runtime secret is one file, and three would mean three mounts that can get
/// out of step with each other.
///
/// # Errors
///
/// Returns an error when the file is missing, unreadable, or does not contain
/// all three parts. Startup then fails, which is the intended behaviour: a store
/// that came up without mTLS would accept anything on the segment, and nothing
/// about its behaviour would say so.
pub fn server_config(identity_path: &Path) -> Result<ServerTlsConfig, Box<dyn std::error::Error>> {
    let bundle = std::fs::read(identity_path).map_err(|failure| {
        format!(
            "the mTLS identity at {} could not be read: {failure}. It is required and has no \
             default — a store without it accepts every caller on the segment (ADR-0044).",
            identity_path.display()
        )
    })?;

    // The LABEL travels with the bytes. A PKCS#1 key re-emitted under the PKCS#8
    // label is a file every parser rejects, and the error it produces — "failed
    // to parse private key" — says nothing about which of the three encodings was
    // expected. `openssl genpkey` writes PKCS#8 and BouncyCastle writes PKCS#1
    // for the same RSA key, so both spellings reach this code in practice.
    let mut keys: Vec<(&'static str, Vec<u8>)> = Vec::new();
    let mut certificates = Vec::new();
    for item in rustls_pemfile::read_all(&mut bundle.as_slice()) {
        match item? {
            rustls_pemfile::Item::Pkcs8Key(key) => {
                keys.push(("PRIVATE KEY", key.secret_pkcs8_der().to_vec()))
            }
            rustls_pemfile::Item::Pkcs1Key(key) => {
                keys.push(("RSA PRIVATE KEY", key.secret_pkcs1_der().to_vec()))
            }
            rustls_pemfile::Item::Sec1Key(key) => {
                keys.push(("EC PRIVATE KEY", key.secret_sec1_der().to_vec()))
            }
            rustls_pemfile::Item::X509Certificate(certificate) => {
                certificates.push(certificate.to_vec())
            }
            _ => {}
        }
    }

    if keys.len() != 1 {
        return Err(format!(
            "the mTLS identity at {} contains {} private keys; exactly one is required",
            identity_path.display(),
            keys.len()
        )
        .into());
    }
    if certificates.len() < 2 {
        return Err(format!(
            "the mTLS identity at {} contains {} certificates; it must carry this service's own \
             certificate AND the CA that signs its callers, or no client could be verified",
            identity_path.display(),
            certificates.len()
        )
        .into());
    }

    // The last certificate in the bundle is the CA. It is what a CLIENT is
    // verified against, and it is deliberately the same CA that signed this
    // service — one deployment, one authority, created by `deploy/setup.sh` and
    // never leaving the host.
    let authority = certificates
        .last()
        .expect("checked above")
        .clone();
    let chain = pem_block("CERTIFICATE", &certificates[0]);
    let (key_label, key_der) = &keys[0];
    let key = pem_block(key_label, key_der);

    Ok(ServerTlsConfig::new()
        .identity(Identity::from_pem(chain, key))
        // Not optional. `client_auth_optional` would let an unauthenticated
        // caller through with no indication in the logs that it happened.
        .client_ca_root(Certificate::from_pem(pem_block("CERTIFICATE", &authority))))
}

/// Re-encodes a DER block as PEM, because `tonic` takes PEM and `rustls_pemfile`
/// hands back DER.
fn pem_block(label: &str, der: &[u8]) -> String {
    let encoded = base64_encode(der);
    let mut out = format!("-----BEGIN {label}-----\n");
    for line in encoded.as_bytes().chunks(64) {
        out.push_str(std::str::from_utf8(line).expect("base64 is ascii"));
        out.push('\n');
    }
    out.push_str(&format!("-----END {label}-----\n"));
    out
}

/// Standard base64, without a dependency for sixty lines of table lookup.
fn base64_encode(input: &[u8]) -> String {
    const ALPHABET: &[u8; 64] =
        b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(input.len().div_ceil(3) * 4);
    for chunk in input.chunks(3) {
        let b = [
            chunk[0],
            *chunk.get(1).unwrap_or(&0),
            *chunk.get(2).unwrap_or(&0),
        ];
        let triple = ((b[0] as u32) << 16) | ((b[1] as u32) << 8) | b[2] as u32;
        out.push(ALPHABET[(triple >> 18 & 0x3F) as usize] as char);
        out.push(ALPHABET[(triple >> 12 & 0x3F) as usize] as char);
        out.push(if chunk.len() > 1 {
            ALPHABET[(triple >> 6 & 0x3F) as usize] as char
        } else {
            '='
        });
        out.push(if chunk.len() > 2 {
            ALPHABET[(triple & 0x3F) as usize] as char
        } else {
            '='
        });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base64_matches_the_standard_alphabet() {
        assert_eq!(base64_encode(b""), "");
        assert_eq!(base64_encode(b"f"), "Zg==");
        assert_eq!(base64_encode(b"fo"), "Zm8=");
        assert_eq!(base64_encode(b"foo"), "Zm9v");
        assert_eq!(base64_encode(b"foob"), "Zm9vYg==");
        assert_eq!(base64_encode(b"fooba"), "Zm9vYmE=");
        assert_eq!(base64_encode(b"foobar"), "Zm9vYmFy");
    }

    #[test]
    fn a_missing_identity_is_a_startup_failure() {
        // Not a warning and not a fallback. A store that came up without mTLS
        // would accept anything on the segment, and nothing about its behaviour
        // would say so.
        let failure = server_config(Path::new("/nonexistent/mtls-blobstore")).unwrap_err();
        assert!(failure.to_string().contains("could not be read"));
    }

    #[test]
    fn pem_blocks_wrap_at_sixty_four_characters() {
        let der = vec![0_u8; 100];
        let pem = pem_block("CERTIFICATE", &der);
        let body: Vec<&str> = pem
            .lines()
            .filter(|line| !line.starts_with("-----"))
            .collect();
        assert!(body.iter().all(|line| line.len() <= 64));
    }
}
