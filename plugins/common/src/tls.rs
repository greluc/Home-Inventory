// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! TLS in both directions: towards the core, and towards whatever the plugin reaches.
//!
//! A plugin is called by the core and by nothing else. The core pins this
//! plugin's certificate by fingerprint at registration (`REQ-SEC-056`); this is
//! the other half — a caller presents a certificate signed by the deployment's
//! own CA or it does not get a connection. There is no unauthenticated mode and
//! no flag to enable one: a mode that exists is a mode somebody runs.
//!
//! *The same shape as `blobstore/src/tls.rs`. It lived in `plugins/webhook/`
//! until `plugins/smtp/` needed the identical eighty lines, which is the moment
//! ADR-0072 named for moving it here: a shared crate costs less than the second
//! copy would. `blobstore/` keeps its own, because it is a core service rather
//! than a plugin and does not depend on this crate.*

use std::path::Path;
use std::sync::Arc;

use tokio::net::TcpStream;
use tokio_rustls::rustls::pki_types::ServerName;
use tokio_rustls::rustls::{ClientConfig, RootCertStore};
use tokio_rustls::TlsConnector;
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
    let encoded = crate::encoding::base64(der);
    let mut out = format!("-----BEGIN {label}-----\n");
    for line in encoded.as_bytes().chunks(64) {
        out.push_str(std::str::from_utf8(line).expect("base64 is ascii"));
        out.push('\n');
    }
    out.push_str(&format!("-----END {label}-----\n"));
    out
}

/// Wraps an already-opened socket in TLS, verifying the far end's certificate.
///
/// The other direction from [`server_config`]: an ordinary TLS client against a
/// server on the public internet, reached through the tunnel the egress proxy
/// opened. The roots come from `webpki-roots` as DATA, because a `scratch` image
/// has no system trust store to read and a plugin that skipped verification would
/// be handing this deployment's credentials to whatever answered.
///
/// # Arguments
///
/// * `socket` — the tunnel, already connected to `host`
/// * `host` — the name the certificate has to match
///
/// # Errors
///
/// A sentence for a log line: a host that is not a name TLS can verify, or a
/// handshake the far end refused.
pub async fn client_handshake(
    socket: TcpStream,
    host: &str,
) -> Result<tokio_rustls::client::TlsStream<TcpStream>, String> {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());

    let config = ClientConfig::builder_with_provider(Arc::new(
        tokio_rustls::rustls::crypto::ring::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .map_err(|failure| format!("TLS could not be configured: {failure}"))?
    .with_root_certificates(roots)
    .with_no_client_auth();

    let name = ServerName::try_from(host.to_string())
        .map_err(|_| format!("{host} is not a name TLS can verify"))?;
    TlsConnector::from(Arc::new(config))
        .connect(name, socket)
        .await
        .map_err(|failure| format!("the TLS handshake with {host} failed: {failure}"))
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
    fn pem_blocks_wrap_at_sixty_four_characters() {
        let pem = pem_block("CERTIFICATE", &[0_u8; 100]);
        assert!(pem
            .lines()
            .filter(|line| !line.starts_with("-----"))
            .all(|line| line.len() <= 64));
    }
}
