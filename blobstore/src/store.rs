// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! The filesystem layout, and the rules that keep a caller from leaving it.
//!
//! Everything in this module exists because the two path components come from a
//! network peer. The peer is authenticated by mTLS and is `api` or `worker` —
//! but `internal` is not a trust boundary (ADR-0044), and a path built from
//! remote input is a path traversal unless something says otherwise. This is
//! that something.

use std::path::{Path, PathBuf};

/// Why a blob reference was refused.
#[derive(Debug, PartialEq, Eq)]
pub enum RefError {
    /// The tenant is not a UUID in canonical hyphenated form.
    TenantNotAUuid,
    /// The digest is not 64 lowercase hex characters.
    DigestNotSha256,
}

impl RefError {
    /// The message a caller is given.
    ///
    /// It names the field and the expected shape and nothing else. The value is
    /// not echoed: it came from a peer, and a message repeating it back is a
    /// message that carries whatever the peer put in it into our logs.
    pub fn message(&self) -> &'static str {
        match self {
            RefError::TenantNotAUuid => "tenant_id must be a UUID in canonical hyphenated form",
            RefError::DigestNotSha256 => "sha256 must be 64 lowercase hexadecimal characters",
        }
    }
}

/// A validated blob reference.
///
/// It can only be constructed through [`BlobRef::parse`], so every value of this
/// type is one whose components have already been checked. That is the point:
/// the check cannot be forgotten at a call site, because there is no other way
/// to make one.
#[derive(Debug, Clone)]
pub struct BlobRef {
    tenant_id: String,
    sha256: String,
}

impl BlobRef {
    /// Validates both components.
    ///
    /// No path traversal is possible through a value that passes: a canonical
    /// UUID and 64 hex characters contain no separator, no `.` and no `..`, so
    /// the result of joining them onto the root is always inside it. Rejecting
    /// on shape rather than sanitising is deliberate — a sanitiser turns a
    /// hostile path into a valid one and stores the file somewhere nobody meant.
    ///
    /// # Errors
    ///
    /// Returns [`RefError`] when either component is not of the required shape.
    pub fn parse(tenant_id: &str, sha256: &str) -> Result<Self, RefError> {
        if uuid::Uuid::try_parse(tenant_id).is_err() || tenant_id.len() != 36 {
            // `try_parse` accepts the braced and urn forms and the unhyphenated
            // one; the layout in ADR-0032 is the canonical hyphenated form, and
            // accepting a second spelling would store one tenant's blobs under
            // two directories.
            return Err(RefError::TenantNotAUuid);
        }
        if sha256.len() != 64
            || !sha256
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            return Err(RefError::DigestNotSha256);
        }
        Ok(Self {
            tenant_id: tenant_id.to_owned(),
            sha256: sha256.to_owned(),
        })
    }

    /// The digest, as text.
    pub fn sha256(&self) -> &str {
        &self.sha256
    }

    /// Where this blob lives under a root.
    ///
    /// `sha256/<tenantId>/<aa>/<bb>/<hash>` — the layout of ADR-0032 with two
    /// levels of fan-out. The fan-out is not decoration: a tenant with a hundred
    /// thousand photographs would otherwise have a hundred thousand entries in
    /// one directory, which several filesystems handle badly and every `ls`
    /// handles worse.
    pub fn path_under(&self, root: &Path) -> PathBuf {
        root.join("sha256")
            .join(&self.tenant_id)
            .join(&self.sha256[0..2])
            .join(&self.sha256[2..4])
            .join(&self.sha256)
    }
}

/// Where a half-arrived upload lives (`REQ-MED-008`).
///
/// The same rules as [`BlobRef`] and for the same reason: both components come
/// from a network peer, and a path built from remote input is a path traversal
/// unless something refuses the shapes that could be one. The difference is
/// what the second component is — an upload id rather than a digest, because a
/// half-arrived file has no content address yet.
#[derive(Debug, Clone)]
pub struct StagedRef {
    tenant_id: String,
    upload_id: String,
}

impl StagedRef {
    /// Validates both components as canonical UUIDs.
    ///
    /// # Errors
    ///
    /// Returns [`RefError::TenantNotAUuid`] when either is not a UUID in
    /// canonical hyphenated form. Both are the same kind of value, so they
    /// share the error: the message names the shape, which is what a caller
    /// needs, and neither value is echoed back.
    pub fn parse(tenant_id: &str, upload_id: &str) -> Result<Self, RefError> {
        for component in [tenant_id, upload_id] {
            if uuid::Uuid::try_parse(component).is_err() || component.len() != 36 {
                return Err(RefError::TenantNotAUuid);
            }
        }
        Ok(Self {
            tenant_id: tenant_id.to_owned(),
            upload_id: upload_id.to_owned(),
        })
    }

