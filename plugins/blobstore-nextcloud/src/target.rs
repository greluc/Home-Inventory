// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Whose Nextcloud, and as whom — the two-level configuration of ADR-0073.
//!
//! | | Who decides | Where it lives |
//! |---|---|---|
//! | the deployment's instance, account and app password | the operator | this container's environment and a mounted secret |
//! | a tenant's **own** instance, account, app password and folder | that tenant | the call envelope, the secret sealed in the core |
//!
//! The same split as `plugins/blobstore-s3/`, and for the same reason: an
//! operator who runs one Nextcloud for the installation configures it once, and
//! a tenant who has its own account puts its photographs there instead.
//!
//! # Credentials are a pair
//!
//! `username` and `appPassword` are taken together or not at all. Half a pair
//! would authenticate as one party with the other's password, and the answer is
//! a `401` that says nothing about which half was wrong.
//!
//! # An app password, never the account password
//!
//! Nextcloud issues app passwords per application, they carry no web session,
//! and revoking one revokes exactly this integration. A deployment that held a
//! person's account password would hold the key to their whole Nextcloud, which
//! is not what storing photographs needs.

use std::collections::HashMap;

use homeinv_plugin_common::encoding::base64;

/// The default port when a URL names none. TLS, always.
const DEFAULT_PORT: u16 = 443;

/// Where the files of one account live, as every Nextcloud spells it.
const FILES_ROOT: &str = "remote.php/dav/files";

/// Where an unfinished chunked upload lives, likewise.
const UPLOAD_ROOT: &str = "remote.php/dav/uploads";

/// Where one call's bytes go, and as whom.
///
/// `Debug` is derived and safe: the password prints as `<redacted>`.
#[derive(Debug)]
pub struct Target {
    /// The instance's host, without a port.
    pub host: String,
    /// Its port.
    pub port: u16,
    /// What comes before `remote.php` when Nextcloud is not at the root of the
    /// host — `/nextcloud` on a shared domain. Empty or starting with `/`.
    pub base_path: String,
    /// The account.
    pub username: String,
    /// Its app password.
    pub password: Password,
    /// The folder inside that account, or empty for its root.
    pub folder: String,
}

/// An app password that does not print itself.
#[derive(Clone)]
pub struct Password(String);

impl Password {
    /// The `Authorization` header for this account.
    ///
    /// # Arguments
    ///
    /// * `username` — the account it belongs to
    pub fn basic(&self, username: &str) -> String {
        format!(
            "Basic {}",
            base64(format!("{username}:{}", self.0).as_bytes())
        )
    }
}

impl std::fmt::Debug for Password {
    /// Prints `<redacted>` and never the password.
    ///
    /// A `{:?}` on a configuration struct is how a secret reaches a log line,
    /// and the derive would have put this one there the first time anybody
    /// debugged a `401` (REQ-SEC-050).
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("<redacted>")
    }
}

impl Target {
    /// The WebDAV path of one blob.
    ///
    /// `<base>/remote.php/dav/files/<user>/<folder>/sha256/<tenantId>/<aa>/<bb>/<hash>`
    /// — the tail is the layout the in-deployment `blobstore` service writes on
    /// disk and the S3 plugin writes into its bucket
    /// ([ADR-0032](../../../docs/adr/0032-per-tenant-blob-addressing.md)).
    /// Identical on purpose: moving a tenant between any two of the three stores
    /// is a copy and never a rename.
    ///
    /// # Arguments
    ///
    /// * `tenant_id` — the owning tenant, canonical hyphenated form
    /// * `sha256` — the content address, lower-case hex, 64 characters
    pub fn path_for(&self, tenant_id: &str, sha256: &str) -> String {
        format!(
            "{}/{}",
            self.files_root(),
            self.blob_path(tenant_id, sha256)
        )
    }

    /// The collections that have to exist before a blob can be written, in the
    /// order they have to be created.
    ///
    /// WebDAV creates no parent on its own: a `PUT` into a collection that is
    /// not there answers `409`, and there is no flag that changes it. So the
    /// chain is walked — but only after a `409`, because after the first upload
    /// of a tenant every one of them already exists.
    ///
    /// # Arguments
    ///
    /// * `tenant_id` — the owning tenant
    /// * `sha256` — the content address
    pub fn collections_for(&self, tenant_id: &str, sha256: &str) -> Vec<String> {
        let mut walked = Vec::new();
        let mut so_far = self.files_root();
        for segment in self.blob_path(tenant_id, sha256).split('/') {
            // Everything but the file itself, which is the last segment.
            if segment == sha256 {
                break;
            }
            so_far = format!("{so_far}/{segment}");
            walked.push(so_far.clone());
        }
        walked
    }

