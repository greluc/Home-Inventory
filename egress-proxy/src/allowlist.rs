// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! What may be reached, and what may never be.
//!
//! Two independent checks, and both have to pass. The **name** must be on the
//! allowlist, and every **address** it resolves to must be outside the ranges
//! that belong to the deployment, to the host or to the cloud provider. Either
//! one alone is insufficient: a name check alone is defeated by a DNS entry that
//! answers `169.254.169.254`, and an address check alone would let any host on
//! the internet through.

use std::net::IpAddr;

/// The hosts a caller may reach, read from the generated allowlist file.
///
/// One flat set at stage 0. Per-plugin allowances arrive with the plugin runtime
/// in stage 1 (ADR-0028) and are keyed by the segment interface a connection
/// arrived on (ADR-0037), which is why the caller is already carried through the
/// log lines here rather than added later.
#[derive(Debug, Default, Clone)]
pub struct Allowlist {
    hosts: Vec<String>,
}

impl Allowlist {
    /// Parses the generated allowlist.
    ///
    /// The format is one host per line, `#` starts a comment, blank lines are
    /// ignored. Deliberately not YAML or JSON: the file is generated from
    /// `deploy/services.yaml` and read once at start-up, and a parser is a
    /// dependency and a class of failure this does not need.
    pub fn parse(text: &str) -> Self {
        let hosts = text
            .lines()
            .map(|line| line.split('#').next().unwrap_or("").trim())
            .filter(|line| !line.is_empty())
            .map(|line| line.to_ascii_lowercase())
            .collect();
        Self { hosts }
    }

    /// Whether the list has no entry at all.
    ///
    /// Worth asking separately: an empty list refuses everything, which is the
    /// right default and the wrong configuration. The caller says so at start-up
    /// rather than leaving an operator to discover it as a silent outage.
    pub fn is_empty(&self) -> bool {
        self.hosts.is_empty()
    }

    /// How many hosts are allowed.
    pub fn len(&self) -> usize {
        self.hosts.len()
    }

    /// Whether a host is on the list.
    ///
    /// Exact match, case-insensitively, with a trailing dot ignored — `example.com.`
    /// and `example.com` are the same name and a client may send either. No
    /// wildcards: ADR-0027 allows a host, not a domain, and "any name under this
    /// domain" is an allowance nobody declared.
    pub fn permits(&self, host: &str) -> bool {
        let wanted = host.trim_end_matches('.').to_ascii_lowercase();
        self.hosts.iter().any(|allowed| allowed.as_str() == wanted)
    }
}

/// Whether an address is one this proxy must never connect to.
///
/// The ranges are the ones REQ-SEC-034 names — private, link-local and cloud
/// metadata — plus loopback, which is this container's own network namespace and
/// therefore its own listener.
///
/// The metadata endpoint is inside the link-local range on every cloud there is
/// (`169.254.169.254` on AWS, GCP, Azure and DigitalOcean alike), so refusing
/// link-local refuses it. It is named in the tests anyway: the reason this rule
/// exists is worth keeping visible.
pub fn is_forbidden(address: IpAddr) -> bool {
    match address {
        IpAddr::V4(v4) => {
            v4.is_private()
                || v4.is_loopback()
                || v4.is_link_local()
                || v4.is_broadcast()
                || v4.is_documentation()
                || v4.is_unspecified()
                // 100.64.0.0/10, carrier-grade NAT. Not private by the letter of
                // `is_private`, and not somewhere a deployment reaches out to.
                || (v4.octets()[0] == 100 && (64..128).contains(&v4.octets()[1]))
                // 0.0.0.0/8 — "this network". Routable nowhere, and on some
                // stacks a synonym for the local host.
                || v4.octets()[0] == 0
        }
        IpAddr::V6(v6) => {
            v6.is_loopback()
                || v6.is_unspecified()
                // fe80::/10 link-local and fc00::/7 unique-local. `is_unicast_link_local`
                // and `is_unique_local` are still unstable, so the prefixes are
                // matched by hand rather than waiting for them.
                || (v6.segments()[0] & 0xffc0) == 0xfe80
                || (v6.segments()[0] & 0xfe00) == 0xfc00
                // An IPv4 address wearing an IPv6 coat. Without this, `::ffff:10.0.0.1`
                // walks past every rule above it.
                || v6.to_ipv4_mapped().map(|v4| is_forbidden(IpAddr::V4(v4))).unwrap_or(false)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{Ipv4Addr, Ipv6Addr};

    #[test]
    fn comments_and_blank_lines_are_ignored() {
        let list = Allowlist::parse("# a comment\n\ndatabase.clamav.net  # the mirror\n");
        assert_eq!(list.len(), 1);
        assert!(list.permits("database.clamav.net"));
    }

    #[test]
    fn matching_is_exact_and_case_insensitive() {
        let list = Allowlist::parse("database.clamav.net\n");
        assert!(list.permits("DATABASE.ClamAV.net"));
        assert!(list.permits("database.clamav.net."));
        // No wildcards: a subdomain is a different host, and nobody declared it.
        assert!(!list.permits("evil.database.clamav.net"));
        assert!(!list.permits("clamav.net"));
        assert!(!list.permits("database.clamav.net.evil.example"));
    }

    #[test]
    fn an_empty_list_permits_nothing() {
        let list = Allowlist::parse("# nothing but a comment\n");
        assert!(list.is_empty());
        assert!(!list.permits("database.clamav.net"));
    }

    #[test]
    fn the_ranges_that_are_never_reached() {
        for forbidden in [
            "127.0.0.1",
            "10.1.2.3",
            "172.16.0.1",
            "192.168.1.1",
            "169.254.169.254", // the cloud metadata endpoint
            "100.64.0.1",
            "0.0.0.0",
        ] {
            assert!(
                is_forbidden(forbidden.parse().unwrap()),
                "{forbidden} must be refused"
            );
        }
        assert!(is_forbidden(IpAddr::V6(Ipv6Addr::LOCALHOST)));
        assert!(is_forbidden("fe80::1".parse().unwrap()));
        assert!(is_forbidden("fd00::1".parse().unwrap()));
        // The trap this rule exists for: an IPv4 private address in IPv6 clothing.
        assert!(is_forbidden("::ffff:10.0.0.1".parse().unwrap()));
    }

    #[test]
    fn a_public_address_is_reachable() {
        assert!(!is_forbidden(IpAddr::V4(Ipv4Addr::new(104, 16, 0, 1))));
        assert!(!is_forbidden("2606:4700::1".parse().unwrap()));
    }
}
