// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! HTTP/1.1 to an S3-compatible store, inside the proxy's tunnel.
//!
//! Five requests are all this plugin ever makes — `PUT`, `GET`, `HEAD`, `DELETE`
//! and the three-step multipart upload — so this is a request writer and a
//! response reader rather than an HTTP client. What a client library would add
//! is a connection pool, a redirect policy, a DNS resolver and a retry ladder,
//! and a plugin on an internal segment can use none of them: there is no DNS
//! here, the one route out is a `CONNECT` tunnel through the egress proxy
//! ([ADR-0027](../../../docs/adr/0027-egress-enforcement.md)), and a redirect to
//! a host the allowlist does not name would be refused by the proxy anyway.
//!
//! # One connection per request
//!
//! Deliberate. Keep-alive across requests would save a handshake per 8 MiB part
//! and would mean carrying a half-read socket between calls, where a response
//! nobody drained becomes the next request's answer — the classic HTTP/1.1 bug,
//! and one that shows up as the wrong object rather than as an error. Every
//! request therefore says `Connection: close` and the socket is dropped with the
//! response.

use std::time::Duration;

use homeinv_plugin_common::encoding::hex;
use homeinv_plugin_common::mac::sha256;
use homeinv_plugin_common::proxy;
use homeinv_plugin_common::time::{amz_stamps, now};
use homeinv_plugin_common::tls::client_handshake;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio_rustls::client::TlsStream;

use crate::sigv4::{authorization, encode_segment, Signable};
use crate::target::Target;

/// How much of a response head is read before it is called nonsense.
const MAX_HEAD: usize = 64 * 1024;

/// How much of an error body is read before it is truncated. An S3 error
/// document is a few hundred bytes; anything larger is not one.
const MAX_ERROR_BODY: usize = 8 * 1024;

/// How large a piece the body reader hands back at a time.
const READ: usize = 64 * 1024;

/// A request's body.
pub enum Body<'a> {
    /// No body at all: `GET`, `HEAD`, `DELETE`.
    Empty,
    /// The bytes, whose SHA-256 is signed.
    Bytes(&'a [u8]),
}

/// One query parameter, unencoded.
pub type Parameter<'a> = (&'a str, String);

/// A response, with its body still on the socket.
pub struct Response {
    /// The status code.
    pub status: u16,
    /// The headers, names lower-cased.
    pub headers: Vec<(String, String)>,
    /// What is left to read.
    reader: Reader,
}

/// How the rest of the body is framed.
enum Framing {
    /// `Content-Length` said how much.
    Length(u64),
    /// `Transfer-Encoding: chunked`. AWS uses it for a slow
    /// `CompleteMultipartUpload`, so it is not optional to support.
    Chunked,
    /// Neither, so the body ends when the connection does.
    ToEnd,
}

/// The socket and whatever was read past the head.
struct Reader {
    stream: TlsStream<TcpStream>,
    buffered: Vec<u8>,
    framing: Framing,
    done: bool,
}

impl Response {
    /// One header's value, if it is there.
    ///
    /// # Arguments
    ///
    /// * `name` — lower-case
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(header, _)| header == name)
            .map(|(_, value)| value.as_str())
    }

    /// The next piece of the body, or `None` when it has all arrived.
    ///
    /// # Errors
    ///
    /// A sentence for a log line, when the connection fails mid-body or the
    /// chunked framing is malformed.
    pub async fn next_chunk(&mut self) -> Result<Option<Vec<u8>>, String> {
        self.reader.next_chunk().await
    }

    /// The whole body, for the small ones: an error document, an upload id.
    ///
    /// # Arguments
    ///
    /// * `limit` — stop after this many bytes, so a store that answers a 404
    ///   with a web page cannot be read into memory
    ///
    /// # Errors
    ///
    /// As [`Self::next_chunk`].
    pub async fn read_all(&mut self, limit: usize) -> Result<Vec<u8>, String> {
        let mut out = Vec::new();
        while let Some(piece) = self.reader.next_chunk().await? {
            out.extend_from_slice(&piece);
            if out.len() >= limit {
                out.truncate(limit);
                break;
            }
        }
        Ok(out)
    }
}

impl Reader {
    async fn next_chunk(&mut self) -> Result<Option<Vec<u8>>, String> {
        if self.done {
            return Ok(None);
        }
        match self.framing {
            Framing::Length(0) => {
                self.done = true;
                Ok(None)
            }
            Framing::Length(remaining) => {
                let piece = self.take(remaining.min(READ as u64) as usize).await?;
                if piece.is_empty() {
                    return Err("the store closed the connection before the body ended".to_string());
                }
                self.framing = Framing::Length(remaining - piece.len() as u64);
                Ok(Some(piece))
            }
            Framing::Chunked => self.next_chunked().await,
            Framing::ToEnd => {
                let piece = self.take(READ).await?;
                if piece.is_empty() {
                    self.done = true;
                    return Ok(None);
                }
                Ok(Some(piece))
            }
        }
    }

    /// One chunk of a `chunked` body, size line and all.
    async fn next_chunked(&mut self) -> Result<Option<Vec<u8>>, String> {
        let line = self.line().await?;
        // The size is hexadecimal and may carry chunk extensions after a `;`,
        // which nothing here uses and which are skipped rather than refused.
        let size = usize::from_str_radix(line.split(';').next().unwrap_or("").trim(), 16)
            .map_err(|_| format!("the store sent a chunk size that is not a number: {line:?}"))?;
        if size == 0 {
            // The trailer, then the end. Read until the blank line rather than
            // assuming there is none.
            loop {
                let trailer = self.line().await?;
                if trailer.is_empty() {
                    break;
                }
            }
            self.done = true;
            return Ok(None);
        }
        let piece = self.take_exactly(size).await?;
        // The CRLF that follows every chunk.
        let _ = self.take_exactly(2).await?;
        Ok(Some(piece))
    }

