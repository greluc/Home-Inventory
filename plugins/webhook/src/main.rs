// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! `plugin-webhook` — a notification, signed, POSTed to a URL a tenant chose.
//!
//! One of the five first-party plugins ([ADR-0072](../../../docs/adr/0072-first-party-plugins-live-here.md)),
//! and the smallest: it implements `NotificationChannel` and does exactly one
//! thing with a message. It is a plugin rather than part of the core because it
//! opens a connection to a host outside the deployment, and the core has no
//! route out at all ([ADR-0026](../../../docs/adr/0026-core-outbound-via-plugins.md)).
//!
//! # Configured twice, like every plugin (ADR-0073)
//!
//! | | Who decides | Where |
//! |---|---|---|
//! | the egress proxy to use, the timeout, the listening port, the mTLS identity | the operator | this container's environment and mounted secrets |
//! | the **signing secret** | each tenant | the call envelope, sealed at rest in the core |
//!
//! The target URL is neither: it is the `recipient` of the call, because it
//! belongs to the subscription rather than to the plugin.
//!
//! # What it refuses
//!
//! A target that is not `https`, one carrying credentials, and one naming a
//! private, loopback or link-local address — `REQ-SEC-034`, checked here as well
//! as at the proxy, because the URL came from a person typing into a form. It
//! follows **no redirects**: a redirect is a target the allowlist never approved.
//!
//! The signature covers the **timestamp and the body** (`REQ-API-010`), so a
//! captured request is worth a tolerance window rather than for ever.
//!
//! Without a signing secret it delivers nothing. An unsigned webhook is one
//! anybody who learns the URL can forge, and "it worked without a secret" is how
//! a deployment ends up with one.

mod deliver;
mod payload;
mod signature;
mod target;

/// The generated contract from `proto/home_inv/plugin/v1/`.
///
/// `dead_code` is allowed here and nowhere else: the generated module carries every
/// message of `common.proto`, and this plugin constructs two of them. A type nobody
/// builds is what a shared contract looks like from inside one of its users.
#[allow(dead_code)]
mod proto {
    tonic::include_proto!("home_inv.plugin.v1");
}

use std::net::SocketAddr;
use std::path::PathBuf;
use std::time::Duration;

use homeinv_plugin_common::keys::Remembered;
use homeinv_plugin_common::tls;

use tonic::transport::Server;
use tonic::{Request, Response, Status};
use tracing::{info, warn};

use crate::proto::notification_channel_server::{NotificationChannel, NotificationChannelServer};
use crate::proto::plugin_health_server::{PluginHealth, PluginHealthServer};
use crate::proto::{
    Check, HealthRequest, HealthResponse, HealthState, NotificationChannelDescriptor,
    NotificationDeliverRequest, NotificationDeliverResponse, NotificationDescribeRequest,
};

/// The port the service matrix names for a plugin. Never published to the host.
const DEFAULT_PORT: u16 = 8200;

/// Where the runtime mounts this plugin's own identity.
const DEFAULT_IDENTITY: &str = "/run/secrets/mtls-plugin-webhook";

/// How long the whole exchange with a receiver may take.
const DEFAULT_TIMEOUT_SECONDS: u64 = 10;

/// The setting a tenant configures, declared in the manifest (ADR-0073).
const SIGNING_SECRET: &str = "signingSecret";

