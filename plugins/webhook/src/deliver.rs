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
//! # Where the exchange itself lives
//!
//! In `homeinv_plugin_common::http`, since `plugins/blobstore-s3/` and
//! `plugins/blobstore-nextcloud/` need the identical tunnel, handshake, request
//! writer and framed body reader
//! ([ADR-0072](../../../docs/adr/0072-first-party-plugins-live-here.md)). What
//! stays here is what is specific to a webhook: one `POST` of JSON, a bounded
//! read of whatever comes back, and a status line for the delivery log.
//!
//! **No redirect is followed**, which the shared transport guarantees rather
//! than promises: it has no redirect handling at all. A redirect is a new target
//! chosen by the far side, and following one would send a signed payload
//! somewhere the allowlist never approved.

use std::time::Duration;

use homeinv_plugin_common::http::{self, Body, Endpoint, Request};

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
    let host_header = http::host_header(&target.host, target.port);
    let mut all = vec![("Content-Type".to_string(), "application/json".to_string())];
    all.extend_from_slice(headers);

    let mut answer = http::send(
        &Endpoint {
            proxy,
            host: &target.host,
            port: target.port,
            timeout,
        },
        &Request {
            method: "POST",
            target: &target.path,
            host_header: &host_header,
            headers: &all,
            body: Body::Bytes(body),
        },
    )
    .await?;

    // Read and discard, bounded: the status is all this acts on, and reading
    // some of the body is what lets the far side see a complete exchange rather
    // than a reset.
    let _ = answer.read_all(MAX_RESPONSE).await;

    Ok(Delivered {
        status: answer.status,
        detail: answer.status_line,
    })
}

#[cfg(test)]
mod tests {
    use homeinv_plugin_common::http::host_header;
    use homeinv_plugin_common::proxy::status_line;

    #[test]
    fn the_host_header_omits_the_default_port() {
        assert_eq!(host_header("hooks.example.org", 443), "hooks.example.org");
    }

    #[test]
    fn the_host_header_carries_any_other_port() {
        assert_eq!(
            host_header("hooks.example.org", 8443),
            "hooks.example.org:8443"
        );
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
