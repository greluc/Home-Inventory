// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! One HTTP/1.1 exchange, through the one route out.
//!
//! Three plugins speak HTTP to something outside the deployment — a webhook
//! receiver, an S3 endpoint, a Nextcloud instance — and all three do it the same
//! way: `CONNECT` to the egress proxy, TLS to the target inside that tunnel, one
//! request, one response
//! ([ADR-0026](../../../docs/adr/0026-core-outbound-via-plugins.md),
//! [ADR-0027](../../../docs/adr/0027-egress-enforcement.md)). The third of them
//! is what moved it here, which is the rule
//! [ADR-0072](../../../docs/adr/0072-first-party-plugins-live-here.md) states.
//!
//! # Why this is written out rather than delegated to a client library
//!
//! The same reason `egress-proxy/` writes the other end of it: a request line, a
//! handful of headers and a framed body are a hundred and fifty lines, against a
//! dependency tree that would be the largest thing in the image. A client
//! library would also bring a connection pool, a redirect policy and a DNS
//! resolver, and a plugin on an internal segment can use none of them — there is
//! no DNS here, and a redirect is a target the far side chose, which the
//! allowlist never approved.
//!
//! **No redirect is ever followed.** A 3xx is returned as itself.
//!
//! # One connection per exchange
//!
//! Every request says `Connection: close` and the socket is dropped with the
//! response. Keep-alive would mean carrying a half-read socket between calls,
//! where a response nobody drained becomes the next request's answer — the
//! classic HTTP/1.1 bug, and one that surfaces as the wrong object rather than
//! as an error.

use std::time::Duration;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::proxy;
use crate::tls::client_handshake;

/// How much of a response head is read before it is called nonsense.
const MAX_HEAD: usize = 64 * 1024;

/// How large a piece the body reader hands back at a time.
const READ: usize = 64 * 1024;

/// The default port, which the `Host` header leaves out.
const DEFAULT_PORT: u16 = 443;

/// A request's body.
pub enum Body<'a> {
    /// No body at all: `GET`, `HEAD`, `DELETE`.
    Empty,
    /// The bytes, sent with a `Content-Length`.
    Bytes(&'a [u8]),
}

impl Body<'_> {
    /// The bytes, empty when there are none.
    pub fn bytes(&self) -> &[u8] {
        match self {
            Self::Empty => &[],
            Self::Bytes(bytes) => bytes,
        }
    }
}

/// Where the exchange goes.
pub struct Endpoint<'a> {
    /// `host:port` of the egress proxy, or `None` to dial directly — which only
    /// a test does: in a deployment a plugin segment has no route out at all.
    pub proxy: Option<&'a str>,
    /// The host to connect to and to verify the certificate against.
    pub host: &'a str,
    /// Its port.
    pub port: u16,
    /// How long the connection, the request and the response HEAD may take
    /// together. The body is the caller's to bound: a download of arbitrary size
    /// must not die on a budget meant for a handshake.
    pub timeout: Duration,
}

/// What to send.
pub struct Request<'a> {
    /// `GET`, `PUT`, `POST`, `HEAD`, `DELETE`, `MKCOL`, `MOVE`.
    pub method: &'a str,
    /// Origin form: the path, already encoded, with its query if any.
    pub target: &'a str,
    /// The `Host` header's value. [`host_header`] builds one.
    pub host_header: &'a str,
    /// Further headers, sent in the order given.
    pub headers: &'a [(String, String)],
    /// The body.
    pub body: Body<'a>,
}

/// What came back, with its body still on the socket.
pub struct Response {
    /// The status code.
    pub status: u16,
    /// The status line as it arrived, which is what a delivery log records.
    pub status_line: String,
    /// The headers, names lower-cased.
    pub headers: Vec<(String, String)>,
    /// What is left to read.
    reader: Reader,
}

/// How the rest of the body is framed.
enum Framing {
    /// `Content-Length` said how much.
    Length(u64),
    /// `Transfer-Encoding: chunked`.
    Chunked,
    /// Neither, so the body ends when the connection does.
    ToEnd,
}

/// The socket and whatever was read past the head.
struct Reader {
    stream: Box<dyn AsyncRead + Send + Unpin>,
    buffered: Vec<u8>,
    framing: Framing,
    done: bool,
}

/// The `Host` header for a host and port, leaving out the default one.
///
/// # Arguments
///
/// * `host` — the name
/// * `port` — its port
pub fn host_header(host: &str, port: u16) -> String {
    if port == DEFAULT_PORT {
        host.to_string()
    } else {
        format!("{host}:{port}")
    }
}

