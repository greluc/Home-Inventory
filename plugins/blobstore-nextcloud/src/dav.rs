// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! What a WebDAV request is, on top of the shared HTTP transport.
//!
//! The exchange itself — the `CONNECT` tunnel, the TLS handshake inside it, the
//! request writer and the framed body reader — is
//! `homeinv_plugin_common::http`
//! ([ADR-0072](../../../docs/adr/0072-first-party-plugins-live-here.md)). What
//! is WebDAV and therefore lives here: the methods HTTP does not have, the
//! `Authorization` every request carries, and what an error document says.
//!
//! # WebDAV creates no parent
//!
//! A `PUT` into a collection that does not exist answers `409 Conflict`, and no
//! header changes that. So a `409` is answered by creating the chain with
//! `MKCOL` and trying once more — after the first blob of a tenant every
//! collection already exists, which is why the chain is walked on failure rather
//! than before every upload.

use std::time::Duration;

use homeinv_plugin_common::http::{self, Body, Endpoint, Request, Response};

use crate::target::Target;

/// How much of an error body is read before it is truncated. A Nextcloud error
/// document is a few hundred bytes; anything larger is not one.
pub const MAX_ERROR_BODY: usize = 8 * 1024;

/// Makes one authenticated request and reads its head.
///
/// # Arguments
///
/// * `proxy_address` — `host:port` of the egress proxy, the one route out
/// * `target` — which instance and as whom
/// * `timeout` — for the tunnel, the request and the response head
/// * `method` — `GET`, `PUT`, `HEAD`, `DELETE`, `MKCOL`, `MOVE`
/// * `path` — the WebDAV path, already encoded
/// * `headers` — anything beyond the authorisation
/// * `body` — what goes after the head
///
/// # Errors
///
/// A sentence for a log line. The proxy's own refusal is passed through: it says
/// an operator has to add a target, not that an instance is down.
pub async fn request(
    proxy_address: &str,
    target: &Target,
    timeout: Duration,
    method: &str,
    path: &str,
    headers: &[(String, String)],
    body: Body<'_>,
) -> Result<Response, String> {
    let host_header = http::host_header(&target.host, target.port);
    let mut all = vec![(
        "Authorization".to_string(),
        target.password.basic(&target.username),
    )];
    all.extend_from_slice(headers);

    http::send(
        &Endpoint {
            proxy: Some(proxy_address),
            host: &target.host,
            port: target.port,
            timeout,
        },
        &Request {
            method,
            target: path,
            host_header: &host_header,
            headers: &all,
            body,
        },
    )
    .await
}

/// Creates one collection, treating "it is already there" as success.
///
/// # Arguments
///
/// * `proxy_address` — the egress proxy
/// * `target` — which instance
/// * `timeout` — for the request
/// * `path` — the collection's WebDAV path
///
/// # Errors
///
/// A sentence, for anything that is neither `201` nor an existing collection.
pub async fn make_collection(
    proxy_address: &str,
    target: &Target,
    timeout: Duration,
    path: &str,
) -> Result<(), String> {
    let mut answer = request(
        proxy_address,
        target,
        timeout,
        "MKCOL",
        path,
        &[],
        Body::Empty,
    )
    .await?;
    match answer.status {
        // Created, or there already — which is the common case and not an
        // error: two uploads of the same tenant race to create the same parent,
        // and the loser has nothing to be sorry about.
        201 | 405 => Ok(()),
        status => {
            let body = answer.read_all(MAX_ERROR_BODY).await.unwrap_or_default();
            Err(failure(status, &body))
        }
    }
}

/// What a WebDAV error document says, in one sentence.
///
/// # Arguments
///
/// * `status` — the status code
/// * `body` — the XML the instance sent, possibly empty
pub fn failure(status: u16, body: &[u8]) -> String {
    let text = String::from_utf8_lossy(&body[..body.len().min(MAX_ERROR_BODY)]).to_string();
    match message(&text) {
        Some(message) => format!("Nextcloud answered {status}: {message}"),
        None => format!("Nextcloud answered {status}"),
    }
}

/// The human-readable half of a Nextcloud error document.
///
/// `<s:message>` is where it puts the sentence; `<s:exception>` is the class
/// name behind it and is the better-than-nothing case. An XML parser for two
/// elements would be a dependency for `find`.
fn message(text: &str) -> Option<String> {
    between(text, "<s:message>", "</s:message>")
        .filter(|found| !found.trim().is_empty())
        .or_else(|| between(text, "<s:exception>", "</s:exception>"))
        .map(|found| found.trim().to_string())
}

/// The text between two markers.
fn between(text: &str, open: &str, close: &str) -> Option<String> {
    let start = text.find(open)? + open.len();
    let end = text[start..].find(close)? + start;
    Some(text[start..end].to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_error_document_becomes_a_sentence() {
        let body = b"<?xml version=\"1.0\" encoding=\"utf-8\"?>\
                     <d:error xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\">\
                     <s:exception>Sabre\\DAV\\Exception\\Conflict</s:exception>\
                     <s:message>Parent node does not exist</s:message></d:error>";
        assert_eq!(
            failure(409, body),
            "Nextcloud answered 409: Parent node does not exist"
        );
    }

    #[test]
    fn an_exception_alone_is_better_than_nothing() {
        let body = b"<d:error xmlns:s=\"http://sabredav.org/ns\">\
                     <s:exception>Sabre\\DAV\\Exception\\NotAuthenticated</s:exception>\
                     <s:message></s:message></d:error>";
        assert_eq!(
            failure(401, body),
            "Nextcloud answered 401: Sabre\\DAV\\Exception\\NotAuthenticated"
        );
    }

    #[test]
    fn a_body_that_is_not_a_dav_error_still_says_the_status() {
        assert_eq!(
            failure(502, b"<html>bad gateway</html>"),
            "Nextcloud answered 502"
        );
    }
}