    /// Up to `count` bytes, from the buffer first and the socket after.
    async fn take(&mut self, count: usize) -> Result<Vec<u8>, String> {
        if !self.buffered.is_empty() {
            let taken = self.buffered.len().min(count);
            let piece = self.buffered.drain(..taken).collect();
            return Ok(piece);
        }
        let mut buffer = vec![0_u8; count];
        let read = self
            .stream
            .read(&mut buffer)
            .await
            .map_err(|failure| format!("the store's answer could not be read: {failure}"))?;
        buffer.truncate(read);
        Ok(buffer)
    }

    /// Exactly `count` bytes, or an error.
    async fn take_exactly(&mut self, count: usize) -> Result<Vec<u8>, String> {
        let mut out = Vec::with_capacity(count);
        while out.len() < count {
            let piece = self.take(count - out.len()).await?;
            if piece.is_empty() {
                return Err("the store closed the connection mid-body".to_string());
            }
            out.extend_from_slice(&piece);
        }
        Ok(out)
    }

    /// One CRLF-terminated line, without the terminator.
    async fn line(&mut self) -> Result<String, String> {
        let mut out = Vec::new();
        loop {
            let byte = self.take_exactly(1).await?;
            if byte[0] == b'\n' {
                if out.last() == Some(&b'\r') {
                    out.pop();
                }
                return String::from_utf8(out)
                    .map_err(|_| "the store sent a line that is not text".to_string());
            }
            out.push(byte[0]);
            if out.len() > MAX_HEAD {
                return Err("the store sent a line with no end".to_string());
            }
        }
    }
}

/// Makes one signed request and reads its head.
///
/// # Arguments
///
/// * `proxy_address` — `host:port` of the egress proxy, the one route out
/// * `target` — where and as whom
/// * `timeout` — for opening the tunnel
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
    let bytes = match body {
        Body::Empty => &[][..],
        Body::Bytes(bytes) => bytes,
    };
    let payload_sha256 = hex(&sha256(bytes));
    let (date, stamp) = amz_stamps(now());

    let path = target.path_for(key);
    let (wire_query, canonical_query) = queries(parameters);
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

    let mut head = String::with_capacity(512);
    if wire_query.is_empty() {
        head.push_str(&format!("{method} {path} HTTP/1.1\r\n"));
    } else {
        head.push_str(&format!("{method} {path}?{wire_query} HTTP/1.1\r\n"));
    }
    head.push_str(&format!("Host: {host}\r\n"));
    head.push_str(&format!("x-amz-date: {stamp}\r\n"));
    head.push_str(&format!("x-amz-content-sha256: {payload_sha256}\r\n"));
    head.push_str(&format!("Authorization: {signature}\r\n"));
    head.push_str(&format!("Content-Length: {}\r\n", bytes.len()));
    head.push_str("Connection: close\r\n\r\n");

    // In virtual-hosted style the bucket is part of the hostname, so the tunnel,
    // the certificate and the `Host` header all name the same thing and the
    // operator's allowlist has to carry it.
    let connect_host = target.connect_host();
    let tunnel = proxy::connect(proxy_address, &connect_host, target.port, timeout).await?;
    let mut stream = client_handshake(tunnel, &connect_host).await?;

    stream
        .write_all(head.as_bytes())
        .await
        .map_err(|failure| format!("the request could not be sent: {failure}"))?;
    if !bytes.is_empty() {
        stream
            .write_all(bytes)
            .await
            .map_err(|failure| format!("the body could not be sent: {failure}"))?;
    }
    stream
        .flush()
        .await
        .map_err(|failure| format!("the request could not be flushed: {failure}"))?;

    read_head(stream).await
}

/// Reads the status line and the headers, leaving the body on the socket.
async fn read_head(mut stream: TlsStream<TcpStream>) -> Result<Response, String> {
    let mut head = Vec::with_capacity(1024);
    let mut byte = [0_u8; 1];
    while head.len() < MAX_HEAD {
        let read = stream
            .read(&mut byte)
            .await
            .map_err(|failure| format!("the store did not answer: {failure}"))?;
        if read == 0 {
            break;
        }
        head.push(byte[0]);
        if head.ends_with(b"\r\n\r\n") {
            break;
        }
    }
    let text = String::from_utf8_lossy(&head).to_string();
    let mut lines = text.split("\r\n");
    let status_line = lines
        .next()
        .ok_or_else(|| "the store answered nothing at all".to_string())?;
    let status: u16 = status_line
        .split_whitespace()
        .nth(1)
        .and_then(|code| code.parse().ok())
        .ok_or_else(|| format!("the store's answer has no status: {status_line:?}"))?;

    let mut headers = Vec::new();
    for line in lines {
        if line.is_empty() {
            break;
        }
        if let Some((name, value)) = line.split_once(':') {
            headers.push((name.trim().to_ascii_lowercase(), value.trim().to_string()));
        }
    }

    let chunked = headers
        .iter()
        .any(|(name, value)| name == "transfer-encoding" && value.contains("chunked"));
    let length = headers
        .iter()
        .find(|(name, _)| name == "content-length")
        .and_then(|(_, value)| value.parse::<u64>().ok());
    let framing = if chunked {
        Framing::Chunked
    } else if let Some(length) = length {
        Framing::Length(length)
    } else {
        Framing::ToEnd
    };

    Ok(Response {
        status,
        headers,
        reader: Reader {
            stream,
            buffered: Vec::new(),
            framing,
            done: false,
        },
    })
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
