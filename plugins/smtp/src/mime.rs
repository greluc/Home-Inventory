// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! The message itself: headers, parts, and the encoding that makes both safe.
//!
//! # Why every body is base64
//!
//! Because the alternative is three problems at once. A UTF-8 body needs
//! `8BITMIME` to travel as-is and not every submission server offers it; a long
//! line is folded by servers that then break a signature; and a line beginning
//! with a full stop ends the `DATA` command. Base64 removes all three — the
//! wrapped output is short ASCII lines, none of which can begin with a dot.
//!
//! # Why the date is computed here
//!
//! A message without a `Date:` is a message most filters score as spam, and the
//! header is defined by RFC 5322 in a calendar this crate has to produce from a
//! Unix timestamp. That is the civil-from-days algorithm below: twenty lines
//! against a date library, in a container budgeted at 32 MB.

use homeinv_plugin_common::encoding::base64_wrapped;
use homeinv_plugin_common::time::{civil_from_days, DAY};

/// The line length RFC 2045 asks of a base64 body.
const WRAP: usize = 76;

/// One thing sent with the message.
pub struct Attachment<'a> {
    /// What to call it.
    pub file_name: &'a str,
    /// What it is.
    pub media_type: &'a str,
    /// The bytes.
    pub content: &'a [u8],
}

/// Everything a message needs before it can be written.
pub struct Message<'a> {
    /// The envelope sender, which is also the `From:` address.
    pub sender: &'a str,
    /// What a person sees as the sender's name, or empty.
    pub sender_name: &'a str,
    /// Where it goes.
    pub recipient: &'a str,
    /// The subject, in the recipient's language.
    pub subject: &'a str,
    /// The plain-text body, always present.
    pub text: &'a str,
    /// The HTML body, or empty.
    pub html: &'a str,
    /// The recipient's language as an IETF tag, for `Content-Language`.
    pub language: &'a str,
    /// The core's idempotency key, which becomes the `Message-ID`.
    pub idempotency_key: &'a str,
    /// What travels with it.
    pub attachments: &'a [Attachment<'a>],
    /// Seconds since the epoch, for `Date:`.
    pub now: u64,
}

/// Renders the message as the bytes that go after `DATA`.
///
/// Lines are separated by CRLF, as the protocol requires, and dot-stuffing is
/// applied by the caller when it writes them — not here, because what is written
/// is what was built and the two must not disagree.
///
/// @param message what to send
/// @return the message, headers and body
pub fn render(message: &Message<'_>) -> String {
    let boundary = format!(
        "homeinv-{}-{}",
        message.now,
        stable_suffix(message.idempotency_key)
    );

    let mut out = String::with_capacity(1024 + message.text.len() + message.html.len());
    out.push_str(&header("From", &from(message)));
    out.push_str(&header("To", &format!("<{}>", message.recipient)));
    out.push_str(&header("Subject", &encoded_words(message.subject)));
    out.push_str(&header("Date", &rfc5322_date(message.now)));
    out.push_str(&header(
        "Message-ID",
        &format!(
            "<{}@{}>",
            message_id(message.idempotency_key),
            domain_of(message.sender)
        ),
    ));
    if !message.language.is_empty() {
        out.push_str(&header("Content-Language", message.language));
    }
    // What it is not: a reply, a list post, or anything a filter should treat as
    // bulk. `Auto-Submitted` is RFC 3834's way of saying "a machine sent this and
    // nothing should answer it", which is what stops a vacation responder from
    // writing back to a notification address.
    out.push_str(&header("Auto-Submitted", "auto-generated"));
    out.push_str(&header("MIME-Version", "1.0"));

    let body = if message.attachments.is_empty() {
        alternative_or_plain(message, &boundary)
    } else {
        mixed(message, &boundary)
    };
    out.push_str(&body);
    out
}

/// The body when nothing is attached: one part, or two alternatives.
fn alternative_or_plain(message: &Message<'_>, boundary: &str) -> String {
    if message.html.is_empty() {
        let mut out = String::new();
        out.push_str(&header("Content-Type", "text/plain; charset=utf-8"));
        out.push_str(&header("Content-Transfer-Encoding", "base64"));
        out.push_str("\r\n");
        out.push_str(&base64_wrapped(message.text.as_bytes(), WRAP));
        out.push_str("\r\n");
        return out;
    }

    let mut out = String::new();
    out.push_str(&header(
        "Content-Type",
        &format!("multipart/alternative; boundary=\"{boundary}\""),
    ));
    out.push_str("\r\n");
    out.push_str(&part(
        boundary,
        "text/plain; charset=utf-8",
        message.text.as_bytes(),
        None,
    ));
    out.push_str(&part(
        boundary,
        "text/html; charset=utf-8",
        message.html.as_bytes(),
        None,
    ));
    out.push_str(&format!("--{boundary}--\r\n"));
    out
}

