// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! The one route out of a Home Inventory deployment (ADR-0027, ADR-0036).
//!
//! `api` and `worker` have no route out at all; the containers that need one
//! reach it through here, and here refuses everything that is not on an
//! allowlist generated from `deploy/services.yaml`. At stage 0 that list has one
//! entry — the ClamAV signature mirror — and one caller, the scanner, whose
//! signatures would otherwise freeze at the image build date.
//!
//! It never terminates TLS. With `CONNECT` it sees a host and a port and copies
//! bytes; the certificate the caller checks is the real target's. A
//! TLS-intercepting proxy would put every plugin's credentials within reach of
//! this one container, which is the opposite of what it is for.
//!
//! What it does **not** do yet is the per-plugin half: one listener per plugin
//! segment, the caller identified by the interface a connection arrived on, and
//! the TCP forwarding mode for targets that are not HTTP. That is stage 1, with
//! the plugin runtime (ADR-0028, ADR-0037).

mod allowlist;
mod request;

use allowlist::Allowlist;
use request::Destination;
use std::net::SocketAddr;
use std::process::ExitCode;
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};
use tracing::{debug, error, info, warn};

/// Where the generated allowlist is mounted.
const ALLOWLIST_PATH: &str = "/etc/homeinv/egress-allowlist.conf";

/// The listener port. One per segment interface once plugins arrive; one today.
const PORT: u16 = 8118;

/// How long a caller has to send its request line before it is dropped.
///
/// Without it a connection that says nothing holds a task for ever, and a
/// container limited to 32 MiB has few to spare.
const REQUEST_TIMEOUT: Duration = Duration::from_secs(10);

/// How long to wait for the target to accept a connection.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);

/// The longest request line and header block this will read.
///
/// A megabyte of headers from a caller that never sends the blank line is a
/// memory exhaustion this refuses rather than absorbs.
const MAX_HEAD_BYTES: usize = 16 * 1024;

#[tokio::main]
async fn main() -> ExitCode {
    tracing_subscriber::fmt()
        .json()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
        )
        .init();

    // The health check, for a `scratch` image that has no shell to run a script
    // in. The same shape the blob store uses, and for the same reason.
    if std::env::args().any(|argument| argument == "--health") {
        return match TcpStream::connect(("127.0.0.1", PORT)).await {
            Ok(_) => ExitCode::SUCCESS,
            Err(failure) => {
                eprintln!("the proxy is not accepting connections: {failure}");
                ExitCode::FAILURE
            }
        };
    }

    let path = std::env::var("HOMEINV_EGRESS_ALLOWLIST").unwrap_or_else(|_| ALLOWLIST_PATH.into());
    let list = match tokio::fs::read_to_string(&path).await {
        Ok(text) => Allowlist::parse(&text),
        Err(failure) => {
            // Fail to start rather than start permitting nothing. An empty
            // allowlist and a missing one look identical from the outside — every
            // fetch refused — and the second is a deployment mistake somebody
            // needs to be told about (REQ-NFR-046's reasoning, applied here).
            error!(path = %path, error = %failure, "the allowlist could not be read");
            return ExitCode::FAILURE;
        }
    };
    if list.is_empty() {
        error!(path = %path, "the allowlist is empty; every request would be refused");
        return ExitCode::FAILURE;
    }
    info!(hosts = list.len(), path = %path, "allowlist loaded");

    let listener = match TcpListener::bind(("0.0.0.0", PORT)).await {
        Ok(listener) => listener,
        Err(failure) => {
            error!(port = PORT, error = %failure, "could not listen");
            return ExitCode::FAILURE;
        }
    };
    info!(port = PORT, "the egress proxy is listening");

    let list = Arc::new(list);
    loop {
        tokio::select! {
            accepted = listener.accept() => match accepted {
                Ok((stream, peer)) => {
                    // The address the connection arrived ON, which is this
                    // proxy's interface on the caller's segment and therefore
                    // says which plugin is asking (ADR-0037). Never an address
                    // the caller claims. A socket whose local address cannot be
                    // read has no segment, so it gets the deployment half alone.
                    let arrival = stream.local_addr().map(|address| address.ip()).ok();
                    let list = Arc::clone(&list);
                    tokio::spawn(async move { serve(stream, peer, arrival, list).await });
                }
                Err(failure) => warn!(error = %failure, "a connection could not be accepted"),
            },
            _ = tokio::signal::ctrl_c() => {
                info!("stopping");
                return ExitCode::SUCCESS;
            }
        }
    }
}

