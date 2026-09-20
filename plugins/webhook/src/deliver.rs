// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! One POST, through the one route out.
//!
//! `api` and `worker` have no route out of the deployment at all
//! ([ADR-0026](../../../docs/adr/0026-core-outbound-via-plugins.md)); a plugin
//! has one, and it is the egress proxy, which applies this plugin's own manifest
//! allowlist ([ADR-0027](../../../docs/adr/0027-egress-enforcement.md)). So the
//! conversation here is: `CONNECT` to the proxy, TLS to the target inside that
//! tunnel, one HTTP/1.1 request, one response.
//!
//! # Why this is written out rather than delegated to a client library
//!
//! The same reason `egress-proxy/` writes the other end of it: two request
//! shapes and a status line are forty lines, against a dependency tree that
//! would be the largest thing in the image. What this does not do is as
//! important as what it does — **no redirects are followed**. A redirect is a
//! new target, chosen by the far side, and following one would send a signed
//! payload somewhere the allowlist never approved.

use std::sync::Arc;
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio_rustls::rustls::pki_types::ServerName;
use tokio_rustls::rustls::{ClientConfig, RootCertStore};
use tokio_rustls::TlsConnector;

use crate::target::Target;

/// How much of a response is read before the rest is dropped.
///
/// A receiver's answer is a status and perhaps a sentence. Anything beyond this
/// is not information this system acts on, and reading it unbounded would make
/// the far side decide how much memory a delivery costs.
const MAX_RESPONSE: usize = 8 * 1024;

/// What the far side said.
pub struct Delivered {
    /// The HTTP status.
    pub status: u16,
    /// The status line as it arrived, for the delivery log. Never a body.
    pub detail: String,
}

/// Sends one request and reads one answer.
///
/// # Arguments
///
/// * `proxy` — `host:port` of the egress proxy, or `None` to dial directly,
///   which only a test does: in a deployment a plugin segment has no route out
/// * `target` — where to send it, already checked by [`crate::target::parse`]
/// * `headers` — extra header lines, already filtered
/// * `body` — the bytes that were signed
/// * `timeout` — the whole exchange, connection included
///
/// # Errors
///
/// A sentence for the delivery log. It never carries the body and never the
/// target's path or query, because a log line is read by people who did not
/// configure the target.
pub async fn post(
    proxy: Option<&str>,
    target: &Target,
    headers: &[(String, String)],
    body: &[u8],
    timeout: Duration,
) -> Result<Delivered, String> {
    tokio::time::timeout(timeout, exchange(proxy, target, headers, body))
        .await
        .map_err(|_| {
            format!(
                "the target did not answer within {} seconds",
                timeout.as_secs()
            )
        })?
}

async fn exchange(
    proxy: Option<&str>,
    target: &Target,
    headers: &[(String, String)],
    body: &[u8],
) -> Result<Delivered, String> {
    let socket = match proxy {
        Some(address) => connect_through(address, target).await?,
        None => TcpStream::connect((target.host.as_str(), target.port))
            .await
            .map_err(|failure| format!("the target could not be reached: {failure}"))?,
    };
    socket
        .set_nodelay(true)
        .map_err(|failure| format!("the connection could not be configured: {failure}"))?;

    let name = ServerName::try_from(target.host.clone())
        .map_err(|_| "the target's host is not a name TLS can verify".to_string())?;
    let mut stream = connector()?
        .connect(name, socket)
        .await
        .map_err(|failure| format!("the target's TLS handshake failed: {failure}"))?;

    let mut request = format!(
        "POST {} HTTP/1.1\r\nHost: {}\r\nContent-Type: application/json\r\n\
         Content-Length: {}\r\nConnection: close\r\n",
        target.path,
        host_header(target),
        body.len()
    );
    for (name, value) in headers {
        request.push_str(name);
        request.push_str(": ");
        request.push_str(value);
        request.push_str("\r\n");
    }
    request.push_str("\r\n");

    stream
        .write_all(request.as_bytes())
        .await
        .map_err(|failure| format!("the request could not be sent: {failure}"))?;
    stream
        .write_all(body)
        .await
        .map_err(|failure| format!("the request body could not be sent: {failure}"))?;
    stream
        .flush()
        .await
        .map_err(|failure| format!("the request could not be flushed: {failure}"))?;

    let mut answer = Vec::with_capacity(1024);
    let mut chunk = [0_u8; 1024];
    loop {
        let read = stream
            .read(&mut chunk)
            .await
            .map_err(|failure| format!("the answer could not be read: {failure}"))?;
        if read == 0 || answer.len() >= MAX_RESPONSE {
            break;
        }
        answer.extend_from_slice(&chunk[..read]);
        if status_line(&answer).is_some() && answer.len() > 512 {
            // The status is all this acts on. Everything after it is read only
            // so that the far side sees a complete exchange rather than a reset.
            break;
        }
    }

    let line = status_line(&answer).ok_or_else(|| "the target answered nothing".to_string())?;
    let status = line
        .split_whitespace()
        .nth(1)
        .and_then(|code| code.parse::<u16>().ok())
        .ok_or_else(|| format!("the target answered '{line}', which is not an HTTP status"))?;
    Ok(Delivered {
        status,
        detail: line,
    })
}

