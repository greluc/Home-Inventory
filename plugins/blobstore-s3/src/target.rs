// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Which bucket, as whom — the two-level configuration of ADR-0073.
//!
//! | | Who decides | Where it lives |
//! |---|---|---|
//! | the deployment's bucket, its endpoint, region and keys | the operator | this container's environment and a mounted secret |
//! | a tenant's **own** bucket, endpoint, region, prefix and keys | that tenant | the call envelope, the secret sealed in the core |
//!
//! A tenant that configures nothing uses the deployment's bucket under its own
//! key prefix — the tenant id is part of every address
//! ([ADR-0032](../../../docs/adr/0032-per-tenant-blob-addressing.md)), so one
//! bucket holds many tenants without their addresses ever colliding. A tenant
//! that wants its own account fills in the settings and its bytes never touch
//! the operator's bucket again.
//!
//! # Credentials are a pair
//!
//! `accessKeyId` and `secretAccessKey` are taken together or not at all. Half a
//! pair would mean signing one party's key id with the other's secret, which
//! fails at the far end with a message about the signature — a sentence that
//! sends the reader to look at the clock, the region and the canonical form
//! before the one thing that is actually wrong.
//!
//! # The endpoint is still the operator's to allow
//!
//! A tenant may name any endpoint; the egress proxy will refuse everything the
//! operator's allowlist does not carry
//! ([ADR-0027](../../../docs/adr/0027-egress-enforcement.md),
//! [ADR-0037](../../../docs/adr/0037-per-plugin-network-segments.md)). That is
//! the intended division: the tenant chooses where its data goes, the operator
//! chooses which places this deployment may reach at all.

use std::collections::HashMap;

use crate::sigv4::{encode_key, Credentials};

/// The default port when an endpoint names none. TLS, always.
const DEFAULT_PORT: u16 = 443;

/// What AWS calls a region when a store has none of its own.
///
/// MinIO, Garage and most of the rest ignore it and still require the signature
/// to name one, because the credential scope has a slot for it.
const DEFAULT_REGION: &str = "us-east-1";

/// Where one call's bytes go, and as whom.
///
/// `Debug` is derived and safe: the secret inside [`Credentials`] prints as
/// `<redacted>`.
#[derive(Debug)]
pub struct Target {
    /// The endpoint's host, without a port.
    pub endpoint_host: String,
    /// Its port.
    pub port: u16,
    /// The bucket.
    pub bucket: String,
    /// The credential scope's region.
    pub region: String,
    /// What goes in front of every key, or empty.
    pub prefix: String,
    /// `true` for `https://endpoint/bucket/key`, `false` for
    /// `https://bucket.endpoint/key`.
    pub path_style: bool,
    /// Whose keys sign the requests.
    pub credentials: Credentials,
}

impl Target {
    /// The host to open the tunnel to and to verify the certificate against.
    ///
    /// In virtual-hosted style the bucket is part of the name, which means it is
    /// also part of what the operator has to allow and what the certificate has
    /// to cover. That is why path style is the default: a self-hosted store
    /// rarely has a wildcard certificate, and its operator would have to allow
    /// one host per bucket.
    pub fn connect_host(&self) -> String {
        if self.path_style {
            self.endpoint_host.clone()
        } else {
            format!("{}.{}", self.bucket, self.endpoint_host)
        }
    }

    /// The `Host` header, which carries the port unless it is the default.
    pub fn request_host(&self) -> String {
        let host = self.connect_host();
        if self.port == DEFAULT_PORT {
            host
        } else {
            format!("{host}:{}", self.port)
        }
    }

    /// The request path for a key, percent-encoded.
    ///
    /// # Arguments
    ///
    /// * `key` — the object key, unencoded, or empty for a bucket-level request
    pub fn path_for(&self, key: &str) -> String {
        if self.path_style {
            if key.is_empty() {
                format!("/{}", encode_key(&self.bucket))
            } else {
                format!("/{}/{}", encode_key(&self.bucket), encode_key(key))
            }
        } else if key.is_empty() {
            "/".to_string()
        } else {
            format!("/{}", encode_key(key))
        }
    }

