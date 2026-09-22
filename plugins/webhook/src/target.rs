// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Where a webhook may be sent, decided before anything is sent anywhere.
//!
//! The egress proxy is the enforcement point: a plugin reaches the hosts in its
//! manifest and nothing else, and no container runtime can express that rule
//! ([ADR-0027](../../../docs/adr/0027-egress-enforcement.md)). This is the
//! second line, in the plugin's own code, and it exists for the reason
//! `REQ-SEC-034` gives: the URL came from a person typing it into a form.
//!
//! What it refuses, and each is a sentence from that requirement:
//!
//! * anything that is not `https` — a webhook carrying a signed payload over
//!   plaintext is a payload somebody else can read on the way;
//! * a URL with no host, or one with credentials in it;
//! * a host that is a literal address in a private, loopback, link-local or
//!   carrier-grade-NAT range, which is how a URL from a form becomes a request
//!   to `169.254.169.254` and the deployment's own metadata endpoint.
//!
//! It does **not** resolve names: that is the proxy's job, done at the moment of
//! connection, where a name that resolves differently a second later cannot
//! slip between the check and the use.

/// A target that may be dialled.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Target {
    /// The host, as written.
    pub host: String,
    /// The port, 443 unless the URL said otherwise.
    pub port: u16,
    /// Everything after the authority, beginning with `/`.
    pub path: String,
}

/// Reads a target out of a URL a tenant configured.
///
/// # Errors
///
/// Returns the sentence to log and to hand back to the core. It names what was
/// wrong and never repeats the URL: a delivery log is read by people who are not
/// necessarily the ones who configured it.
pub fn parse(url: &str) -> Result<Target, String> {
    let rest = url
        .strip_prefix("https://")
        .ok_or_else(|| "a webhook target must be an https URL".to_string())?;

    let (authority, path) = match rest.find('/') {
        Some(index) => (&rest[..index], &rest[index..]),
        None => (rest, "/"),
    };
    if authority.is_empty() {
        return Err("the webhook target has no host".to_string());
    }
    if authority.contains('@') {
        // Credentials in a URL are sent on every request and end up in logs on
        // both sides. A receiver that needs authentication gets the signature.
        return Err("a webhook target may not carry credentials in its URL".to_string());
    }

    let (host, port) = match authority.rsplit_once(':') {
        Some((host, port)) if !host.contains(']') || port.chars().all(|c| c.is_ascii_digit()) => (
            host.to_string(),
            port.parse::<u16>()
                .map_err(|_| "the webhook target's port is not a number".to_string())?,
        ),
        _ => (authority.to_string(), 443_u16),
    };

    if host.is_empty() {
        return Err("the webhook target has no host".to_string());
    }
    if let Some(reason) = refused_address(&host) {
        return Err(reason);
    }

    Ok(Target {
        host,
        port,
        path: path.to_string(),
    })
}

/// Whether a literal address is one nothing outside may name.
///
/// Only literals: a name is the proxy's business, and checking one here would be
/// a check against an answer that can change before the connection is made.
fn refused_address(host: &str) -> Option<String> {
    let refused = |what: &str| Some(format!("a webhook target may not be {what}"));

    if host.eq_ignore_ascii_case("localhost") {
        return refused("localhost");
    }
    if host.starts_with('[') {
        // IPv6 literal. `::1` is loopback and `fc00::/7` is unique-local; both
        // are inside the deployment's world rather than outside it.
        let inner = host.trim_start_matches('[').trim_end_matches(']');
        if inner == "::1"
            || inner.starts_with("fc")
            || inner.starts_with("fd")
            || inner.starts_with("fe80")
        {
            return refused("an address inside the deployment");
        }
        return None;
    }

    let octets: Vec<&str> = host.split('.').collect();
    if octets.len() != 4
        || !octets
            .iter()
            .all(|part| part.chars().all(|c| c.is_ascii_digit()))
    {
        // A name. The proxy decides.
        return None;
    }
    let numbers: Vec<u16> = octets.iter().filter_map(|part| part.parse().ok()).collect();
    if numbers.len() != 4 {
        return None;
    }
    match (numbers[0], numbers[1]) {
        (10, _) => refused("an address in a private range"),
        (127, _) => refused("a loopback address"),
        (169, 254) => refused(
            "a link-local address, which is where a cloud provider's metadata endpoint lives",
        ),
        (172, second) if (16..=31).contains(&second) => refused("an address in a private range"),
        (192, 168) => refused("an address in a private range"),
        (100, second) if (64..=127).contains(&second) => {
            refused("an address in the carrier-grade NAT range")
        }
        (0, _) => refused("an unspecified address"),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_ordinary_target() {
        let target = parse("https://hooks.example.org/inventory").unwrap();
        assert_eq!(target.host, "hooks.example.org");
        assert_eq!(target.port, 443);
        assert_eq!(target.path, "/inventory");
    }

    #[test]
    fn a_target_without_a_path_gets_the_root() {
        assert_eq!(parse("https://hooks.example.org").unwrap().path, "/");
    }

    #[test]
    fn a_port_is_read_when_one_is_given() {
        let target = parse("https://hooks.example.org:8443/in").unwrap();
        assert_eq!(target.port, 8443);
    }

    #[test]
    fn plaintext_is_refused() {
        assert!(parse("http://hooks.example.org/in")
            .unwrap_err()
            .contains("https"));
    }

    #[test]
    fn credentials_in_a_url_are_refused() {
        assert!(parse("https://user:password@hooks.example.org/in")
            .unwrap_err()
            .contains("credentials"));
    }

    #[test]
    fn the_metadata_endpoint_is_refused() {
        // The one that matters: a URL from a form, pointed at the cloud
        // provider's metadata service, is how a webhook becomes credential theft.
        assert!(parse("https://169.254.169.254/latest/meta-data/")
            .unwrap_err()
            .contains("link-local"));
    }

    #[test]
    fn private_and_loopback_addresses_are_refused() {
        for url in [
            "https://10.0.0.5/in",
            "https://127.0.0.1/in",
            "https://172.16.4.4/in",
            "https://192.168.1.1/in",
            "https://100.64.0.1/in",
            "https://localhost/in",
            "https://[::1]/in",
        ] {
            assert!(parse(url).is_err(), "{url} should be refused");
        }
    }

    #[test]
    fn a_public_address_is_allowed_and_a_name_is_left_to_the_proxy() {
        assert!(parse("https://93.184.216.34/in").is_ok());
        assert!(parse("https://hooks.internal.example.org/in").is_ok());
    }

    #[test]
    fn a_refusal_never_repeats_the_url() {
        // A delivery log is read by people who did not configure the target.
        let message = parse("https://10.0.0.5/secret-path?token=abc").unwrap_err();
        assert!(!message.contains("secret-path"));
        assert!(!message.contains("token"));
    }
}
