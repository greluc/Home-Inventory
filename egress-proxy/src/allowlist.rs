// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! What may be reached, by whom, and what may never be.
//!
//! Three checks, and each answers a different question. **Who is asking** — the
//! segment the connection arrived on, which is one interface per plugin
//! ([ADR-0037](../../docs/adr/0037-per-plugin-network-segments.md)). **What they
//! asked for** — the name must be on the list that caller may use. **Where that
//! name leads** — every address it resolves to must be outside the ranges that
//! belong to the deployment, to the host or to the cloud provider.
//!
//! Any one of them alone is insufficient. A name check alone is defeated by a
//! DNS entry that answers `169.254.169.254`; an address check alone would let
//! any host on the internet through; and one shared name list would let a plugin
//! reach the hosts another plugin's manifest declared, which is the allowance
//! nobody consented to.

use std::net::IpAddr;

/// The hosts a caller may reach, read from the generated allowlist file.
///
/// Two halves. The **deployment** half is every line before the first section
/// header: hosts any caller may reach, which today is the ClamAV signature
/// mirror and nothing else ([ADR-0036](../../docs/adr/0036-scanner-egress.md)).
/// The **per-plugin** halves each name the network segment they apply to, so a
/// plugin reaches what its own manifest declared and nothing another one did.
///
/// The segment is identified by the address the connection arrived **on** — the
/// proxy's own interface on that segment — and not by any address the caller
/// claims. The proxy has one interface per segment (ADR-0037), so that address
/// is the question "which plugin is this" already answered by the kernel.
#[derive(Debug, Default, Clone)]
pub struct Allowlist {
    /// Hosts every caller may reach.
    shared: Vec<String>,
    /// One entry per plugin segment, in the order the file declares them.
    segments: Vec<Segment>,
}

/// One plugin's allowance.
#[derive(Debug, Clone)]
struct Segment {
    /// The network the proxy's interface on that segment sits in.
    network: Network,
    /// Which plugin it is, for the log line. It decides nothing.
    plugin: String,
    /// The hosts that plugin's manifest declared.
    hosts: Vec<String>,
}

/// An address range, as a section header writes it.
#[derive(Debug, Clone, Copy)]
struct Network {
    /// The base address.
    base: IpAddr,
    /// How many leading bits are fixed.
    prefix: u8,
}

impl Network {
    /// Reads `10.89.31.0/24` or `fd00:1::/64`.
    fn parse(text: &str) -> Option<Self> {
        let (address, prefix) = text.split_once('/')?;
        let base: IpAddr = address.parse().ok()?;
        let prefix: u8 = prefix.parse().ok()?;
        let width = if base.is_ipv4() { 32 } else { 128 };
        if prefix > width {
            return None;
        }
        Some(Self { base, prefix })
    }

    /// Whether an address is inside it.
    ///
    /// Compared over the raw octets, which is the same code for both families
    /// and avoids the integer-width branch entirely.
    fn contains(&self, address: IpAddr) -> bool {
        let (base, candidate) = match (self.base, address) {
            (IpAddr::V4(base), IpAddr::V4(other)) => {
                (base.octets().to_vec(), other.octets().to_vec())
            }
            (IpAddr::V6(base), IpAddr::V6(other)) => {
                (base.octets().to_vec(), other.octets().to_vec())
            }
            // An IPv4 address in IPv6 clothing is compared as what it is, so a
            // segment declared in IPv4 still recognises `::ffff:10.89.31.2`.
            (IpAddr::V4(base), IpAddr::V6(other)) => match other.to_ipv4_mapped() {
                Some(mapped) => (base.octets().to_vec(), mapped.octets().to_vec()),
                None => return false,
            },
            _ => return false,
        };

        let whole = (self.prefix / 8) as usize;
        if base[..whole] != candidate[..whole] {
            return false;
        }
        let remaining = self.prefix % 8;
        if remaining == 0 {
            return true;
        }
        let mask = 0xffu8 << (8 - remaining);
        base[whole] & mask == candidate[whole] & mask
    }
}