    /// Where an unfinished chunked upload is assembled.
    ///
    /// # Arguments
    ///
    /// * `id` — this upload's own name, unique within the account
    pub fn upload_path(&self, id: &str) -> String {
        format!(
            "{}/{UPLOAD_ROOT}/{}/{}",
            self.base_path,
            encode(&self.username),
            encode(id)
        )
    }

    /// The absolute URL of a blob, which `MOVE` needs in its `Destination`.
    ///
    /// # Arguments
    ///
    /// * `tenant_id` — the owning tenant
    /// * `sha256` — the content address
    pub fn destination_url(&self, tenant_id: &str, sha256: &str) -> String {
        let authority = if self.port == DEFAULT_PORT {
            self.host.clone()
        } else {
            format!("{}:{}", self.host, self.port)
        };
        format!("https://{authority}{}", self.path_for(tenant_id, sha256))
    }

    /// The account's files root, path only.
    fn files_root(&self) -> String {
        format!("{}/{FILES_ROOT}/{}", self.base_path, encode(&self.username))
    }

    /// The part after the account's root: the folder, then the blob's address.
    fn blob_path(&self, tenant_id: &str, sha256: &str) -> String {
        let mut path = String::new();
        if !self.folder.is_empty() {
            for segment in self.folder.split('/') {
                path.push_str(&encode(segment));
                path.push('/');
            }
        }
        path.push_str(&format!(
            "sha256/{}/{}/{}/{sha256}",
            encode(tenant_id),
            &sha256[0..2],
            &sha256[2..4]
        ));
        path
    }
}

/// Percent-encodes one path segment.
///
/// Unreserved characters stay, everything else becomes `%XX`. A blob's address
/// is hexadecimal and a tenant id is a UUID, so nothing in the generated part is
/// ever encoded; an account name or a folder a tenant chose can contain
/// anything, and that is the case this exists for.
pub fn encode(segment: &str) -> String {
    let mut out = String::with_capacity(segment.len());
    for byte in segment.as_bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => {
                out.push(*byte as char)
            }
            other => out.push_str(&format!("%{other:02X}")),
        }
    }
    out
}

/// What the operator configured in the container.
#[derive(Default, Clone)]
pub struct Defaults {
    /// The instance, as a URL or a bare host.
    pub url: Option<String>,
    /// The account.
    pub username: Option<String>,
    /// Its app password, read from a mounted file.
    pub password: Option<String>,
    /// The folder inside the account.
    pub folder: Option<String>,
}

impl Defaults {
    /// Whether the operator configured anything at all.
    ///
    /// A deployment where every tenant brings its own account is a valid
    /// deployment, and one where the operator set two of the three fields is
    /// not. Telling them apart is what makes the health check worth reading.
    pub fn touched(&self) -> bool {
        self.url.is_some() || self.username.is_some() || self.password.is_some()
    }

    /// What is still missing from a deployment default that was started.
    pub fn incomplete(&self) -> Vec<String> {
        let mut missing = Vec::new();
        if self.url.is_none() {
            missing.push("HOMEINV_NEXTCLOUD_URL names no instance".to_string());
        }
        if self.username.is_none() {
            missing.push("HOMEINV_NEXTCLOUD_USER names no account".to_string());
        }
        if self.password.is_none() {
            missing.push(
                "the app password file is empty or missing. It is created empty deliberately: a \
                 password to somebody else's Nextcloud is not one this deployment may invent"
                    .to_string(),
            );
        }
        missing
    }

