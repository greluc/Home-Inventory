// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Reading just enough of a request to know where it wants to go.
//!
//! A forward proxy sees two shapes and this reads both:
//!
//! ```text
//! CONNECT database.clamav.net:443 HTTP/1.1      -> a tunnel
//! GET http://database.clamav.net/daily.cvd HTTP/1.1  -> a plain fetch
//! ```
//!
//! Nothing else is parsed. The headers are read to find where the request line
//! ends and are otherwise passed through untouched — this proxy does not
//! terminate TLS and has no business rewriting what it forwards
//! ([ADR-0027](../../docs/adr/0027-egress-enforcement.md)).

/// Where a request wants to go, and how.
#[derive(Debug, PartialEq, Eq)]
pub enum Destination {
    /// `CONNECT host:port` — a tunnel, which is every HTTPS target.
    Tunnel { host: String, port: u16 },
    /// An absolute-form request, which is how a plain HTTP fetch reaches a proxy.
    ///
    /// `freshclam` is the one caller at stage 0 and it uses this shape when the
    /// mirror is reached over HTTP; over HTTPS it uses `CONNECT`. Supporting both
    /// is a handful of lines and removes an entire class of "it works here and
    /// not there".
    Fetch {
        host: String,
        port: u16,
        /// The request line, rewritten to the origin form a server expects.
        rewritten: String,
    },
}

/// Parses the first line of a proxied request.
///
/// Returns `None` for anything that is not one of the two shapes above, which is
/// answered with `400` rather than guessed at.
pub fn parse(line: &str) -> Option<Destination> {
    let mut parts = line.split_whitespace();
    let method = parts.next()?;
    let target = parts.next()?;
    let version = parts.next()?;
    if !version.starts_with("HTTP/") {
        return None;
    }

    if method.eq_ignore_ascii_case("CONNECT") {
        let (host, port) = split_authority(target, 443)?;
        return Some(Destination::Tunnel { host, port });
    }

    // Absolute form: scheme://host[:port]/path
    let rest = target
        .strip_prefix("http://")
        .or_else(|| target.strip_prefix("HTTP://"))?;
    let (authority, path) = match rest.find('/') {
        Some(index) => (&rest[..index], &rest[index..]),
        None => (rest, "/"),
    };
    let (host, port) = split_authority(authority, 80)?;
    Some(Destination::Fetch {
        host,
        port,
        rewritten: format!("{method} {path} {version}\r\n"),
    })
}

/// Splits `host:port`, or `host` with a default.
///
/// A bracketed IPv6 literal is handled because a client may send one; this proxy
/// refuses to connect to one later anyway if it is in a forbidden range, which is
/// where that decision belongs.
fn split_authority(authority: &str, default_port: u16) -> Option<(String, u16)> {
    if let Some(rest) = authority.strip_prefix('[') {
        let (host, tail) = rest.split_once(']')?;
        let port = match tail.strip_prefix(':') {
            Some(text) => text.parse().ok()?,
            None => default_port,
        };
        return Some((host.to_string(), port));
    }

    match authority.rsplit_once(':') {
        Some((host, port)) if !host.is_empty() => Some((host.to_string(), port.parse().ok()?)),
        _ if !authority.is_empty() => Some((authority.to_string(), default_port)),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_tunnel_is_read() {
        assert_eq!(
            parse("CONNECT database.clamav.net:443 HTTP/1.1"),
            Some(Destination::Tunnel {
                host: "database.clamav.net".into(),
                port: 443
            })
        );
    }

    #[test]
    fn a_tunnel_without_a_port_defaults_to_https() {
        assert_eq!(
            parse("CONNECT database.clamav.net HTTP/1.1"),
            Some(Destination::Tunnel {
                host: "database.clamav.net".into(),
                port: 443
            })
        );
    }

    #[test]
    fn a_plain_fetch_is_rewritten_to_the_origin_form() {
        assert_eq!(
            parse("GET http://database.clamav.net/daily.cvd HTTP/1.1"),
            Some(Destination::Fetch {
                host: "database.clamav.net".into(),
                port: 80,
                rewritten: "GET /daily.cvd HTTP/1.1\r\n".into()
            })
        );
    }

    #[test]
    fn a_fetch_with_no_path_gets_one() {
        assert_eq!(
            parse("GET http://database.clamav.net HTTP/1.1"),
            Some(Destination::Fetch {
                host: "database.clamav.net".into(),
                port: 80,
                rewritten: "GET / HTTP/1.1\r\n".into()
            })
        );
    }

    #[test]
    fn a_bracketed_literal_is_read() {
        assert_eq!(
            parse("CONNECT [2606:4700::1]:8443 HTTP/1.1"),
            Some(Destination::Tunnel {
                host: "2606:4700::1".into(),
                port: 8443
            })
        );
    }

    #[test]
    fn anything_else_is_refused_rather_than_guessed_at() {
        // An origin-form request: somebody is talking to this as if it were the
        // server rather than the proxy.
        assert_eq!(parse("GET /daily.cvd HTTP/1.1"), None);
        // https:// absolute form is not a thing a proxy is sent; CONNECT is.
        assert_eq!(parse("GET https://database.clamav.net/x HTTP/1.1"), None);
        assert_eq!(parse("CONNECT database.clamav.net:443 SSH-2.0"), None);
        assert_eq!(parse(""), None);
        assert_eq!(parse("CONNECT"), None);
    }
}