/// Handles one caller.
///
/// Every outcome is logged with the caller, the target and what happened: that
/// log is the source for the per-resolver disclosure REQ-ENR-009 asks for, and
/// for the cause behind a stale-signature alert (REQ-SEC-093).
async fn serve(
    stream: TcpStream,
    peer: SocketAddr,
    arrival: Option<std::net::IpAddr>,
    list: Arc<Allowlist>,
) {
    // An unreadable local address belongs to no segment, and `UNSPECIFIED`
    // is in no plugin's network -- so both fall back to the deployment half,
    // which is the safe direction.
    let arrival = arrival.unwrap_or(std::net::IpAddr::V4(std::net::Ipv4Addr::UNSPECIFIED));
    let plugin = list.caller_on(arrival).unwrap_or("-").to_string();
    let mut reader = BufReader::new(stream);

    let mut line = String::new();
    let read = tokio::time::timeout(REQUEST_TIMEOUT, reader.read_line(&mut line)).await;
    match read {
        Ok(Ok(count)) if count > 0 && count <= MAX_HEAD_BYTES => {}
        // A connection that opens and closes without sending anything is the
        // health check, every interval, in every deployment. Saying WARN about it
        // would fill the log with the one line that is always fine, which is how
        // a log stops being read.
        Ok(Ok(0)) => debug!(caller = %peer.ip(), "a connection that sent nothing"),
        _ => {
            warn!(caller = %peer.ip(), "no usable request line");
            return;
        }
    }
    if line.is_empty() {
        return;
    }

    let Some(destination) = request::parse(line.trim_end()) else {
        warn!(caller = %peer.ip(), "a request this proxy does not speak");
        let _ = refuse(reader.get_mut(), "400 Bad Request").await;
        return;
    };

    let (host, port) = match &destination {
        Destination::Tunnel { host, port } => (host.clone(), *port),
        Destination::Fetch { host, port, .. } => (host.clone(), *port),
    };

    if !list.permits(&host, port, arrival) {
        // The refusal is the product, not an error: a caller reaching for a host
        // nobody declared is exactly what this exists to stop. The plugin is in
        // the line because "which plugin asked for this" is the first question an
        // operator has, and the answer is the segment rather than a guess.
        warn!(caller = %peer.ip(), plugin = %plugin, host = %host, port, outcome = "refused",
              "not on the allowlist");
        let _ = refuse(reader.get_mut(), "403 Forbidden").await;
        return;
    }

    let addresses = match tokio::net::lookup_host((host.as_str(), port)).await {
        Ok(addresses) => addresses.collect::<Vec<_>>(),
        Err(failure) => {
            warn!(caller = %peer.ip(), host = %host, port, outcome = "unresolved",
                  error = %failure, "the name did not resolve");
            let _ = refuse(reader.get_mut(), "502 Bad Gateway").await;
            return;
        }
    };

    // Resolved here rather than trusted from the caller, and every answer is
    // checked: an allowlisted name whose DNS points at 169.254.169.254 is the
    // way a name check alone is defeated (REQ-SEC-034).
    let permitted: Vec<SocketAddr> = addresses
        .into_iter()
        .filter(|address| !allowlist::is_forbidden(address.ip()))
        .collect();
    if permitted.is_empty() {
        warn!(caller = %peer.ip(), host = %host, port, outcome = "refused",
              "every address resolves into a range this proxy never reaches");
        let _ = refuse(reader.get_mut(), "403 Forbidden").await;
        return;
    }

    let Some(upstream) = connect(&permitted).await else {
        warn!(caller = %peer.ip(), host = %host, port, outcome = "unreachable",
              "the target did not accept a connection");
        let _ = refuse(reader.get_mut(), "502 Bad Gateway").await;
        return;
    };

    info!(caller = %peer.ip(), host = %host, port, outcome = "allowed", "forwarding");

    let result = match destination {
        Destination::Tunnel { .. } => tunnel(reader, upstream).await,
        Destination::Fetch { rewritten, .. } => fetch(reader, upstream, &rewritten).await,
    };
    if let Err(failure) = result {
        // A copy that ends early is ordinary — a client closing a connection
        // looks exactly like this — so it is DEBUG-shaped information at INFO's
        // level of importance: the outcome above already said what mattered.
        info!(caller = %peer.ip(), host = %host, error = %failure, "the exchange ended");
    }
}