    /// Where this upload lives under a root.
    ///
    /// `staged/<tenantId>/<uploadId>` — a directory of its own, beside
    /// `sha256/` and never inside it. Two reasons: an unfinished file must
    /// never be reachable at an address `Head` would report as a stored blob,
    /// and a sweep that removes abandoned uploads can then be a sweep over one
    /// directory rather than a filter over all of them.
    ///
    /// No fan-out, unlike a blob: uploads in flight are a handful at a time,
    /// and the directory empties itself as they complete.
    pub fn path_under(&self, root: &Path) -> PathBuf {
        root.join("staged")
            .join(&self.tenant_id)
            .join(&self.upload_id)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const TENANT: &str = "0192f2a0-1b2c-7d3e-8f40-5a6b7c8d9e0f";
    const DIGEST: &str = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    #[test]
    fn accepts_a_canonical_reference() {
        let reference = BlobRef::parse(TENANT, DIGEST).expect("valid");
        let path = reference.path_under(Path::new("/var/lib/homeinv/blobs"));

        // Asserted on the components rather than on the rendered string: the
        // separator differs per platform, and the layout is the property under
        // test, not how this machine happens to spell a path.
        let components: Vec<String> = path
            .components()
            .map(|component| component.as_os_str().to_string_lossy().into_owned())
            .collect();
        let tail = &components[components.len() - 5..];
        assert_eq!(tail, ["sha256", TENANT, "e3", "b0", DIGEST]);
    }

    #[test]
    fn refuses_a_traversal_in_either_component() {
        // The whole reason this module exists. Neither component may contain a
        // separator, and neither shape admits one.
        assert_eq!(
            BlobRef::parse("../../etc", DIGEST).unwrap_err(),
            RefError::TenantNotAUuid
        );
        assert_eq!(
            BlobRef::parse(TENANT, "../../../etc/passwd").unwrap_err(),
            RefError::DigestNotSha256
        );
    }

    #[test]
    fn refuses_an_uppercase_digest() {
        // Not pedantry: `AB` and `ab` are the same digest and two directories,
        // so the same blob would be stored twice and deduplication would
        // silently stop working.
        assert_eq!(
            BlobRef::parse(TENANT, &DIGEST.to_uppercase()).unwrap_err(),
            RefError::DigestNotSha256
        );
    }

    #[test]
    fn refuses_an_unhyphenated_tenant() {
        // `try_parse` would accept it. The layout would then have two
        // directories for one tenant, and a blob written under one spelling
        // would be invisible under the other.
        assert_eq!(
            BlobRef::parse(&TENANT.replace('-', ""), DIGEST).unwrap_err(),
            RefError::TenantNotAUuid
        );
    }

    #[test]
    fn a_staged_upload_lives_beside_the_blobs_and_not_among_them() {
        // An unfinished file at an address `Head` answers for would be a blob
        // that is not all there, served as though it were.
        let staged =
            StagedRef::parse(TENANT, "0192f2a0-1b2c-7d3e-8f40-000000000001").expect("valid");
        let path = staged.path_under(Path::new("/var/lib/homeinv/blobs"));
        let components: Vec<String> = path
            .components()
            .map(|component| component.as_os_str().to_string_lossy().into_owned())
            .collect();

        let tail = &components[components.len() - 3..];
        assert_eq!(
            tail,
            ["staged", TENANT, "0192f2a0-1b2c-7d3e-8f40-000000000001"]
        );
    }

    #[test]
    fn a_staged_reference_refuses_a_traversal_in_either_component() {
        assert_eq!(
            StagedRef::parse("../../etc", TENANT).unwrap_err(),
            RefError::TenantNotAUuid
        );
        assert_eq!(
            StagedRef::parse(TENANT, "../../etc/passwd").unwrap_err(),
            RefError::TenantNotAUuid
        );
    }

    #[test]
    fn refuses_a_digest_of_the_wrong_length() {
        assert_eq!(
            BlobRef::parse(TENANT, &DIGEST[..63]).unwrap_err(),
            RefError::DigestNotSha256
        );
        assert_eq!(
            BlobRef::parse(TENANT, &format!("{DIGEST}0")).unwrap_err(),
            RefError::DigestNotSha256
        );
    }
}