    /// The target for one call, from these defaults and this tenant's settings.
    ///
    /// # Arguments
    ///
    /// * `settings` — the envelope's settings for this tenant (ADR-0073)
    ///
    /// # Errors
    ///
    /// Every missing piece at once, each saying whose it is to supply.
    pub fn resolve(&self, settings: &HashMap<String, String>) -> Result<Target, Vec<String>> {
        let setting = |name: &str| -> Option<String> {
            settings
                .get(name)
                .map(|value| value.trim().to_string())
                .filter(|value| !value.is_empty())
        };

        let mut missing = Vec::new();

        let account = match (setting("username"), setting("appPassword")) {
            (Some(username), Some(password)) => Some((username, password)),
            (Some(_), None) => {
                missing.push(
                    "this tenant configured `username` and no `appPassword`. The two are used as \
                     a pair, so that one account is never tried with another's password"
                        .to_string(),
                );
                None
            }
            (None, Some(_)) => {
                missing.push("this tenant configured `appPassword` and no `username`".to_string());
                None
            }
            (None, None) => match (self.username.clone(), self.password.clone()) {
                (Some(username), Some(password)) => Some((username, password)),
                _ => {
                    missing.push(
                        "no account: this tenant configured none and the deployment has none for \
                         it to fall back to"
                            .to_string(),
                    );
                    None
                }
            },
        };

        let url = setting("url").or_else(|| self.url.clone());
        if url.is_none() {
            missing.push(
                "no instance: this tenant configured none and HOMEINV_NEXTCLOUD_URL is unset"
                    .to_string(),
            );
        }

        if !missing.is_empty() {
            return Err(missing);
        }

        let (host, port, base_path) = split_url(&url.expect("checked above"));
        let (username, password) = account.expect("checked above");
        Ok(Target {
            host,
            port,
            base_path,
            username,
            password: Password(password),
            folder: normalise_folder(setting("folder").or_else(|| self.folder.clone())),
        })
    }
}

/// Splits a URL into host, port and the path Nextcloud is mounted under.
///
/// A scheme is stripped if somebody wrote one. There is no plaintext option: the
/// only thing on the far end of the tunnel is TLS.
fn split_url(url: &str) -> (String, u16, String) {
    let bare = url
        .trim()
        .trim_start_matches("https://")
        .trim_start_matches("http://")
        .trim_end_matches('/');
    let (authority, path) = match bare.find('/') {
        Some(index) => (&bare[..index], bare[index..].trim_end_matches('/')),
        None => (bare, ""),
    };
    let (host, port) = match authority.rsplit_once(':') {
        Some((host, port)) => match port.parse() {
            Ok(port) => (host.to_string(), port),
            Err(_) => (authority.to_string(), DEFAULT_PORT),
        },
        None => (authority.to_string(), DEFAULT_PORT),
    };
    (host, port, path.to_string())
}