/// The body when something is attached: the message, then each attachment.
fn mixed(message: &Message<'_>, boundary: &str) -> String {
    let inner = format!("{boundary}-alt");
    let mut out = String::new();
    out.push_str(&header(
        "Content-Type",
        &format!("multipart/mixed; boundary=\"{boundary}\""),
    ));
    out.push_str("\r\n");

    out.push_str(&format!("--{boundary}\r\n"));
    // The message itself, as one part of the mixture -- which is why its own
    // headers are written here rather than at the top.
    let text = alternative_or_plain(message, &inner);
    out.push_str(&text);

    for attachment in message.attachments {
        out.push_str(&part(
            boundary,
            attachment.media_type,
            attachment.content,
            Some(attachment.file_name),
        ));
    }
    out.push_str(&format!("--{boundary}--\r\n"));
    out
}

/// One MIME part, base64-encoded.
fn part(boundary: &str, media_type: &str, content: &[u8], file_name: Option<&str>) -> String {
    let mut out = format!("--{boundary}\r\n");
    out.push_str(&header("Content-Type", media_type));
    out.push_str(&header("Content-Transfer-Encoding", "base64"));
    if let Some(name) = file_name {
        // The name is encoded the same way a subject is: a receipt called
        // `Beleg Küche.pdf` is an ordinary name and must survive the journey.
        out.push_str(&header(
            "Content-Disposition",
            &format!("attachment; filename=\"{}\"", encoded_words(name)),
        ));
    }
    out.push_str("\r\n");
    out.push_str(&base64_wrapped(content, WRAP));
    out.push_str("\r\n");
    out
}

/// One header line, CRLF-terminated.
fn header(name: &str, value: &str) -> String {
    format!("{name}: {value}\r\n")
}

/// The `From:` value, with a display name when the tenant configured one.
fn from(message: &Message<'_>) -> String {
    if message.sender_name.is_empty() {
        format!("<{}>", message.sender)
    } else {
        format!(
            "{} <{}>",
            encoded_words(message.sender_name),
            message.sender
        )
    }
}

/// A header value, encoded as RFC 2047 when it is not plain ASCII.
///
/// Left alone when it is: an ASCII subject encoded anyway is a subject some
/// clients show as the encoding.
fn encoded_words(value: &str) -> String {
    if value.is_ascii() && !value.contains(['\r', '\n']) {
        return value.to_string();
    }
    format!(
        "=?UTF-8?B?{}?=",
        homeinv_plugin_common::encoding::base64(value.as_bytes())
    )
}

/// The local part of the `Message-ID`, derived from the idempotency key.
///
/// Derived rather than random, so that a retry of the same message carries the
/// same id: a receiver that deduplicates on it then sees one message, which is
/// the property the contract asks every channel for.
fn message_id(key: &str) -> String {
    let cleaned: String = key
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '.')
        .collect();
    if cleaned.is_empty() {
        "homeinv".to_string()
    } else {
        cleaned
    }
}

/// The domain of an address, for the `Message-ID`.
fn domain_of(address: &str) -> String {
    address
        .rsplit_once('@')
        .map(|(_, domain)| domain.to_string())
        .unwrap_or_else(|| "localhost".to_string())
}

/// A short stable suffix, so a boundary cannot collide with the body.
fn stable_suffix(key: &str) -> String {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for byte in key.as_bytes() {
        hash ^= *byte as u64;
        hash = hash.wrapping_mul(0x1000_0000_01b3);
    }
    format!("{hash:016x}")
}