impl Allowlist {
    /// Parses the generated allowlist.
    ///
    /// One host per line, `#` starts a comment, blank lines are ignored, and a
    /// line of the shape `[<cidr> <plugin id>]` starts a section that applies to
    /// that segment alone. Deliberately not YAML or JSON: the file is generated
    /// from `deploy/services.yaml` and read once at start-up, and a parser is a
    /// dependency and a class of failure this does not need.
    ///
    /// A section header that cannot be read is **dropped together with its
    /// hosts**, and the caller that would have used them is refused. The other
    /// direction — treating an unreadable header as "applies to everyone" — is
    /// how a typo becomes an allowance.
    pub fn parse(text: &str) -> Self {
        let mut shared = Vec::new();
        let mut segments: Vec<Segment> = Vec::new();
        let mut current: Option<usize> = None;
        let mut dropped = false;

        for raw in text.lines() {
            let line = raw.split('#').next().unwrap_or("").trim();
            if line.is_empty() {
                continue;
            }
            if let Some(header) = line
                .strip_prefix('[')
                .and_then(|rest| rest.strip_suffix(']'))
            {
                let (network, plugin) = header
                    .split_once(char::is_whitespace)
                    .unwrap_or((header, ""));
                match Network::parse(network.trim()) {
                    Some(network) => {
                        segments.push(Segment {
                            network,
                            plugin: plugin.trim().to_string(),
                            hosts: Vec::new(),
                        });
                        current = Some(segments.len() - 1);
                    }
                    None => {
                        // Everything until the next header belongs to a section
                        // this cannot key, so none of it is allowed to anybody.
                        dropped = true;
                        current = None;
                    }
                }
                continue;
            }
            let host = line.to_ascii_lowercase();
            match current {
                Some(index) => segments[index].hosts.push(host),
                None if !dropped => shared.push(host),
                None => {}
            }
        }

        Self { shared, segments }
    }

    /// Whether the list has no entry at all.
    ///
    /// Worth asking separately: an empty list refuses everything, which is the
    /// right default and the wrong configuration. The caller says so at start-up
    /// rather than leaving an operator to discover it as a silent outage.
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// How many hosts are allowed, over every caller.
    pub fn len(&self) -> usize {
        self.shared.len()
            + self
                .segments
                .iter()
                .map(|segment| segment.hosts.len())
                .sum::<usize>()
    }

    /// Which plugin sits on the segment a connection arrived on.
    ///
    /// For the log line. `None` means the connection came from something that is
    /// not a plugin segment — an in-deployment service using the shared half.
    pub fn caller_on(&self, arrival: IpAddr) -> Option<&str> {
        self.segment_for(arrival)
            .map(|segment| segment.plugin.as_str())
    }

    /// Whether a host may be reached from the segment this connection arrived on.
    ///
    /// Exact match, case-insensitively, with a trailing dot ignored — `example.com.`
    /// and `example.com` are the same name and a client may send either. No
    /// wildcards: ADR-0027 allows a host, not a domain, and "any name under this
    /// domain" is an allowance nobody declared.
    ///
    /// A caller on a plugin segment gets the shared half **and** its own; a
    /// caller on any other gets the shared half alone. The shared half is the
    /// deployment's own, consented to by nobody and changed only by a change to
    /// this repository, so a plugin having it too costs nothing and removes a
    /// special case.
    pub fn permits(&self, host: &str, arrival: IpAddr) -> bool {
        let wanted = host.trim_end_matches('.').to_ascii_lowercase();
        if self.shared.iter().any(|allowed| allowed.as_str() == wanted) {
            return true;
        }
        self.segment_for(arrival)
            .map(|segment| {
                segment
                    .hosts
                    .iter()
                    .any(|allowed| allowed.as_str() == wanted)
            })
            .unwrap_or(false)
    }

    fn segment_for(&self, arrival: IpAddr) -> Option<&Segment> {
        self.segments
            .iter()
            .find(|segment| segment.network.contains(arrival))
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
        assert!(list.permits("database.clamav.net", INTERNAL));
    }

    #[test]
    fn matching_is_exact_and_case_insensitive() {
        let list = Allowlist::parse("database.clamav.net\n");
        assert!(list.permits("DATABASE.ClamAV.net", INTERNAL));
        assert!(list.permits("database.clamav.net.", INTERNAL));
        // No wildcards: a subdomain is a different host, and nobody declared it.
        assert!(!list.permits("evil.database.clamav.net", INTERNAL));
        assert!(!list.permits("clamav.net", INTERNAL));
        assert!(!list.permits("database.clamav.net.evil.example", INTERNAL));
    }