/// How many delivered keys are remembered, so a retry of one is recognised.
const REMEMBERED_KEYS: usize = 10_000;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // THE LICENCE NOTICE THIS BINARY CARRIES (REQ-CON-013).
    //
    // A permissive licence asks for its notice in every copy, and a statically
    // linked binary is a copy. This image is `scratch` — one binary and nothing
    // else, which CI asserts — so there is no file to put beside it and the
    // notice is compiled in, exactly as the public roots are in the plugins
    // that speak TLS.
    //
    // First, before the logger: somebody reading a licence should get the
    // licence and not a JSON log line above it. `tools/notices.py` generates
    // the file and CI fails when it no longer describes what is linked in.
    if std::env::args().any(|argument| argument == "--licences") {
        print!("{}", include_str!("../THIRD-PARTY-NOTICES.txt"));
        return Ok(());
    }

    // JSON lines, like every other service in the deployment (REQ-NFR-041).
    tracing_subscriber::fmt()
        .json()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
        )
        .init();

    let port: u16 = env("HOMEINV_PLUGIN_PORT")
        .and_then(|value| value.parse().ok())
        .unwrap_or(DEFAULT_PORT);
    let identity =
        PathBuf::from(env("HOMEINV_MTLS_PLUGIN_FILE").unwrap_or(DEFAULT_IDENTITY.into()));
    let proxy = env("HOMEINV_EGRESS_PROXY");
    let timeout = Duration::from_secs(
        env("HOMEINV_WEBHOOK_TIMEOUT_SECONDS")
            .and_then(|value| value.parse().ok())
            .unwrap_or(DEFAULT_TIMEOUT_SECONDS),
    );

    let address: SocketAddr = format!("0.0.0.0:{port}").parse()?;
    let channel = Webhook {
        proxy: proxy.clone(),
        timeout,
        delivered: Remembered::holding(REMEMBERED_KEYS),
    };

    info!(
        port = port,
        proxy = proxy.as_deref().unwrap_or("none"),
        "plugin-webhook is listening"
    );
    if proxy.is_none() {
        // Said once, at startup, because it is the difference between "nothing
        // is delivered" and "nothing is delivered and here is why".
        warn!(
            "HOMEINV_EGRESS_PROXY is not set. A plugin segment has no route out of the deployment \
             on its own (ADR-0026), so every delivery will fail to connect."
        );
    }

    Server::builder()
        .tls_config(tls::server_config(&identity)?)?
        .add_service(NotificationChannelServer::new(channel))
        .add_service(PluginHealthServer::new(Health {
            proxy,
            identity_present: identity.exists(),
        }))
        .serve_with_shutdown(address, async {
            let _ = tokio::signal::ctrl_c().await;
            info!("plugin-webhook is stopping");
        })
        .await?;
    Ok(())
}