    /// The key a blob is stored under.
    ///
    /// `<prefix>sha256/<tenantId>/<aa>/<bb>/<hash>` — the same layout the
    /// in-deployment `blobstore` service writes on disk
    /// ([ADR-0032](../../../docs/adr/0032-per-tenant-blob-addressing.md)).
    /// Identical on purpose: an operator moving a tenant between the two stores
    /// copies bytes and renames nothing, and a person looking into either place
    /// sees the same thing.
    ///
    /// # Arguments
    ///
    /// * `tenant_id` — the owning tenant, canonical hyphenated form
    /// * `sha256` — the content address, lower-case hex, 64 characters
    pub fn key_for(&self, tenant_id: &str, sha256: &str) -> String {
        format!(
            "{}sha256/{tenant_id}/{}/{}/{sha256}",
            self.prefix,
            &sha256[0..2],
            &sha256[2..4]
        )
    }
}

/// What the operator configured in the container.
#[derive(Default, Clone)]
pub struct Defaults {
    /// `host` or `host:port`.
    pub endpoint: Option<String>,
    /// The region, or none for the usual placeholder.
    pub region: Option<String>,
    /// The bucket every tenant uses unless it named its own.
    pub bucket: Option<String>,
    /// A prefix in front of every key.
    pub prefix: Option<String>,
    /// `path` or `virtual`.
    pub addressing: Option<String>,
    /// The access key id.
    pub access_key_id: Option<String>,
    /// Its secret, read from a mounted file.
    pub secret_access_key: Option<String>,
}

impl Defaults {
    /// Whether the operator configured anything at all.
    ///
    /// A deployment where every tenant brings its own account is a valid
    /// deployment, and one where the operator set three of the five fields is
    /// not. Telling them apart is what makes the health check worth reading.
    pub fn touched(&self) -> bool {
        self.endpoint.is_some()
            || self.bucket.is_some()
            || self.access_key_id.is_some()
            || self.secret_access_key.is_some()
    }