/// Makes one request and reads its head.
///
/// # Arguments
///
/// * `endpoint` — where it goes and how long the head may take
/// * `request` — what to send
///
/// # Errors
///
/// A sentence for a log line. The proxy's own refusal is passed through,
/// because it is the useful case: it says an operator has to add a target rather
/// than that a server is down.
pub async fn send(endpoint: &Endpoint<'_>, request: &Request<'_>) -> Result<Response, String> {
    tokio::time::timeout(endpoint.timeout, exchange(endpoint, request))
        .await
        .map_err(|_| {
            format!(
                "{} did not answer within {} seconds",
                endpoint.host,
                endpoint.timeout.as_secs()
            )
        })?
}

async fn exchange(endpoint: &Endpoint<'_>, request: &Request<'_>) -> Result<Response, String> {
    let socket = match endpoint.proxy {
        Some(address) => {
            proxy::connect(address, endpoint.host, endpoint.port, endpoint.timeout).await?
        }
        None => TcpStream::connect((endpoint.host, endpoint.port))
            .await
            .map_err(|failure| format!("{} could not be reached: {failure}", endpoint.host))?,
    };
    socket
        .set_nodelay(true)
        .map_err(|failure| format!("the connection could not be configured: {failure}"))?;

    let mut stream = client_handshake(socket, endpoint.host).await?;
    let bytes = request.body.bytes();

    let mut head = String::with_capacity(512);
    head.push_str(&format!(
        "{} {} HTTP/1.1\r\n",
        request.method, request.target
    ));
    head.push_str(&format!("Host: {}\r\n", request.host_header));
    for (name, value) in request.headers {
        head.push_str(&format!("{name}: {value}\r\n"));
    }
    head.push_str(&format!("Content-Length: {}\r\n", bytes.len()));
    head.push_str("Connection: close\r\n\r\n");

    stream
        .write_all(head.as_bytes())
        .await
        .map_err(|failure| format!("the request could not be sent: {failure}"))?;
    if !bytes.is_empty() {
        stream
            .write_all(bytes)
            .await
            .map_err(|failure| format!("the request body could not be sent: {failure}"))?;
    }
    stream
        .flush()
        .await
        .map_err(|failure| format!("the request could not be flushed: {failure}"))?;

    read_head(Box::new(stream)).await
}

/// Reads the status line and the headers, leaving the body on the socket.
async fn read_head(mut stream: Box<dyn AsyncRead + Send + Unpin>) -> Result<Response, String> {
    let mut head = Vec::with_capacity(1024);
    let mut byte = [0_u8; 1];
    while head.len() < MAX_HEAD {
        let read = stream
            .read(&mut byte)
            .await
            .map_err(|failure| format!("the answer could not be read: {failure}"))?;
        if read == 0 {
            break;
        }
        head.push(byte[0]);
        if head.ends_with(b"\r\n\r\n") {
            break;
        }
    }
    let (status_line, status, headers) = parse_head(&head)?;
    let framing = framing_of(&headers);
    Ok(Response {
        status,
        status_line,
        headers,
        reader: Reader {
            stream,
            buffered: Vec::new(),
            framing,
            done: false,
        },
    })
}

/// A parsed response head: the status line, the code, and the headers.
type Head = (String, u16, Vec<(String, String)>);

/// Splits a response head into its status line, its code and its headers.
fn parse_head(head: &[u8]) -> Result<Head, String> {
    let text = String::from_utf8_lossy(head).to_string();
    let mut lines = text.split("\r\n");
    let status_line = lines
        .next()
        .filter(|line| !line.is_empty())
        .ok_or_else(|| "the far side answered nothing at all".to_string())?
        .to_string();
    let status = status_line
        .split_whitespace()
        .nth(1)
        .and_then(|code| code.parse::<u16>().ok())
        .ok_or_else(|| format!("'{status_line}' is not an HTTP status line"))?;

    let mut headers = Vec::new();
    for line in lines {
        if line.is_empty() {
            break;
        }
        if let Some((name, value)) = line.split_once(':') {
            headers.push((name.trim().to_ascii_lowercase(), value.trim().to_string()));
        }
    }
    Ok((status_line, status, headers))
}

