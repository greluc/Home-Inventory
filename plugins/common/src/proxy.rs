// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! The one route out of a plugin segment.
//!
//! A plugin's network is `internal: true` — no gateway, no route, nothing
//! ([ADR-0037](../../../docs/adr/0037-per-plugin-network-segments.md)). What it
//! reaches instead is the egress proxy, which sits on that same segment and
//! applies the allowlist compiled from this plugin's own manifest
//! ([ADR-0027](../../../docs/adr/0027-egress-enforcement.md)).
//!
//! So every outbound connection starts the same way, whatever protocol follows:
//! `CONNECT host:port`, a status line, and then the tunnel is the socket. HTTPS
//! runs TLS inside it; SMTP submission runs SMTP. That is why this is here
//! rather than in the plugin that happened to need it first.

use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

/// How much of the proxy's answer is read before giving up on it.
const MAX_HEAD: usize = 1024;

/// Opens a tunnel to `host:port` through the proxy.
///
/// # Arguments
///
/// * `proxy` — `host:port` of the egress proxy
/// * `host` — where the plugin wants to go
/// * `port` — on which port; the allowlist may be narrower than the host
/// * `timeout` — for the whole exchange, connection included
///
/// # Errors
///
/// A sentence for a log line. The proxy's own refusal is passed through, because
/// it is the useful case: it says an operator has to add a target rather than
/// that a server is down.
pub async fn connect(
    proxy: &str,
    host: &str,
    port: u16,
    timeout: Duration,
) -> Result<TcpStream, String> {
    tokio::time::timeout(timeout, open(proxy, host, port))
        .await
        .map_err(|_| {
            format!(
                "the egress proxy did not answer within {}s",
                timeout.as_secs()
            )
        })?
}

async fn open(proxy: &str, host: &str, port: u16) -> Result<TcpStream, String> {
    let mut socket = TcpStream::connect(proxy).await.map_err(|failure| {
        format!("the egress proxy at {proxy} could not be reached: {failure}")
    })?;

    let authority = format!("{host}:{port}");
    let request = format!("CONNECT {authority} HTTP/1.1\r\nHost: {authority}\r\n\r\n");
    socket
        .write_all(request.as_bytes())
        .await
        .map_err(|failure| format!("the egress proxy would not take the request: {failure}"))?;

    let mut head = Vec::with_capacity(256);
    let mut byte = [0_u8; 1];
    while head.len() < MAX_HEAD {
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
    if line.split_whitespace().nth(1).unwrap_or("") != "200" {
        return Err(format!(
            "the egress proxy refused the target: {line}. A target this plugin may reach is one \
             its manifest names (ADR-0027); adding one is the operator's decision."
        ));
    }
    Ok(socket)
}

/// The first line of a response, once a whole one has arrived.
pub fn status_line(bytes: &[u8]) -> Option<String> {
    let end = bytes.windows(2).position(|pair| pair == b"\r\n")?;
    Some(String::from_utf8_lossy(&bytes[..end]).trim().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_status_line_is_read_once_it_is_complete() {
        assert_eq!(status_line(b"HTTP/1.1 200 Connection established"), None);
        assert_eq!(
            status_line(b"HTTP/1.1 200 Connection established\r\n\r\n").as_deref(),
            Some("HTTP/1.1 200 Connection established")
        );
    }

    #[tokio::test]
    async fn a_proxy_that_is_not_there_is_a_sentence_rather_than_a_panic() {
        let failure = connect("127.0.0.1:1", "example.org", 443, Duration::from_secs(1))
            .await
            .unwrap_err();
        // Which failure it is depends on the platform -- a refused connection on
        // Linux, a timeout on Windows -- and the property under test is neither.
        // It is that a plugin gets a sentence naming the proxy rather than a
        // panic, because that sentence is what an operator reads.
        assert!(
            failure.contains("egress proxy"),
            "the failure should name the proxy: {failure}"
        );
    }
}
