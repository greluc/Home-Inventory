// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! The submission conversation: EHLO, STARTTLS, AUTH, MAIL, RCPT, DATA.
//!
//! # TLS is not optional and there is no flag to make it optional
//!
//! A submission that falls back to plaintext hands the deployment's mail
//! credentials to anything on the path, and it does it silently — the mail still
//! arrives, which is why nobody notices. So: a server that does not offer
//! `STARTTLS` is refused, and `465` is TLS from the first byte. A mode that
//! exists is a mode somebody runs.
//!
//! # Why the line protocol is written out
//!
//! What this needs is eight commands and a multiline reply reader. The mail
//! libraries that would replace it bring a parser for everything else in RFC
//! 5321 — routing, relaying, address rewriting — into a container that is one
//! socket in and one socket out.

use std::sync::Arc;
use std::time::Duration;

use homeinv_plugin_common::encoding::base64;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio_rustls::rustls::pki_types::ServerName;
use tokio_rustls::rustls::{ClientConfig, RootCertStore};
use tokio_rustls::TlsConnector;

/// What the conversation runs over: the tunnel, or TLS inside it.
///
/// An enum and not a boxed trait object, because `STARTTLS` has to take the
/// plain socket back out to hand it to the handshake — and getting a concrete
/// type out of a `Box<dyn …>` needs either `Any` or an unsafe cast. Three small
/// methods forward to whichever half is live, and nothing here is unsafe.
enum Connection {
    /// Before `STARTTLS`, and after the tunnel is opened.
    Plain(TcpStream),
    /// Once the handshake has happened, or from the first byte on 465.
    Tls(Box<tokio_rustls::client::TlsStream<TcpStream>>),
}

impl Connection {
    /// Writes everything, on whichever half is live.
    async fn write_all(&mut self, bytes: &[u8]) -> std::io::Result<()> {
        match self {
            Self::Plain(socket) => socket.write_all(bytes).await,
            Self::Tls(stream) => stream.write_all(bytes).await,
        }
    }

    /// Flushes, so a command is on the wire before the reply is waited for.
    async fn flush(&mut self) -> std::io::Result<()> {
        match self {
            Self::Plain(socket) => socket.flush().await,
            Self::Tls(stream) => stream.flush().await,
        }
    }

    /// Reads one byte, which is what a reply reader needs to see the framing.
    async fn read_byte(&mut self, into: &mut [u8; 1]) -> std::io::Result<usize> {
        match self {
            Self::Plain(socket) => socket.read(into).await,
            Self::Tls(stream) => stream.read(into).await,
        }
    }
}

/// How the connection is secured.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Security {
    /// Plain to begin with, then `STARTTLS` — submission on 587.
    StartTls,
    /// TLS from the first byte — submission on 465.
    Implicit,
}

impl Security {
    /// Reads the operator's setting.
    ///
    /// @param value what the environment says
    /// @return the mode, or `None` when the value names neither
    pub fn parse(value: &str) -> Option<Self> {
        match value.trim().to_ascii_lowercase().as_str() {
            "starttls" => Some(Self::StartTls),
            "implicit" | "tls" | "smtps" => Some(Self::Implicit),
            _ => None,
        }
    }
}

/// What the operator configured about the mail server.
pub struct Server {
    /// Where it is.
    pub host: String,
    /// On which port.
    pub port: u16,
    /// How the connection is secured.
    pub security: Security,
    /// The account, or empty for a server that wants none.
    pub username: String,
    /// Its password.
    pub password: String,
    /// The name this client gives in `EHLO`.
    pub ehlo_name: String,
    /// How long the whole exchange may take.
    pub timeout: Duration,
}

/// Sends one message.
///
/// @param proxy `host:port` of the egress proxy, the one route out
/// @param server what the operator configured
/// @param sender the envelope sender
/// @param recipient the envelope recipient, already normalised
/// @param message the rendered message, headers and body
/// @return what the server said to the final dot, for the delivery log
/// @errors a sentence naming what went wrong, and never the message or the
///     password
pub async fn send(
    proxy: &str,
    server: &Server,
    sender: &str,
    recipient: &str,
    message: &str,
) -> Result<String, String> {
    tokio::time::timeout(
        server.timeout,
        exchange(proxy, server, sender, recipient, message),
    )
    .await
    .map_err(|_| {
        format!(
            "the mail server did not finish within {} seconds",
            server.timeout.as_secs()
        )
    })?
}