    /// What is still missing from a deployment default that was started.
    pub fn incomplete(&self) -> Vec<String> {
        let mut missing = Vec::new();
        if self.endpoint.is_none() {
            missing.push("HOMEINV_S3_ENDPOINT names no object store".to_string());
        }
        if self.bucket.is_none() {
            missing.push("HOMEINV_S3_BUCKET names no bucket".to_string());
        }
        if self.access_key_id.is_none() {
            missing.push("HOMEINV_S3_ACCESS_KEY_ID is not set".to_string());
        }
        if self.secret_access_key.is_none() {
            missing.push(
                "the secret key file is empty or missing. It is created empty deliberately: a key \
                 to somebody else's storage is not one this deployment may invent"
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
    /// Every missing piece at once, each saying whose it is to supply. A caller
    /// that had to fix one field per attempt would make five round trips to
    /// learn five things this knows at once.
    pub fn resolve(&self, settings: &HashMap<String, String>) -> Result<Target, Vec<String>> {
        let setting = |name: &str| -> Option<String> {
            settings
                .get(name)
                .map(|value| value.trim().to_string())
                .filter(|value| !value.is_empty())
        };

        let mut missing = Vec::new();

        // Taken together or not at all, for the reason the module doc gives.
        let credentials = match (setting("accessKeyId"), setting("secretAccessKey")) {
            (Some(id), Some(secret)) => Some(Credentials {
                access_key_id: id,
                secret_access_key: secret,
            }),
            (Some(_), None) => {
                missing.push(
                    "this tenant configured `accessKeyId` and no `secretAccessKey`. The two are \
                     used as a pair, so that one party's key id is never signed with another's \
                     secret"
                        .to_string(),
                );
                None
            }
            (None, Some(_)) => {
                missing.push(
                    "this tenant configured `secretAccessKey` and no `accessKeyId`".to_string(),
                );
                None
            }
            (None, None) => match (self.access_key_id.clone(), self.secret_access_key.clone()) {
                (Some(id), Some(secret)) => Some(Credentials {
                    access_key_id: id,
                    secret_access_key: secret,
                }),
                _ => {
                    missing.push(
                        "no credentials: this tenant configured none and the deployment has none \
                         for it to fall back to"
                            .to_string(),
                    );
                    None
                }
            },
        };

        let endpoint = setting("endpoint").or_else(|| self.endpoint.clone());
        if endpoint.is_none() {
            missing.push(
                "no endpoint: this tenant configured none and HOMEINV_S3_ENDPOINT is unset"
                    .to_string(),
            );
        }
        let bucket = setting("bucket").or_else(|| self.bucket.clone());
        if bucket.is_none() {
            missing.push(
                "no bucket: this tenant configured none and HOMEINV_S3_BUCKET is unset".to_string(),
            );
        }

        let addressing = setting("addressingStyle")
            .or_else(|| self.addressing.clone())
            .unwrap_or_else(|| "path".to_string());
        if !matches!(addressing.as_str(), "path" | "virtual") {
            missing.push(format!(
                "`addressingStyle` is {addressing:?}; it is `path` or `virtual` and is not guessed \
                 at, because the two produce different hostnames and one of them would not be on \
                 the operator's allowlist"
            ));
        }

        if !missing.is_empty() {
            return Err(missing);
        }

        let (host, port) = split_endpoint(&endpoint.expect("checked above"));
        Ok(Target {
            endpoint_host: host,
            port,
            bucket: bucket.expect("checked above"),
            region: setting("region")
                .or_else(|| self.region.clone())
                .unwrap_or_else(|| DEFAULT_REGION.to_string()),
            prefix: normalise_prefix(setting("prefix").or_else(|| self.prefix.clone())),
            path_style: addressing == "path",
            credentials: credentials.expect("checked above"),
        })
    }
}

/// Splits `host` or `host:port`, defaulting to 443.
///
/// A port that is not a number is dropped rather than refused: the endpoint then
/// names a host this deployment's allowlist either carries or does not, and the
/// refusal that follows names the host — which is more useful than a message
/// about parsing.
fn split_endpoint(endpoint: &str) -> (String, u16) {
    // A scheme is stripped if somebody wrote one. There is no plaintext option:
    // the only thing on the far end of the tunnel is TLS.
    let bare = endpoint
        .trim()
        .trim_start_matches("https://")
        .trim_start_matches("http://")
        .trim_end_matches('/');
    match bare.rsplit_once(':') {
        Some((host, port)) => match port.parse() {
            Ok(port) => (host.to_string(), port),
            Err(_) => (bare.to_string(), DEFAULT_PORT),
        },
        None => (bare.to_string(), DEFAULT_PORT),
    }
}

/// A prefix with no leading slash and exactly one trailing one, or empty.
fn normalise_prefix(prefix: Option<String>) -> String {
    match prefix {
        None => String::new(),
        Some(prefix) => {
            let trimmed = prefix.trim().trim_matches('/');
            if trimmed.is_empty() {
                String::new()
            } else {
                format!("{trimmed}/")
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn deployment() -> Defaults {
        Defaults {
            endpoint: Some("objects.example.org".to_string()),
            region: Some("eu-central-1".to_string()),
            bucket: Some("home-inv".to_string()),
            prefix: None,
            addressing: None,
            access_key_id: Some("DEPLOYMENTKEY".to_string()),
            secret_access_key: Some("deployment-secret".to_string()),
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
        assert_eq!(target.endpoint_host, "objects.example.org");
        assert_eq!(target.bucket, "home-inv");
        assert_eq!(target.credentials.access_key_id, "DEPLOYMENTKEY");
        assert_eq!(target.region, "eu-central-1");
        assert!(target.path_style);
    }

    #[test]
    fn a_tenant_with_its_own_account_uses_it_entirely() {
        let target = deployment()
            .resolve(&settings(&[
                ("endpoint", "s3.tenant.example:9000"),
                ("bucket", "photographs"),
                ("accessKeyId", "TENANTKEY"),
                ("secretAccessKey", "tenant-secret"),
                ("prefix", "/inventory/"),
            ]))
            .expect("resolves");
        assert_eq!(target.endpoint_host, "s3.tenant.example");
        assert_eq!(target.port, 9000);
        assert_eq!(target.bucket, "photographs");
        assert_eq!(target.credentials.access_key_id, "TENANTKEY");
        assert_eq!(target.credentials.secret_access_key, "tenant-secret");
        assert_eq!(target.prefix, "inventory/");
    }

    #[test]
    fn half_a_credential_pair_is_refused_rather_than_mixed() {
        let failure = deployment()
            .resolve(&settings(&[("accessKeyId", "TENANTKEY")]))
            .expect_err("half a pair");
        assert!(failure.iter().any(|line| line.contains("used as a pair")));
    }

    #[test]
    fn a_tenant_may_take_the_deployments_endpoint_and_its_own_bucket() {
        let target = deployment()
            .resolve(&settings(&[("bucket", "just-mine")]))
            .expect("resolves");
        assert_eq!(target.endpoint_host, "objects.example.org");
        assert_eq!(target.bucket, "just-mine");
        assert_eq!(target.credentials.access_key_id, "DEPLOYMENTKEY");
    }

    #[test]
    fn a_deployment_with_nothing_configured_says_so_per_tenant() {
        let failure = Defaults::default()
            .resolve(&settings(&[]))
            .expect_err("nothing anywhere");
        assert_eq!(failure.len(), 3);
        assert!(failure.iter().any(|line| line.contains("no credentials")));
        assert!(failure.iter().any(|line| line.contains("no endpoint")));
        assert!(failure.iter().any(|line| line.contains("no bucket")));
    }

    #[test]
    fn a_half_configured_deployment_names_every_missing_piece() {
        let defaults = Defaults {
            endpoint: Some("objects.example.org".to_string()),
            ..Default::default()
        };
        assert!(defaults.touched());
        let missing = defaults.incomplete();
        assert_eq!(missing.len(), 3);
        assert!(missing
            .iter()
            .any(|line| line.contains("HOMEINV_S3_BUCKET")));
    }

    #[test]
    fn the_key_is_the_layout_the_deployment_store_uses() {
        let target = deployment().resolve(&settings(&[])).expect("resolves");
        assert_eq!(
            target.key_for("0191e2aa-0000-7000-8000-000000000001", &"ab".repeat(32)),
            "sha256/0191e2aa-0000-7000-8000-000000000001/ab/ab/".to_string() + &"ab".repeat(32)
        );
    }

    #[test]
    fn a_prefix_goes_in_front_of_that_layout() {
        let target = deployment()
            .resolve(&settings(&[("prefix", "media")]))
            .expect("resolves");
        assert!(target
            .key_for("t", &"cd".repeat(32))
            .starts_with("media/sha256/t/cd/cd/"));
    }

    #[test]
    fn path_style_puts_the_bucket_in_the_path_and_not_in_the_name() {
        let target = deployment().resolve(&settings(&[])).expect("resolves");
        assert_eq!(target.connect_host(), "objects.example.org");
        assert_eq!(target.request_host(), "objects.example.org");
        assert_eq!(
            target.path_for("sha256/t/ab/cd/x"),
            "/home-inv/sha256/t/ab/cd/x"
        );
    }

    #[test]
    fn virtual_style_puts_it_in_the_name_and_not_in_the_path() {
        let target = deployment()
            .resolve(&settings(&[("addressingStyle", "virtual")]))
            .expect("resolves");
        // Which is also the host the operator has to allow and the certificate
        // has to cover, and why it is not the default.
        assert_eq!(target.connect_host(), "home-inv.objects.example.org");
        assert_eq!(target.path_for("sha256/t/ab/cd/x"), "/sha256/t/ab/cd/x");
    }

    #[test]
    fn a_port_shows_up_in_the_host_header() {
        let target = deployment()
            .resolve(&settings(&[("endpoint", "minio.example:9000")]))
            .expect("resolves");
        assert_eq!(target.request_host(), "minio.example:9000");
    }

    #[test]
    fn a_scheme_is_stripped_and_never_makes_it_plaintext() {
        assert_eq!(
            split_endpoint("http://minio.example:9000"),
            ("minio.example".to_string(), 9000)
        );
        assert_eq!(
            split_endpoint("https://objects.example.org/"),
            ("objects.example.org".to_string(), 443)
        );
    }

    #[test]
    fn an_addressing_style_that_is_neither_is_refused() {
        let failure = deployment()
            .resolve(&settings(&[("addressingStyle", "dns")]))
            .expect_err("neither");
        assert!(failure
            .iter()
            .any(|line| line.contains("`path` or `virtual`")));
    }
}