/// Reads one setting from the environment, treating blank as absent.
fn env(name: &str) -> Option<String> {
    std::env::var(name)
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

/// The channel itself.
struct Webhook {
    /// `host:port` of the egress proxy, the one route out.
    proxy: Option<String>,
    /// How long a delivery may take in total.
    timeout: Duration,
    /// Keys already delivered, newest last.
    ///
    /// In memory and bounded: a restart forgets, which the `README` says rather
    /// than implying otherwise. The core retries within minutes, so the window
    /// that matters is far shorter than a process's life — and a plugin with a
    /// durable store would be a plugin with a database.
    delivered: Remembered,
}

#[tonic::async_trait]
impl NotificationChannel for Webhook {
    async fn describe(
        &self,
        _request: Request<NotificationDescribeRequest>,
    ) -> Result<Response<NotificationChannelDescriptor>, Status> {
        Ok(Response::new(NotificationChannelDescriptor {
            channel_key: "webhook".into(),
            name: "Webhook".into(),
            // The core validates a subscription's address against this, which is
            // why `http` is absent rather than merely discouraged.
            address_schemes: vec!["https".into()],
            // A receiver gets both bodies in the payload and decides for itself;
            // there is no rendering here, so there is nothing to support.
            supports_html: true,
        }))
    }

    async fn deliver(
        &self,
        request: Request<NotificationDeliverRequest>,
    ) -> Result<Response<NotificationDeliverResponse>, Status> {
        let message = request.into_inner();
        let context = message.context.unwrap_or_default();

        let secret = context
            .settings
            .get(SIGNING_SECRET)
            .filter(|value| !value.is_empty())
            .ok_or_else(|| {
                Status::failed_precondition(format!(
                    "this tenant has configured no {SIGNING_SECRET}. An unsigned webhook is one \
                     anybody who learns the URL can forge, so nothing is sent without one."
                ))
            })?
            .clone();

        let target = target::parse(&message.recipient).map_err(Status::invalid_argument)?;

        if self.delivered.already(&message.idempotency_key) {
            // Recognised and not sent again. The core counts these separately,
            // because a delivery log where every retry looks like a send cannot
            // answer "did this person get two messages".
            return Ok(Response::new(NotificationDeliverResponse {
                provider_message_id: String::new(),
                detail: "already delivered".into(),
                deduplicated: true,
            }));
        }

        let attachments: Vec<payload::Attachment<'_>> = message
            .attachments
            .iter()
            .map(|attachment| payload::Attachment {
                file_name: &attachment.file_name,
                media_type: &attachment.media_type,
                bytes: attachment.content.len(),
            })
            .collect();

        let body = payload::build(
            &message.idempotency_key,
            &context.tenant_id,
            &message.subject,
            &message.text,
            &message.html,
            &message.language,
            &attachments,
        );

        // Seconds since the epoch, signed WITH the body (REQ-API-010): a captured
        // request is otherwise a valid request for ever, and a timestamp sent
        // beside the signature rather than inside it is one an attacker simply
        // changes.
        let timestamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|since| since.as_secs())
            .unwrap_or(0);

        let mut headers = vec![
            (
                "X-HomeInv-Signature".to_string(),
                format!(
                    "sha256={}",
                    signature::sign(secret.as_bytes(), timestamp, &body)
                ),
            ),
            ("X-HomeInv-Timestamp".to_string(), timestamp.to_string()),
            (
                "X-HomeInv-Idempotency-Key".to_string(),
                message.idempotency_key.clone(),
            ),
        ];
        headers.extend(extra_headers(&message.headers));

        match deliver::post(
            self.proxy.as_deref(),
            &target,
            &headers,
            &body,
            self.timeout,
        )
        .await
        {
            Ok(answer) if (200..300).contains(&answer.status) => {
                info!(
                    host = %target.host,
                    status = answer.status,
                    "a webhook was delivered"
                );
                Ok(Response::new(NotificationDeliverResponse {
                    provider_message_id: String::new(),
                    detail: answer.detail,
                    deduplicated: false,
                }))
            }
            Ok(answer) if (400..500).contains(&answer.status) => {
                // The receiver understood and refused. Permanent: a retry sends
                // the same thing to the same place and is refused the same way,
                // so the core dead-letters it on the first attempt.
                Err(Status::invalid_argument(format!(
                    "the target refused the delivery: {}",
                    answer.detail
                )))
            }
            Ok(answer) => Err(Status::unavailable(format!(
                "the target could not take the delivery: {}",
                answer.detail
            ))),
            Err(reason) => {
                warn!(host = %target.host, reason = %reason, "a webhook could not be delivered");
                Err(Status::unavailable(reason))
            }
        }
    }
}

/// The extra headers a caller asked for, filtered.
///
/// The contract lets the core pass channel-specific extras and says a channel
/// ignores what it does not know. What is refused here is anything that would
/// overwrite what this plugin controls — the signature, the idempotency key, the
/// framing headers — and anything carrying a control character, which is how a
/// header map becomes two headers.
fn extra_headers(supplied: &std::collections::HashMap<String, String>) -> Vec<(String, String)> {
    const RESERVED: [&str; 7] = [
        "x-homeinv-signature",
        // Reserved as hard as the signature is: a caller that could set the
        // timestamp could make a replay look fresh to a receiver that trusts it.
        "x-homeinv-timestamp",
        "x-homeinv-idempotency-key",
        "host",
        "content-length",
        "content-type",
        "connection",
    ];

    let mut extra: Vec<(String, String)> = supplied
        .iter()
        .filter(|(name, value)| {
            let lowered = name.to_ascii_lowercase();
            !RESERVED.contains(&lowered.as_str())
                && !name.is_empty()
                && name
                    .chars()
                    .all(|c| c.is_ascii_graphic() && c != ':' && c != '"')
                && value.chars().all(|c| !c.is_ascii_control())
                && name.len() <= 64
                && value.len() <= 1024
        })
        .map(|(name, value)| (name.clone(), value.clone()))
        .collect();
    // A map has no order and a request does. Sorted, so that the same call
    // produces the same request every time.
    extra.sort();
    extra
}