async fn exchange(
    proxy: &str,
    server: &Server,
    sender: &str,
    recipient: &str,
    message: &str,
) -> Result<String, String> {
    let tunnel =
        homeinv_plugin_common::proxy::connect(proxy, &server.host, server.port, server.timeout)
            .await?;

    let mut stream = match server.security {
        Security::Implicit => Connection::Tls(Box::new(upgrade(tunnel, &server.host).await?)),
        Security::StartTls => Connection::Plain(tunnel),
    };

    let greeting = read_reply(&mut stream).await?;
    expect(&greeting, 220, "the greeting")?;

    let mut capabilities = ehlo(&mut stream, &server.ehlo_name).await?;

    if server.security == Security::StartTls {
        if !capabilities
            .iter()
            .any(|line| line.eq_ignore_ascii_case("STARTTLS"))
        {
            return Err(
                "the mail server offers no STARTTLS. Nothing is sent: a submission that falls back \
                 to plaintext hands this deployment's credentials to anything on the path, and the \
                 mail still arrives, which is why nobody would notice."
                    .to_string(),
            );
        }
        let reply = command(&mut stream, "STARTTLS").await?;
        expect(&reply, 220, "STARTTLS")?;

        // The socket is consumed by the handshake, so the conversation continues
        // on the TLS stream -- and EHLO is asked again, because a server's
        // capabilities before and after TLS are allowed to differ and AUTH
        // usually appears only after.
        stream = match stream {
            Connection::Plain(socket) => {
                Connection::Tls(Box::new(upgrade(socket, &server.host).await?))
            }
            already => already,
        };
        capabilities = ehlo(&mut stream, &server.ehlo_name).await?;
    }

    if !server.username.is_empty() {
        authenticate(
            &mut stream,
            &capabilities,
            &server.username,
            &server.password,
        )
        .await?;
    }

    let reply = command(&mut stream, &format!("MAIL FROM:<{sender}>")).await?;
    expect(&reply, 250, "MAIL FROM")?;

    let reply = command(&mut stream, &format!("RCPT TO:<{recipient}>")).await?;
    // 251 is "not local, will forward", which is an acceptance.
    if !(reply.starts_with("250") || reply.starts_with("251")) {
        return Err(refusal("RCPT TO", &reply));
    }

    let reply = command(&mut stream, "DATA").await?;
    expect(&reply, 354, "DATA")?;

    for line in message.split("\r\n") {
        // Dot-stuffing, RFC 5321 §4.5.2: a line beginning with a full stop would
        // otherwise end the message here. Base64 bodies never do, and headers
        // are ours -- this is the belt for the braces.
        if let Some(rest) = line.strip_prefix('.') {
            stream
                .write_all(format!("..{rest}\r\n").as_bytes())
                .await
                .map_err(|failure| format!("the message could not be sent: {failure}"))?;
        } else {
            stream
                .write_all(format!("{line}\r\n").as_bytes())
                .await
                .map_err(|failure| format!("the message could not be sent: {failure}"))?;
        }
    }
    let accepted = command(&mut stream, ".").await?;
    expect(&accepted, 250, "the message")?;

    // QUIT is best-effort: the message is accepted by here, and a server that
    // hangs up first is not a failed delivery.
    let _ = command(&mut stream, "QUIT").await;

    Ok(accepted)
}

/// Says hello and collects what the server can do.
async fn ehlo(stream: &mut Connection, name: &str) -> Result<Vec<String>, String> {
    let reply = command(stream, &format!("EHLO {name}")).await?;
    expect(&reply, 250, "EHLO")?;
    Ok(reply
        .lines()
        .filter_map(|line| line.get(4..).map(|rest| rest.trim().to_string()))
        .collect())
}

/// Authenticates, preferring `PLAIN` and falling back to `LOGIN`.
///
/// Both send the password base64-encoded, which is an encoding and not a
/// protection — it is the TLS above them that protects it, which is why this is
/// only ever reached after the connection is secured.
async fn authenticate(
    stream: &mut Connection,
    capabilities: &[String],
    username: &str,
    password: &str,
) -> Result<(), String> {
    let mechanisms = capabilities
        .iter()
        .find(|line| line.to_ascii_uppercase().starts_with("AUTH"))
        .map(|line| line.to_ascii_uppercase())
        .unwrap_or_default();

    if mechanisms.contains("PLAIN") || mechanisms.is_empty() {
        let credential = base64(format!("\0{username}\0{password}").as_bytes());
        let reply = command(stream, &format!("AUTH PLAIN {credential}")).await?;
        if reply.starts_with("235") {
            return Ok(());
        }
        if !mechanisms.contains("LOGIN") {
            return Err(refusal("AUTH PLAIN", &reply));
        }
    }

    let reply = command(stream, "AUTH LOGIN").await?;
    expect(&reply, 334, "AUTH LOGIN")?;
    let reply = command(stream, &base64(username.as_bytes())).await?;
    expect(&reply, 334, "the user name")?;
    let reply = command(stream, &base64(password.as_bytes())).await?;
    if !reply.starts_with("235") {
        return Err(refusal("AUTH LOGIN", &reply));
    }
    Ok(())
}