/// Connects to the first address that answers.
async fn connect(addresses: &[SocketAddr]) -> Option<TcpStream> {
    for address in addresses {
        if let Ok(Ok(stream)) =
            tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect(address)).await
        {
            return Some(stream);
        }
    }
    None
}

/// Answers a caller that is not getting what it asked for.
async fn refuse(stream: &mut TcpStream, status: &str) -> std::io::Result<()> {
    // No body. A proxy error page is a page an attacker can read, and the status
    // line says everything a client needs.
    stream
        .write_all(format!("HTTP/1.1 {status}\r\nConnection: close\r\n\r\n").as_bytes())
        .await?;
    stream.shutdown().await
}

/// A `CONNECT` tunnel: say yes, then copy in both directions until one side ends.
async fn tunnel(mut reader: BufReader<TcpStream>, mut upstream: TcpStream) -> std::io::Result<()> {
    // The caller's remaining headers are read and discarded: a CONNECT carries
    // none this proxy acts on, and leaving them in the buffer would prepend them
    // to the TLS handshake.
    discard_headers(&mut reader).await?;

    reader
        .get_mut()
        .write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n")
        .await?;

    let mut client = reader.into_inner();
    tokio::io::copy_bidirectional(&mut client, &mut upstream).await?;
    Ok(())
}

/// A plain HTTP fetch: send the rewritten request line, then relay.
async fn fetch(
    mut reader: BufReader<TcpStream>,
    mut upstream: TcpStream,
    rewritten: &str,
) -> std::io::Result<()> {
    upstream.write_all(rewritten.as_bytes()).await?;

    // The caller's headers, forwarded as they stand except for the hop-by-hop
    // ones a proxy must not pass on (RFC 9110 §7.6.1). `Connection: close` is
    // added because this handles one request per connection: the next request on
    // the same connection could name a different host, and forwarding it to this
    // target without checking would be the allowlist bypassed.
    let mut head = Vec::new();
    loop {
        let mut line = String::new();
        let count = reader.read_line(&mut line).await?;
        if count == 0 || line.trim().is_empty() {
            break;
        }
        if head.len() + line.len() > MAX_HEAD_BYTES {
            return Err(std::io::Error::other("the header block is too large"));
        }
        let lower = line.to_ascii_lowercase();
        if lower.starts_with("proxy-connection:") || lower.starts_with("connection:") {
            continue;
        }
        head.extend_from_slice(line.as_bytes());
    }
    head.extend_from_slice(b"Connection: close\r\n\r\n");
    upstream.write_all(&head).await?;

    let mut client = reader.into_inner();
    tokio::io::copy_bidirectional(&mut client, &mut upstream).await?;
    Ok(())
}

/// Reads to the end of the header block and throws it away.
async fn discard_headers(reader: &mut BufReader<TcpStream>) -> std::io::Result<()> {
    let mut read = 0usize;
    loop {
        let mut line = String::new();
        let count = reader.read_line(&mut line).await?;
        read += count;
        if count == 0 || line.trim().is_empty() {
            return Ok(());
        }
        if read > MAX_HEAD_BYTES {
            return Err(std::io::Error::other("the header block is too large"));
        }
    }
}