/// What this plugin says about itself when asked.
struct Health {
    proxy: Option<String>,
    identity_present: bool,
}

#[tonic::async_trait]
impl PluginHealth for Health {
    async fn check(
        &self,
        _request: Request<HealthRequest>,
    ) -> Result<Response<HealthResponse>, Status> {
        // REQ-PLG-015: an outbound plugin verifies its target completely and
        // refuses service on a partial state. Here that means the two things
        // without which no delivery can succeed, reported as what they are
        // rather than as "unhealthy".
        let checks = vec![
            Check {
                name: "egress-proxy-configured".into(),
                passed: self.proxy.is_some(),
                detail: if self.proxy.is_some() {
                    String::new()
                } else {
                    "HOMEINV_EGRESS_PROXY is not set, so there is no route out".into()
                },
            },
            Check {
                name: "mtls-identity-present".into(),
                passed: self.identity_present,
                detail: if self.identity_present {
                    String::new()
                } else {
                    "the mTLS identity file is not mounted".into()
                },
            },
        ];
        let ready = checks.iter().all(|check| check.passed);

        Ok(Response::new(HealthResponse {
            state: if ready {
                HealthState::Ok as i32
            } else {
                HealthState::NotConfigured as i32
            },
            detail: if ready {
                String::new()
            } else {
                "the plugin is installed and not yet configured to reach anything".into()
            },
            checks,
        }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn plugin() -> Webhook {
        Webhook {
            proxy: Some("egress-proxy:8118".into()),
            timeout: Duration::from_secs(1),
            delivered: Remembered::holding(REMEMBERED_KEYS),
        }
    }

    #[test]
    fn a_repeat_is_recognised_rather_than_sent_again() {
        // The behaviour of the set itself is `homeinv_plugin_common::keys`'
        // business and tested there; what this asserts is that this plugin is
        // wired to it at all.
        let webhook = plugin();
        assert!(!webhook.delivered.already("key-1"));
        assert!(webhook.delivered.already("key-1"));
    }

    #[test]
    fn the_reserved_headers_cannot_be_overwritten() {
        let mut supplied = HashMap::new();
        supplied.insert(
            "X-HomeInv-Signature".to_string(),
            "sha256=forged".to_string(),
        );
        // As hard as the signature: a caller that could set the timestamp could
        // make a replay look fresh to a receiver that trusts it.
        supplied.insert("X-HomeInv-Timestamp".to_string(), "0".to_string());
        supplied.insert("Host".to_string(), "somewhere.else".to_string());
        supplied.insert("X-Team".to_string(), "kitchen".to_string());
        let headers = extra_headers(&supplied);
        assert_eq!(headers, vec![("X-Team".to_string(), "kitchen".to_string())]);
    }

    #[test]
    fn a_header_with_a_newline_is_dropped() {
        // Otherwise a value is two headers, and the second one is whatever the
        // caller wanted it to be.
        let mut supplied = HashMap::new();
        supplied.insert("X-Team".to_string(), "kitchen\r\nX-Admin: true".to_string());
        assert!(extra_headers(&supplied).is_empty());
    }

    #[test]
    fn headers_are_ordered_so_a_request_is_reproducible() {
        let mut supplied = HashMap::new();
        supplied.insert("B".to_string(), "2".to_string());
        supplied.insert("A".to_string(), "1".to_string());
        let headers = extra_headers(&supplied);
        assert_eq!(headers[0].0, "A");
        assert_eq!(headers[1].0, "B");
    }
}

#[cfg(test)]
mod licences {
    /// The notice is compiled into the binary rather than read from a file
    /// (`REQ-CON-013`). A `scratch` image has no filesystem to read one from,
    /// so an absent notice would be a link error here and a licence breach in
    /// production; this makes it the former.
    #[test]
    fn the_notice_travels_with_the_binary() {
        let notice = include_str!("../THIRD-PARTY-NOTICES.txt");

        assert!(notice.starts_with("THIRD-PARTY LICENCE NOTICES"));
        assert!(notice.contains("rustls"));
        assert!(
            notice.len() > 10_000,
            "a notice this short is a generator that failed"
        );
    }
}
