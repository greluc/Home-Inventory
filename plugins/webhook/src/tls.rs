// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Mutual TLS towards the core, from the one file the runtime mounts.
//!
//! A plugin is called by the core and by nothing else. The core pins this
//! plugin's certificate by fingerprint at registration (`REQ-SEC-056`); this is
//! the other half — a caller presents a certificate signed by the deployment's
//! own CA or it does not get a connection. There is no unauthenticated mode and
//! no flag to enable one: a mode that exists is a mode somebody runs.
//!
//! *This is the same shape as `blobstore/src/tls.rs`, and deliberately a copy
//! rather than a shared crate today: there is one Rust plugin. The second one
//! moves it into a crate the plugins share, which is the point at which a shared
//! crate costs less than the duplication does ([ADR-0072](../../../docs/adr/0072-first-party-plugins-live-here.md)).*

use std::path::Path;

use tonic::transport::{Certificate, Identity, ServerTlsConfig};

use rustls_pki_types::pem::PemObject;
use rustls_pki_types::{CertificateDer, PrivateKeyDer};

/// Builds the server's TLS configuration from its mounted identity.
///
/// The file is a PEM bundle — the private key, this plugin's certificate, and
/// the CA that signed it, in that order. One file rather than three, because a
/// runtime secret is one file and three mounts can get out of step.
///
/// # Errors
///
/// Returns an error when the file is missing, unreadable or incomplete. Startup
/// then fails, which is the intended behaviour: a plugin that came up without
/// mTLS would take calls from anything on its segment, and nothing about its
/// behaviour would say so.
pub fn server_config(identity_path: &Path) -> Result<ServerTlsConfig, Box<dyn std::error::Error>> {
    let bundle = std::fs::read(identity_path).map_err(|failure| {
        format!(
            "the mTLS identity at {} could not be read: {failure}. It is required and has no \
             default.",
            identity_path.display()
        )
    })?;

    let certificates: Vec<Vec<u8>> = CertificateDer::pem_slice_iter(&bundle)
        .map(|entry| entry.map(|certificate| certificate.to_vec()))
        .collect::<Result<_, _>>()
        .map_err(|failure| format!("the certificates could not be parsed: {failure}"))?;

    // The variant carries the label: the same RSA key is PKCS#1 from
    // BouncyCastle and PKCS#8 from `openssl genpkey`, and re-emitting one under
    // the other's header produces a file every parser rejects.
    let keys: Vec<(&'static str, Vec<u8>)> = PrivateKeyDer::pem_slice_iter(&bundle)
        .map(|entry| {
            entry.map(|key| match key {
                PrivateKeyDer::Pkcs8(der) => ("PRIVATE KEY", der.secret_pkcs8_der().to_vec()),
                PrivateKeyDer::Pkcs1(der) => ("RSA PRIVATE KEY", der.secret_pkcs1_der().to_vec()),
                PrivateKeyDer::Sec1(der) => ("EC PRIVATE KEY", der.secret_sec1_der().to_vec()),
                other => ("PRIVATE KEY", other.secret_der().to_vec()),
            })
        })
        .collect::<Result<_, _>>()
        .map_err(|failure| format!("the private key could not be parsed: {failure}"))?;

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
            "the mTLS identity at {} contains {} certificates; it must carry this plugin's own \
             certificate AND the CA that signs its callers, or the core could not be verified",
            identity_path.display(),
            certificates.len()
        )
        .into());
    }

    let authority = certificates.last().expect("checked above").clone();
    let chain = pem_block("CERTIFICATE", &certificates[0]);
    let (key_label, key_der) = &keys[0];

    Ok(ServerTlsConfig::new()
        .identity(Identity::from_pem(chain, pem_block(key_label, key_der)))
        // Not optional. `client_auth_optional` would let an unauthenticated
        // caller through with no indication in the logs that it happened.
        .client_ca_root(Certificate::from_pem(pem_block("CERTIFICATE", &authority))))
}

/// Re-encodes a DER block as PEM, because `tonic` takes PEM and the parser hands
/// back DER.
fn pem_block(label: &str, der: &[u8]) -> String {
    let encoded = base64(der);
    let mut out = format!("-----BEGIN {label}-----\n");
    for line in encoded.as_bytes().chunks(64) {
        out.push_str(std::str::from_utf8(line).expect("base64 is ascii"));
        out.push('\n');
    }
    out.push_str(&format!("-----END {label}-----\n"));
    out
}

/// Standard base64, without a dependency for a table lookup.
fn base64(input: &[u8]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
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
    fn a_missing_identity_is_a_startup_failure() {
        let failure = server_config(Path::new("/nonexistent/mtls-plugin-webhook")).unwrap_err();
        assert!(failure.to_string().contains("could not be read"));
    }

    #[test]
    fn base64_matches_the_standard_alphabet() {
        assert_eq!(base64(b""), "");
        assert_eq!(base64(b"f"), "Zg==");
        assert_eq!(base64(b"foobar"), "Zm9vYmFy");
    }
}
