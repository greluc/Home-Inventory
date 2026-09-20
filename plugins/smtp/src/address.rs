// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! What counts as an address here, and what a refusal means.
//!
//! The core hands over whatever the subscription holds. Two shapes arrive in
//! practice — `person@example.org` and `mailto:person@example.org` — and both
//! mean the same thing, so both are accepted and normalised to the first.
//!
//! # Why the check is this narrow
//!
//! An address goes into `RCPT TO:<…>` and into a header, and both are line
//! protocols. A value containing a newline is two commands or two headers, which
//! is how a notification becomes a message somebody else wrote. So the rule is
//! not "is this a valid address" — RFC 5321 permits things no server accepts —
//! but "is this one value on one line, with exactly one `@` and something on
//! either side".
//!
//! Anything refused here is refused **permanently**: the core dead-letters it on
//! the first attempt rather than retrying, because "that is not an address" does
//! not become one by being repeated.

/// The address to put in `RCPT TO:`.
///
/// @param supplied what the subscription holds
/// @return the bare address
/// @errors a sentence for the delivery log, which never repeats the value: a log
///     line is read by people who are not its owner
pub fn normalise(supplied: &str) -> Result<String, String> {
    let trimmed = supplied.trim();
    let bare = trimmed.strip_prefix("mailto:").unwrap_or(trimmed).trim();

    if bare.is_empty() {
        return Err("the subscription holds no address".to_string());
    }
    if bare.len() > 320 {
        // 64 + 1 + 255, the longest address RFC 5321 allows.
        return Err("the address is longer than an address may be".to_string());
    }
    if bare.chars().any(|c| c.is_ascii_control() || c == ' ') {
        return Err(
            "the address contains a space or a control character, which would be a second command \
             or a second header rather than an address"
                .to_string(),
        );
    }
    if bare
        .chars()
        .any(|c| c == '<' || c == '>' || c == ',' || c == ';')
    {
        return Err("the address carries punctuation that belongs to the envelope".to_string());
    }
    match bare.split_once('@') {
        Some((local, domain))
            if !local.is_empty()
                && !domain.is_empty()
                && !domain.contains('@')
                && domain.contains('.') =>
        {
            Ok(bare.to_string())
        }
        _ => Err("the address is not one local part, one @ and one domain".to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_ordinary_address_passes_through() {
        assert_eq!(
            normalise("person@example.org").unwrap(),
            "person@example.org"
        );
    }

    #[test]
    fn a_mailto_url_is_the_same_address() {
        assert_eq!(
            normalise("mailto:person@example.org").unwrap(),
            "person@example.org"
        );
        assert_eq!(
            normalise("  person@example.org  ").unwrap(),
            "person@example.org"
        );
    }

    #[test]
    fn a_newline_is_refused_because_it_would_be_a_second_command() {
        // The one that matters: `RCPT TO:<a@b\r\nRCPT TO:<c@d>` is two
        // recipients, and the second is whoever wrote the subscription.
        assert!(normalise("person@example.org\r\nRCPT TO:<somebody@else.org>").is_err());
        assert!(normalise("person@example.org\nBcc: somebody@else.org").is_err());
    }

    #[test]
    fn the_envelope_punctuation_is_refused() {
        assert!(normalise("<person@example.org>").is_err());
        assert!(normalise("one@example.org, two@example.org").is_err());
    }

    #[test]
    fn something_that_is_not_an_address_is_refused() {
        assert!(normalise("").is_err());
        assert!(normalise("person").is_err());
        assert!(normalise("@example.org").is_err());
        assert!(normalise("person@").is_err());
        assert!(normalise("person@localhost").is_err());
        assert!(normalise("one@two@example.org").is_err());
    }

    #[test]
    fn a_refusal_never_repeats_the_address() {
        let message = normalise("private.person@example.org\r\nBcc: x@y.org").unwrap_err();
        assert!(!message.contains("private.person"));
    }
}