/// How a body is framed, from the headers that say so.
fn framing_of(headers: &[(String, String)]) -> Framing {
    if headers
        .iter()
        .any(|(name, value)| name == "transfer-encoding" && value.contains("chunked"))
    {
        return Framing::Chunked;
    }
    match headers
        .iter()
        .find(|(name, _)| name == "content-length")
        .and_then(|(_, value)| value.parse::<u64>().ok())
    {
        Some(length) => Framing::Length(length),
        None => Framing::ToEnd,
    }
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
    /// * `limit` — stop after this many bytes, so a far side that answers with a
    ///   web page cannot decide how much memory this costs
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
                    return Err("the connection closed before the body ended".to_string());
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
            .map_err(|_| format!("'{line}' is not a chunk size"))?;
        if size == 0 {
            // The trailer, then the end. Read until the blank line rather than
            // assuming there is none.
            while !self.line().await?.is_empty() {}
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
            return Ok(self.buffered.drain(..taken).collect());
        }
        let mut buffer = vec![0_u8; count];
        let read = self
            .stream
            .read(&mut buffer)
            .await
            .map_err(|failure| format!("the body could not be read: {failure}"))?;
        buffer.truncate(read);
        Ok(buffer)
    }

    /// Exactly `count` bytes, or an error.
    async fn take_exactly(&mut self, count: usize) -> Result<Vec<u8>, String> {
        let mut out = Vec::with_capacity(count);
        while out.len() < count {
            let piece = self.take(count - out.len()).await?;
            if piece.is_empty() {
                return Err("the connection closed mid-body".to_string());
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
                    .map_err(|_| "a chunk header that is not text".to_string());
            }
            out.push(byte[0]);
            if out.len() > MAX_HEAD {
                return Err("a chunk header with no end".to_string());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A response whose body is already in memory, for the framing tests.
    fn answering(head: &str, body: &[u8]) -> Response {
        let (status_line, status, headers) = parse_head(head.as_bytes()).expect("a head");
        let framing = framing_of(&headers);
        Response {
            status,
            status_line,
            headers,
            reader: Reader {
                stream: Box::new(std::io::Cursor::new(body.to_vec())),
                buffered: Vec::new(),
                framing,
                done: false,
            },
        }
    }

    #[test]
    fn a_head_becomes_a_status_and_lower_case_headers() {
        let (line, status, headers) =
            parse_head(b"HTTP/1.1 204 No Content\r\nETag: \"abc\"\r\nContent-Length: 0\r\n\r\n")
                .expect("a head");
        assert_eq!(line, "HTTP/1.1 204 No Content");
        assert_eq!(status, 204);
        assert_eq!(headers[0], ("etag".to_string(), "\"abc\"".to_string()));
    }

    #[test]
    fn nothing_at_all_is_an_error_rather_than_a_zero() {
        assert!(parse_head(b"").is_err());
        assert!(parse_head(b"not a status line\r\n\r\n").is_err());
    }

    #[tokio::test]
    async fn a_counted_body_is_read_to_its_length() {
        let mut answer = answering(
            "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n",
            b"hello and then some more that is not part of it",
        );
        assert_eq!(answer.read_all(1024).await.expect("a body"), b"hello");
    }

    #[tokio::test]
    async fn a_chunked_body_is_reassembled() {
        let mut answer = answering(
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n",
            b"5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n",
        );
        assert_eq!(answer.read_all(1024).await.expect("a body"), b"hello world");
    }

    #[tokio::test]
    async fn a_chunk_extension_is_skipped_rather_than_refused() {
        let mut answer = answering(
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n",
            b"5;name=value\r\nhello\r\n0\r\n\r\n",
        );
        assert_eq!(answer.read_all(1024).await.expect("a body"), b"hello");
    }

    #[tokio::test]
    async fn a_body_with_no_framing_ends_with_the_connection() {
        let mut answer = answering("HTTP/1.1 200 OK\r\n\r\n", b"as much as arrives");
        assert_eq!(
            answer.read_all(1024).await.expect("a body"),
            b"as much as arrives"
        );
    }

    #[tokio::test]
    async fn a_truncated_counted_body_is_an_error_rather_than_a_short_read() {
        // The case that matters: a file that arrives half-written must not look
        // like a file.
        let mut answer = answering("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n", b"short");
        assert!(answer.read_all(1024).await.is_err());
    }

    #[tokio::test]
    async fn a_limit_stops_a_body_that_would_not() {
        let mut answer = answering("HTTP/1.1 500 Server Error\r\n\r\n", &[b'x'; 100_000]);
        assert_eq!(answer.read_all(64).await.expect("a body").len(), 64);
    }

    #[test]
    fn the_host_header_omits_the_default_port() {
        assert_eq!(
            host_header("objects.example.org", 443),
            "objects.example.org"
        );
        assert_eq!(host_header("minio.example", 9000), "minio.example:9000");
    }
}