    #[test]
    fn an_empty_list_permits_nothing() {
        let list = Allowlist::parse("# nothing but a comment\n");
        assert!(list.is_empty());
        assert!(!list.permits("database.clamav.net", INTERNAL));
    }

    /// The address the deployment's own services arrive on, which is in no
    /// plugin's segment.
    const INTERNAL: IpAddr = IpAddr::V4(Ipv4Addr::new(10, 89, 1, 1));

    /// The proxy's own interface on one plugin's segment.
    const ON_WEBHOOK_SEGMENT: IpAddr = IpAddr::V4(Ipv4Addr::new(10, 89, 31, 1));

    /// And on another's.
    const ON_SMTP_SEGMENT: IpAddr = IpAddr::V4(Ipv4Addr::new(10, 89, 32, 1));

    fn two_plugins() -> Allowlist {
        Allowlist::parse(
            "database.clamav.net\n\n\
             [10.89.31.0/24 de.greluc.homeinv.plugin.webhook]\n\
             hooks.example.org\n\n\
             [10.89.32.0/24 de.greluc.homeinv.plugin.smtp]\n\
             mail.example.org\n",
        )
    }

    #[test]
    fn a_plugin_reaches_what_its_own_manifest_declared() {
        let list = two_plugins();
        assert!(list.permits("hooks.example.org", ON_WEBHOOK_SEGMENT));
        assert!(list.permits("mail.example.org", ON_SMTP_SEGMENT));
    }

    #[test]
    fn a_plugin_does_not_reach_what_another_plugin_declared() {
        // The allowance nobody consented to, and the reason one shared list was
        // not enough (ADR-0037).
        let list = two_plugins();
        assert!(!list.permits("mail.example.org", ON_WEBHOOK_SEGMENT));
        assert!(!list.permits("hooks.example.org", ON_SMTP_SEGMENT));
    }

    #[test]
    fn every_caller_reaches_the_deployment_half() {
        let list = two_plugins();
        assert!(list.permits("database.clamav.net", INTERNAL));
        assert!(list.permits("database.clamav.net", ON_WEBHOOK_SEGMENT));
    }

    #[test]
    fn a_caller_on_no_plugin_segment_gets_the_deployment_half_alone() {
        let list = two_plugins();
        assert!(!list.permits("hooks.example.org", INTERNAL));
    }

    #[test]
    fn the_segment_names_the_plugin_for_the_log_line() {
        let list = two_plugins();
        assert_eq!(
            list.caller_on(ON_WEBHOOK_SEGMENT),
            Some("de.greluc.homeinv.plugin.webhook")
        );
        assert_eq!(list.caller_on(INTERNAL), None);
    }

    #[test]
    fn a_header_that_cannot_be_read_takes_its_hosts_with_it() {
        // The other direction -- treating an unreadable header as "applies to
        // everyone" -- is how a typo becomes an allowance.
        let list = Allowlist::parse(
            "[not-a-network de.greluc.homeinv.plugin.webhook]\nhooks.example.org\n",
        );
        assert!(list.is_empty());
        assert!(!list.permits("hooks.example.org", ON_WEBHOOK_SEGMENT));
    }

    #[test]
    fn a_prefix_that_is_not_a_whole_number_of_octets_still_matches() {
        let list = Allowlist::parse("[10.89.28.0/22 p]\nhooks.example.org\n");
        assert!(list.permits(
            "hooks.example.org",
            IpAddr::V4(Ipv4Addr::new(10, 89, 30, 7))
        ));
        assert!(!list.permits(
            "hooks.example.org",
            IpAddr::V4(Ipv4Addr::new(10, 89, 32, 7))
        ));
    }

    #[test]
    fn an_ipv4_segment_recognises_an_ipv4_mapped_arrival() {
        // A dual-stack listener reports `::ffff:10.89.31.1` for a v4 connection,
        // and a plugin whose segment is declared in v4 is still that plugin.
        let list = two_plugins();
        assert!(list.permits("hooks.example.org", "::ffff:10.89.31.1".parse().unwrap()));
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
