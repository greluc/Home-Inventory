// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! The document a receiver gets, and the exact bytes that are signed.
//!
//! One shape, stable, documented in this plugin's `README`. A receiver writes
//! one parser and keeps it: a webhook whose payload changes shape between
//! releases is a webhook somebody stops trusting.
//!
//! # What is not in it
//!
//! **The attachment bytes.** The contract keeps attachments small and usually
//! empty, and a webhook is not a file transfer: what travels is the name, the
//! media type and the size, so a receiver knows something was attached and can
//! ask for it through the API if it wants it.
//!
//! # Why the JSON is written by hand
//!
//! Because the bytes that are signed have to be the bytes that are sent, and the
//! surest way to guarantee that is to build them once and hand the same slice to
//! the signature and to the socket. A serialiser that re-encodes on the way out
//! — key order, escaping, number formatting — is a signature that fails for
//! reasons nobody can see.

/// One attachment, as the payload describes it.
pub struct Attachment<'a> {
    /// What it is called.
    pub file_name: &'a str,
    /// What it is.
    pub media_type: &'a str,
    /// How large it is, in bytes.
    pub bytes: usize,
}

/// Builds the document for one notification.
///
/// # Arguments
///
/// * `idempotency_key` — the key the core deduplicates on, so a receiver can too
/// * `tenant` — which tenant the message is about, empty on an instance call
/// * `subject`, `text`, `html`, `language` — the message
/// * `attachments` — what travelled with it, described rather than carried
///
/// # Returns
///
/// The bytes to sign and to send, in that order and without re-encoding.
pub fn build(
    idempotency_key: &str,
    tenant: &str,
    subject: &str,
    text: &str,
    html: &str,
    language: &str,
    attachments: &[Attachment<'_>],
) -> Vec<u8> {
    let mut out = String::with_capacity(512 + text.len() + html.len());
    out.push('{');
    field(&mut out, "id", idempotency_key, true);
    field(&mut out, "tenantId", tenant, false);
    field(&mut out, "subject", subject, false);
    field(&mut out, "text", text, false);
    field(&mut out, "html", html, false);
    field(&mut out, "language", language, false);

    out.push_str(",\"attachments\":[");
    for (index, attachment) in attachments.iter().enumerate() {
        if index > 0 {
            out.push(',');
        }
        out.push('{');
        field(&mut out, "fileName", attachment.file_name, true);
        field(&mut out, "mediaType", attachment.media_type, false);
        out.push_str(",\"bytes\":");
        out.push_str(&attachment.bytes.to_string());
        out.push('}');
    }
    out.push_str("]}");
    out.into_bytes()
}

/// Appends one string field.
fn field(out: &mut String, name: &str, value: &str, first: bool) {
    if !first {
        out.push(',');
    }
    out.push('"');
    out.push_str(name);
    out.push_str("\":");
    escape(out, value);
}

/// Appends a JSON string, escaped as RFC 8259 requires.
///
/// Control characters are escaped rather than dropped: what arrives here has
/// already been through the core's own input handling, and a serialiser that
/// silently changes a value is a serialiser whose output no longer matches what
/// was signed.
fn escape(out: &mut String, value: &str) {
    out.push('"');
    for character in value.chars() {
        match character {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            '\u{08}' => out.push_str("\\b"),
            '\u{0c}' => out.push_str("\\f"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_shape_is_the_one_the_readme_publishes() {
        let body = build("key-1", "t-1", "A subject", "Some text", "", "en", &[]);
        assert_eq!(
            String::from_utf8(body).unwrap(),
            "{\"id\":\"key-1\",\"tenantId\":\"t-1\",\"subject\":\"A subject\",\
             \"text\":\"Some text\",\"html\":\"\",\"language\":\"en\",\"attachments\":[]}"
        );
    }

    #[test]
    fn an_attachment_is_described_and_not_carried() {
        let body = build(
            "key-2",
            "t-1",
            "",
            "",
            "",
            "de",
            &[Attachment {
                file_name: "receipt.pdf",
                media_type: "application/pdf",
                bytes: 4096,
            }],
        );
        let text = String::from_utf8(body).unwrap();
        assert!(text.contains("\"fileName\":\"receipt.pdf\""));
        assert!(text.contains("\"bytes\":4096"));
        // The bytes themselves are not in it, in any encoding.
        assert!(!text.contains("base64"));
        assert!(!text.contains("content"));
    }

    #[test]
    fn quotes_and_newlines_cannot_break_out_of_a_value() {
        let body = build("k", "t", "a \"quote\"", "line\nbreak", "", "en", &[]);
        let text = String::from_utf8(body).unwrap();
        assert!(text.contains("a \\\"quote\\\""));
        assert!(text.contains("line\\nbreak"));
        // And what was built is still one JSON object: the braces are balanced.
        assert_eq!(text.matches('{').count(), text.matches('}').count());
    }

    #[test]
    fn a_control_character_is_escaped_rather_than_dropped() {
        let body = build("k", "t", "bell\u{7}", "", "", "en", &[]);
        assert!(String::from_utf8(body).unwrap().contains("\\u0007"));
    }
}