/// The `Date:` header, RFC 5322, always in UTC.
///
/// UTC rather than a local zone: this container has no time zone database and
/// does not need one — `+0000` is a correct offset and the recipient's client
/// shows it in their own zone anyway.
pub fn rfc5322_date(seconds: u64) -> String {
    const DAYS: [&str; 7] = ["Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed"];
    const MONTHS: [&str; 12] = [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ];

    let days = (seconds / DAY) as i64;
    let time = seconds % DAY;
    let (year, month, day) = civil_from_days(days);
    // 1970-01-01 was a Thursday, which is why the table starts there.
    let weekday = DAYS[(days.rem_euclid(7)) as usize];

    format!(
        "{}, {} {} {} {:02}:{:02}:{:02} +0000",
        weekday,
        day,
        MONTHS[(month - 1) as usize],
        year,
        time / 3600,
        (time % 3600) / 60,
        time % 60
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn message<'a>(text: &'a str, html: &'a str, attachments: &'a [Attachment<'a>]) -> Message<'a> {
        Message {
            sender: "inventory@example.org",
            sender_name: "",
            recipient: "person@example.org",
            subject: "A warranty is running out",
            text,
            html,
            language: "en",
            idempotency_key: "key-1",
            attachments,
            now: 1_700_000_000,
        }
    }

    #[test]
    fn a_plain_message_is_one_base64_part() {
        let rendered = render(&message("The warranty ends on Friday.", "", &[]));
        assert!(rendered.contains("Content-Type: text/plain; charset=utf-8\r\n"));
        assert!(rendered.contains("Content-Transfer-Encoding: base64\r\n"));
        assert!(!rendered.contains("multipart"));
        // The body travels encoded, so no line of it can begin with a full stop
        // and end the DATA command.
        assert!(!rendered.contains("The warranty ends on Friday."));
    }

    #[test]
    fn a_message_with_html_carries_both_as_alternatives() {
        let rendered = render(&message("text", "<p>text</p>", &[]));
        assert!(rendered.contains("multipart/alternative"));
        assert!(rendered.contains("text/plain; charset=utf-8"));
        assert!(rendered.contains("text/html; charset=utf-8"));
    }

    #[test]
    fn an_attachment_makes_it_mixed_and_keeps_its_name() {
        let content = b"%PDF-1.7";
        let attachments = [Attachment {
            file_name: "receipt.pdf",
            media_type: "application/pdf",
            content,
        }];
        let rendered = render(&message("text", "", &attachments));
        assert!(rendered.contains("multipart/mixed"));
        assert!(rendered.contains("Content-Disposition: attachment; filename=\"receipt.pdf\""));
        assert!(rendered.contains("application/pdf"));
    }

    #[test]
    fn a_subject_that_is_not_ascii_is_encoded_and_one_that_is_stays_readable() {
        let mut plain = message("text", "", &[]);
        plain.subject = "A warranty is running out";
        assert!(render(&plain).contains("Subject: A warranty is running out\r\n"));

        let mut german = message("text", "", &[]);
        german.subject = "Garantie läuft ab";
        let rendered = render(&german);
        assert!(rendered.contains("Subject: =?UTF-8?B?"));
        assert!(!rendered.contains("Garantie läuft ab"));
    }

    #[test]
    fn the_message_id_follows_the_idempotency_key() {
        // A retry carries the same id, so a receiver that deduplicates on it
        // sees one message.
        let rendered = render(&message("text", "", &[]));
        assert!(rendered.contains("Message-ID: <key-1@example.org>"));
    }

    #[test]
    fn it_says_a_machine_sent_it() {
        // RFC 3834. Without it a vacation responder writes back to the
        // notification address, and every reply is a new notification.
        assert!(render(&message("text", "", &[])).contains("Auto-Submitted: auto-generated"));
    }

    #[test]
    fn the_date_is_rfc_5322_in_utc() {
        // 1700000000 is 2023-11-14T22:13:20Z, a Tuesday.
        assert_eq!(
            rfc5322_date(1_700_000_000),
            "Tue, 14 Nov 2023 22:13:20 +0000"
        );
        // The epoch itself, which was a Thursday.
        assert_eq!(rfc5322_date(0), "Thu, 1 Jan 1970 00:00:00 +0000");
        // A leap day, because February is where a hand-written calendar breaks.
        assert_eq!(
            rfc5322_date(1_709_164_800),
            "Thu, 29 Feb 2024 00:00:00 +0000"
        );
    }

    #[test]
    fn every_header_line_ends_with_crlf() {
        // A bare LF in a header is a message some servers reject and others
        // silently truncate at that point.
        let rendered = render(&message("text", "", &[]));
        for line in rendered.split("\r\n") {
            assert!(!line.contains('\n'), "a bare newline survived: {line:?}");
        }
    }
}
