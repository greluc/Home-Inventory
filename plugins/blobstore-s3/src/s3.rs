// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! What an S3 request is, on top of the shared HTTP transport.
//!
//! The exchange itself — the `CONNECT` tunnel, the TLS handshake inside it, the
//! request writer and the framed body reader — is
//! `homeinv_plugin_common::http`, because three plugins now need exactly that
//! ([ADR-0072](../../../docs/adr/0072-first-party-plugins-live-here.md)). What
//! is S3 and therefore lives here: the canonical query, the signature, and what
//! an error document says.

use std::time::Duration;

use homeinv_plugin_common::encoding::hex;
use homeinv_plugin_common::http::{self, Body, Endpoint, Request, Response};
use homeinv_plugin_common::mac::sha256;
use homeinv_plugin_common::time::{amz_stamps, now};

use crate::sigv4::{authorization, encode_segment, Signable};
use crate::target::Target;

/// How much of an error body is read before it is truncated. An S3 error
/// document is a few hundred bytes; anything larger is not one.
pub const MAX_ERROR_BODY: usize = 8 * 1024;

/// One query parameter, unencoded.
pub type Parameter<'a> = (&'a str, String);

/// Makes one signed request and reads its head.
///
/// # Arguments
///
/// * `proxy_address` — `host:port` of the egress proxy, the one route out
/// * `target` — where and as whom
/// * `timeout` — for the tunnel, the request and the response head
/// * `method` — `GET`, `PUT`, `HEAD`, `DELETE`, `POST`
/// * `key` — the object key, unencoded, or empty for a bucket-level request
/// * `parameters` — query parameters, unencoded
/// * `body` — what goes after the head
///
/// # Errors
///
/// A sentence for a log line. The proxy's own refusal is passed through: it says
/// an operator has to add a target, not that a store is down.
pub async fn request(
    proxy_address: &str,
    target: &Target,
    timeout: Duration,
    method: &str,
    key: &str,
    parameters: &[Parameter<'_>],
    body: Body<'_>,
) -> Result<Response, String> {
    let payload_sha256 = hex(&sha256(body.bytes()));
    let (date, stamp) = amz_stamps(now());

    let path = target.path_for(key);
    let (wire_query, canonical_query) = queries(parameters);
    // In virtual-hosted style the bucket is part of the hostname, so the tunnel,
    // the certificate and the `Host` header all name the same thing — and the
    // operator's allowlist has to carry it.
    let connect_host = target.connect_host();
    let host = target.request_host();

    let extra = vec![("x-amz-content-sha256".to_string(), payload_sha256.clone())];
    let signature = authorization(
        &Signable {
            method,
            path: &path,
            query: &canonical_query,
            host: &host,
            payload_sha256: &payload_sha256,
            extra: &extra,
        },
        &target.credentials,
        &target.region,
        "s3",
        &date,
        &stamp,
    );

    let request_target = if wire_query.is_empty() {
        path
    } else {
        format!("{path}?{wire_query}")
    };
    let headers = vec![
        ("x-amz-date".to_string(), stamp),
        ("x-amz-content-sha256".to_string(), payload_sha256),
        ("Authorization".to_string(), signature),
    ];

    http::send(
        &Endpoint {
            proxy: Some(proxy_address),
            host: &connect_host,
            port: target.port,
            timeout,
        },
        &Request {
            method,
            target: &request_target,
            host_header: &host,
            headers: &headers,
            body,
        },
    )
    .await
}

/// The query as it goes on the wire, and as it is signed.
///
/// They differ in one place: a flag parameter such as `?uploads` is written
/// without a value and signed **with** an empty one, because the canonical form
/// requires every parameter to carry `=`. Every S3 implementation is built
/// against that asymmetry; a sender that wrote `?uploads=` would also be
/// accepted, and a sender that signed `uploads` without it would not.
fn queries(parameters: &[Parameter<'_>]) -> (String, String) {
    let mut sorted: Vec<&Parameter<'_>> = parameters.iter().collect();
    sorted.sort_by(|left, right| left.0.cmp(right.0));

    let wire = sorted
        .iter()
        .map(|(name, value)| {
            if value.is_empty() {
                encode_segment(name)
            } else {
                format!("{}={}", encode_segment(name), encode_segment(value))
            }
        })
        .collect::<Vec<_>>()
        .join("&");
    let canonical = sorted
        .iter()
        .map(|(name, value)| format!("{}={}", encode_segment(name), encode_segment(value)))
        .collect::<Vec<_>>()
        .join("&");
    (wire, canonical)
}

/// What an S3 error document says, in one sentence.
///
/// # Arguments
///
/// * `status` — the status code
/// * `body` — the XML the store sent, possibly empty
pub fn failure(status: u16, body: &[u8]) -> String {
    let text = String::from_utf8_lossy(&body[..body.len().min(MAX_ERROR_BODY)]).to_string();
    let code = between(&text, "<Code>", "</Code>");
    let message = between(&text, "<Message>", "</Message>");
    match (code, message) {
        (Some(code), Some(message)) => format!("the store answered {status} {code}: {message}"),
        (Some(code), None) => format!("the store answered {status} {code}"),
        _ => format!("the store answered {status}"),
    }
}

/// The text between two markers, which is all the XML this plugin reads.
///
/// An XML parser for three elements in a document this plugin also writes would
/// be a dependency for `find`. Where the document is somebody else's — an error,
/// an upload id — the markers are fixed by the S3 API and carry no attributes.
pub fn between(text: &str, open: &str, close: &str) -> Option<String> {
    let start = text.find(open)? + open.len();
    let end = text[start..].find(close)? + start;
    Some(text[start..end].to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_flag_parameter_is_written_bare_and_signed_with_an_equals() {
        let (wire, canonical) = queries(&[("uploads", String::new())]);
        assert_eq!(wire, "uploads");
        assert_eq!(canonical, "uploads=");
    }

    #[test]
    fn parameters_are_sorted_and_encoded() {
        let (wire, canonical) = queries(&[
            ("uploadId", "a b+c".to_string()),
            ("partNumber", "12".to_string()),
        ]);
        assert_eq!(wire, "partNumber=12&uploadId=a%20b%2Bc");
        assert_eq!(wire, canonical);
    }

    #[test]
    fn an_error_document_becomes_a_sentence() {
        let body = b"<?xml version=\"1.0\"?><Error><Code>NoSuchBucket</Code>\
                     <Message>The specified bucket does not exist</Message></Error>";
        assert_eq!(
            failure(404, body),
            "the store answered 404 NoSuchBucket: The specified bucket does not exist"
        );
    }

    #[test]
    fn a_body_that_is_not_xml_still_says_the_status() {
        assert_eq!(failure(503, b"<html>away</html>"), "the store answered 503");
    }

    #[test]
    fn an_upload_id_is_read_out_of_the_document() {
        let body = "<InitiateMultipartUploadResult><Bucket>b</Bucket><Key>k</Key>\
                    <UploadId>2~abc-def</UploadId></InitiateMultipartUploadResult>";
        assert_eq!(
            between(body, "<UploadId>", "</UploadId>"),
            Some("2~abc-def".to_string())
        );
    }
}