/// Opens a tunnel through the egress proxy.
///
/// The proxy answers `200` when the target is on this plugin's allowlist and a
/// `4xx` when it is not — and the refusal is the useful half, because it is the
/// one that says an operator has to add a host rather than that a receiver is
/// down.
async fn connect_through(proxy: &str, target: &Target) -> Result<TcpStream, String> {
    let mut socket = TcpStream::connect(proxy).await.map_err(|failure| {
        format!("the egress proxy at {proxy} could not be reached: {failure}")
    })?;

    let authority = format!("{}:{}", target.host, target.port);
    let request = format!("CONNECT {authority} HTTP/1.1\r\nHost: {authority}\r\n\r\n");
    socket
        .write_all(request.as_bytes())
        .await
        .map_err(|failure| format!("the egress proxy would not take the request: {failure}"))?;

    let mut head = Vec::with_capacity(256);
    let mut byte = [0_u8; 1];
    while head.len() < 1024 {
        let read = socket
            .read(&mut byte)
            .await
            .map_err(|failure| format!("the egress proxy did not answer: {failure}"))?;
        if read == 0 {
            break;
        }
        head.push(byte[0]);
        if head.ends_with(b"\r\n\r\n") {
            break;
        }
    }

    let line = status_line(&head)
        .ok_or_else(|| "the egress proxy answered nothing to CONNECT".to_string())?;
    let status = line.split_whitespace().nth(1).unwrap_or("");
    if status != "200" {
        return Err(format!(
            "the egress proxy refused the target: {line}. A host this plugin may reach is one its \
             manifest names (ADR-0027); adding one is the operator's decision."
        ));
    }
    Ok(socket)
}

/// The TLS client configuration, with the public roots compiled in.
///
/// A `scratch` image has no system trust store, which is why the roots are a
/// crate. The provider is named rather than taken from a process-wide default:
/// a default that is installed somewhere else is a default that can change
/// somewhere else.
fn connector() -> Result<TlsConnector, String> {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());

    let config = ClientConfig::builder_with_provider(Arc::new(
        tokio_rustls::rustls::crypto::ring::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .map_err(|failure| format!("TLS could not be configured: {failure}"))?
    .with_root_certificates(roots)
    .with_no_client_auth();

    Ok(TlsConnector::from(Arc::new(config)))
}

/// `Host:` as it goes on the wire — without the port when it is the default.
fn host_header(target: &Target) -> String {
    if target.port == 443 {
        target.host.clone()
    } else {
        format!("{}:{}", target.host, target.port)
    }
}

/// The first line of a response, if a whole one has arrived.
fn status_line(bytes: &[u8]) -> Option<String> {
    let end = bytes.windows(2).position(|pair| pair == b"\r\n")?;
    Some(String::from_utf8_lossy(&bytes[..end]).trim().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_host_header_omits_the_default_port() {
        let target = Target {
            host: "hooks.example.org".into(),
            port: 443,
            path: "/in".into(),
        };
        assert_eq!(host_header(&target), "hooks.example.org");
    }

    #[test]
    fn the_host_header_carries_any_other_port() {
        let target = Target {
            host: "hooks.example.org".into(),
            port: 8443,
            path: "/in".into(),
        };
        assert_eq!(host_header(&target), "hooks.example.org:8443");
    }

    #[test]
    fn a_status_line_is_read_once_it_is_complete() {
        assert_eq!(status_line(b"HTTP/1.1 200 OK"), None);
        assert_eq!(
            status_line(b"HTTP/1.1 204 No Content\r\nDate: now\r\n\r\n").as_deref(),
            Some("HTTP/1.1 204 No Content")
        );
    }
}