/// Wraps a socket in TLS, verified against the public roots.
async fn upgrade(
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
        .map_err(|_| "the mail server's host is not a name TLS can verify".to_string())?;
    TlsConnector::from(Arc::new(config))
        .connect(name, socket)
        .await
        .map_err(|failure| format!("the mail server's TLS handshake failed: {failure}"))
}

/// Sends one command and reads one reply.
async fn command(stream: &mut Connection, line: &str) -> Result<String, String> {
    stream
        .write_all(format!("{line}\r\n").as_bytes())
        .await
        .map_err(|failure| format!("the command could not be sent: {failure}"))?;
    stream
        .flush()
        .await
        .map_err(|failure| format!("the command could not be flushed: {failure}"))?;
    read_reply(stream).await
}

/// Reads a reply, following the multiline form.
///
/// `250-CAPABILITY` continues and `250 CAPABILITY` ends, which is the one piece
/// of SMTP framing a reader has to know.
async fn read_reply(stream: &mut Connection) -> Result<String, String> {
    let mut reply = String::new();
    let mut byte = [0_u8; 1];
    loop {
        if reply.len() > 8 * 1024 {
            return Err("the mail server answered more than a reply".to_string());
        }
        let read = stream
            .read_byte(&mut byte)
            .await
            .map_err(|failure| format!("the mail server stopped answering: {failure}"))?;
        if read == 0 {
            break;
        }
        reply.push(byte[0] as char);
        if reply.ends_with("\r\n") {
            let last = reply.lines().next_back().unwrap_or("");
            // A four-character code followed by a space ends the reply; a hyphen
            // means another line follows.
            if last.len() >= 4 && last.as_bytes()[3] == b' ' {
                break;
            }
        }
    }
    if reply.is_empty() {
        return Err("the mail server closed the connection without answering".to_string());
    }
    Ok(reply.trim_end().to_string())
}

/// Whether a reply carries the expected code.
fn expect(reply: &str, code: u16, what: &str) -> Result<(), String> {
    if reply.starts_with(&code.to_string()) {
        Ok(())
    } else {
        Err(refusal(what, reply))
    }
}

/// A refusal, as the delivery log should carry it.
fn refusal(what: &str, reply: &str) -> String {
    // The server's own line, which is the useful part -- "550 5.1.1 no such
    // mailbox" tells an operator what to do and "the mail was not sent" does
    // not. Never the message, and never the password.
    format!(
        "the mail server refused {what}: {}",
        reply.replace(['\r', '\n'], " ")
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_security_mode_reads_what_an_operator_would_write() {
        assert_eq!(Security::parse("starttls"), Some(Security::StartTls));
        assert_eq!(Security::parse("STARTTLS"), Some(Security::StartTls));
        assert_eq!(Security::parse("implicit"), Some(Security::Implicit));
        assert_eq!(Security::parse("smtps"), Some(Security::Implicit));
        // Anything else is refused rather than guessed at: guessing here means
        // guessing whether the credentials travel in the clear.
        assert_eq!(Security::parse("none"), None);
        assert_eq!(Security::parse("plain"), None);
    }

    #[test]
    fn a_code_is_expected_or_the_reply_is_carried_into_the_refusal() {
        assert!(expect("250 OK", 250, "MAIL FROM").is_ok());
        let refused = expect("550 5.1.1 no such mailbox", 250, "RCPT TO").unwrap_err();
        assert!(refused.contains("550 5.1.1 no such mailbox"));
        assert!(refused.contains("RCPT TO"));
    }

    #[test]
    fn a_refusal_is_one_line_however_many_the_server_sent() {
        // A multiline refusal in a log record is a record that looks like
        // several, and the delivery log stores one string per attempt.
        let refused = refusal("DATA", "451-first\r\n451 second");
        assert!(!refused.contains('\n'));
        assert!(refused.contains("451-first"));
    }
}