/// A folder with no leading or trailing slash, or empty.
fn normalise_folder(folder: Option<String>) -> String {
    match folder {
        None => String::new(),
        Some(folder) => folder.trim().trim_matches('/').to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const HASH: &str = "ab00000000000000000000000000000000000000000000000000000000000000";

    fn deployment() -> Defaults {
        Defaults {
            url: Some("https://cloud.example.org".to_string()),
            username: Some("inventory".to_string()),
            password: Some("app-password".to_string()),
            folder: Some("HomeInventory".to_string()),
        }
    }

    fn settings(pairs: &[(&str, &str)]) -> HashMap<String, String> {
        pairs
            .iter()
            .map(|(key, value)| (key.to_string(), value.to_string()))
            .collect()
    }

    #[test]
    fn a_tenant_that_configures_nothing_uses_the_deployment() {
        let target = deployment().resolve(&settings(&[])).expect("resolves");
        assert_eq!(target.host, "cloud.example.org");
        assert_eq!(target.port, 443);
        assert_eq!(target.username, "inventory");
        assert_eq!(target.folder, "HomeInventory");
    }

    #[test]
    fn a_tenant_with_its_own_account_uses_it_entirely() {
        let target = deployment()
            .resolve(&settings(&[
                ("url", "https://cloud.tenant.example:8443/nextcloud"),
                ("username", "them"),
                ("appPassword", "theirs"),
                ("folder", "/Photographs/"),
            ]))
            .expect("resolves");
        assert_eq!(target.host, "cloud.tenant.example");
        assert_eq!(target.port, 8443);
        assert_eq!(target.base_path, "/nextcloud");
        assert_eq!(target.username, "them");
        assert_eq!(target.folder, "Photographs");
    }

    #[test]
    fn half_a_credential_pair_is_refused_rather_than_mixed() {
        let failure = deployment()
            .resolve(&settings(&[("username", "them")]))
            .expect_err("half a pair");
        assert!(failure.iter().any(|line| line.contains("used as a pair")));
    }

    #[test]
    fn a_deployment_with_nothing_configured_says_so_per_tenant() {
        let failure = Defaults::default()
            .resolve(&settings(&[]))
            .expect_err("nothing anywhere");
        assert_eq!(failure.len(), 2);
        assert!(failure.iter().any(|line| line.contains("no account")));
        assert!(failure.iter().any(|line| line.contains("no instance")));
    }

    #[test]
    fn a_half_configured_deployment_names_every_missing_piece() {
        let defaults = Defaults {
            url: Some("https://cloud.example.org".to_string()),
            ..Default::default()
        };
        assert!(defaults.touched());
        assert_eq!(defaults.incomplete().len(), 2);
    }

    #[test]
    fn the_path_is_the_layout_the_other_two_stores_use() {
        let target = deployment().resolve(&settings(&[])).expect("resolves");
        assert_eq!(
            target.path_for("0191e2aa-0000-7000-8000-000000000001", HASH),
            format!(
                "/remote.php/dav/files/inventory/HomeInventory/sha256/\
                 0191e2aa-0000-7000-8000-000000000001/ab/00/{HASH}"
            )
        );
    }

    #[test]
    fn an_instance_under_a_path_keeps_it_in_front() {
        let target = deployment()
            .resolve(&settings(&[("url", "cloud.example.org/nextcloud")]))
            .expect("resolves");
        assert!(target
            .path_for("t", HASH)
            .starts_with("/nextcloud/remote.php/dav/files/inventory/"));
    }

    #[test]
    fn every_parent_collection_is_named_in_the_order_it_is_created() {
        let target = deployment().resolve(&settings(&[])).expect("resolves");
        let collections = target.collections_for("tenant", HASH);
        assert_eq!(
            collections,
            vec![
                "/remote.php/dav/files/inventory/HomeInventory".to_string(),
                "/remote.php/dav/files/inventory/HomeInventory/sha256".to_string(),
                "/remote.php/dav/files/inventory/HomeInventory/sha256/tenant".to_string(),
                "/remote.php/dav/files/inventory/HomeInventory/sha256/tenant/ab".to_string(),
                "/remote.php/dav/files/inventory/HomeInventory/sha256/tenant/ab/00".to_string(),
            ]
        );
    }

    #[test]
    fn the_destination_url_is_absolute_because_move_needs_one() {
        let target = deployment().resolve(&settings(&[])).expect("resolves");
        assert!(target
            .destination_url("tenant", HASH)
            .starts_with("https://cloud.example.org/remote.php/dav/files/inventory/"));
        let other = deployment()
            .resolve(&settings(&[("url", "cloud.example.org:8443")]))
            .expect("resolves");
        assert!(other
            .destination_url("tenant", HASH)
            .starts_with("https://cloud.example.org:8443/"));
    }

    #[test]
    fn a_folder_with_a_space_is_encoded_and_still_one_folder() {
        let target = deployment()
            .resolve(&settings(&[("folder", "Home Inventory/Media")]))
            .expect("resolves");
        assert!(target
            .path_for("t", HASH)
            .contains("/Home%20Inventory/Media/sha256/"));
    }

    #[test]
    fn the_password_never_prints_itself() {
        let target = deployment().resolve(&settings(&[])).expect("resolves");
        let printed = format!("{target:?}");
        assert!(printed.contains("<redacted>"));
        assert!(!printed.contains("app-password"));
    }

    #[test]
    fn the_authorization_header_is_basic_over_user_and_password() {
        let target = deployment().resolve(&settings(&[])).expect("resolves");
        // `inventory:app-password`, which is what every WebDAV client sends.
        assert_eq!(
            target.password.basic(&target.username),
            "Basic aW52ZW50b3J5OmFwcC1wYXNzd29yZA=="
        );
    }

    #[test]
    fn a_scheme_is_stripped_and_never_makes_it_plaintext() {
        assert_eq!(
            split_url("http://cloud.example.org/nextcloud/"),
            (
                "cloud.example.org".to_string(),
                443,
                "/nextcloud".to_string()
            )
        );
    }
}
