// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! `plugin-smtp` — the notification that arrives as an e-mail.
//!
//! The second of the five first-party plugins
//! ([ADR-0072](../../../docs/adr/0072-first-party-plugins-live-here.md)) and the
//! one a deployment misses first: an invitation, a password reset and every
//! security notification of `REQ-NOTI-004` are mail or they are nothing. Without
//! it an installation says so plainly rather than failing silently, which is what
//! `REQ-NOTI-002` means by *every channel is a plugin, e-mail included*.
//!
//! # Configured twice, like every plugin (ADR-0073)
//!
//! | | Who decides | Where |
//! |---|---|---|
//! | the mail server, its port, the account, the password, the envelope sender | the operator | this container's environment and mounted secrets |
//! | the **sender's display name** | each tenant | the call envelope |
//!
//! The deployment's mail password never passes through the core: it is a file
//! mounted into this container, like the database password is for `api`.
//!
//! # What it refuses
//!
//! A server that offers no `STARTTLS`, on the `starttls` setting — nothing is
//! sent, and the refusal says why. A submission that fell back to plaintext
//! would hand the credentials to anything on the path *and still deliver the
//! mail*, which is why nobody would notice.
//!
//! An address that is not one local part, one `@` and one domain, or that
//! carries a control character: that is a second `RCPT TO` or a second header
//! rather than an address, and the core dead-letters it on the first attempt.

mod address;
mod mime;
mod smtp;

/// The generated contract from `proto/home_inv/plugin/v1/`.
///
/// `dead_code` is allowed here and nowhere else: the generated module carries
/// every message of `common.proto`, and this plugin constructs two of them.
#[allow(dead_code)]
mod proto {
    tonic::include_proto!("home_inv.plugin.v1");
}

use std::net::SocketAddr;
use std::path::{Path, PathBuf};
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
use crate::smtp::{Security, Server as MailServer};

/// The port the service matrix names for this plugin.
const DEFAULT_PORT: u16 = 8201;

/// Where the runtime mounts this plugin's own identity.
const DEFAULT_IDENTITY: &str = "/run/secrets/mtls-plugin-smtp";

/// Where the runtime mounts the mail account's password.
const DEFAULT_PASSWORD_FILE: &str = "/run/secrets/plugin-smtp-password";

/// How long the whole submission may take.
const DEFAULT_TIMEOUT_SECONDS: u64 = 30;

/// The setting a tenant may configure (ADR-0073).
const SENDER_NAME: &str = "senderName";

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

    let configuration = Configuration::read();
    let address: SocketAddr = format!("0.0.0.0:{port}").parse()?;

    info!(
        port = port,
        host = configuration.host.as_deref().unwrap_or("none"),
        security = ?configuration.security,
        "plugin-smtp is listening"
    );
    for missing in configuration.missing(proxy.as_deref()) {
        // Said once, at startup, and each one names what to set. A plugin that
        // cannot deliver is a plugin an operator has to be able to diagnose
        // without reading its source (REQ-PLG-015).
        warn!("plugin-smtp is not ready: {missing}");
    }

    let channel = Smtp {
        proxy,
        configuration,
        delivered: Remembered::holding(REMEMBERED_KEYS),
    };
    let health = Health {
        ready: channel.configuration.missing(channel.proxy.as_deref()),
    };

    Server::builder()
        .tls_config(tls::server_config(&identity)?)?
        .add_service(NotificationChannelServer::new(channel))
        .add_service(PluginHealthServer::new(health))
        .serve_with_shutdown(address, async {
            let _ = tokio::signal::ctrl_c().await;
            info!("plugin-smtp is stopping");
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

/// What the operator configured about the mail server.
///
/// Read once at startup rather than per call: a delivery that behaved
/// differently because a file changed underneath it would be a delivery nobody
/// could reproduce.
struct Configuration {
    /// The mail server.
    host: Option<String>,
    /// Its port.
    port: u16,
    /// How the connection is secured, or `None` when the setting names neither.
    security: Option<Security>,
    /// The account, or empty for a server that wants none.
    username: String,
    /// Its password, read from the mounted file.
    password: String,
    /// The envelope sender, which is also the `From:` address.
    sender: Option<String>,
    /// The name this client gives in `EHLO`.
    ehlo_name: String,
    /// How long a submission may take.
    timeout: Duration,
}

impl Configuration {
    /// Reads it from the environment and the mounted password file.
    fn read() -> Self {
        let security_setting = env("HOMEINV_SMTP_SECURITY").unwrap_or_else(|| "starttls".into());
        Self {
            host: env("HOMEINV_SMTP_HOST"),
            port: env("HOMEINV_SMTP_PORT")
                .and_then(|value| value.parse().ok())
                .unwrap_or(587),
            security: Security::parse(&security_setting),
            username: env("HOMEINV_SMTP_USER").unwrap_or_default(),
            password: read_secret(
                &env("HOMEINV_SMTP_PASSWORD_FILE").unwrap_or(DEFAULT_PASSWORD_FILE.into()),
            ),
            sender: env("HOMEINV_SMTP_SENDER"),
            // The name in EHLO. A server may check that it resolves, and the
            // default says what this is rather than pretending to be a host.
            ehlo_name: env("HOMEINV_SMTP_EHLO_NAME").unwrap_or_else(|| "home-inventory".into()),
            timeout: Duration::from_secs(
                env("HOMEINV_SMTP_TIMEOUT_SECONDS")
                    .and_then(|value| value.parse().ok())
                    .unwrap_or(DEFAULT_TIMEOUT_SECONDS),
            ),
        }
    }

    /// Everything that has to be set before a message can be sent.
    ///
    /// Returned rather than logged, so that the same list answers the health
    /// check: `REQ-PLG-015` asks an outbound plugin to verify its target
    /// completely and to refuse service on a partial state, and "it is broken" is
    /// the diagnosis an operator can do least with.
    fn missing(&self, proxy: Option<&str>) -> Vec<String> {
        let mut missing = Vec::new();
        if self.host.is_none() {
            missing.push("HOMEINV_SMTP_HOST names no mail server".to_string());
        }
        if self.sender.is_none() {
            missing.push(
                "HOMEINV_SMTP_SENDER names no envelope sender, and a message needs one".to_string(),
            );
        }
        if self.security.is_none() {
            missing.push(
                "HOMEINV_SMTP_SECURITY is neither `starttls` nor `implicit`. It is not guessed at: \
                 guessing here means guessing whether the credentials travel in the clear"
                    .to_string(),
            );
        }
        if !self.username.is_empty() && self.password.is_empty() {
            missing.push(
                "a user name is configured and the password file is empty or missing".to_string(),
            );
        }
        if proxy.is_none() {
            missing.push(
                "HOMEINV_EGRESS_PROXY is not set, and a plugin segment has no route out of the \
                 deployment on its own (ADR-0026)"
                    .to_string(),
            );
        }
        missing
    }

    /// The server, once everything needed is present.
    fn server(&self) -> Option<MailServer> {
        Some(MailServer {
            host: self.host.clone()?,
            port: self.port,
            security: self.security?,
            username: self.username.clone(),
            password: self.password.clone(),
            ehlo_name: self.ehlo_name.clone(),
            timeout: self.timeout,
        })
    }
}

/// Reads a mounted secret, trimming the newline a file usually ends with.
fn read_secret(path: &str) -> String {
    std::fs::read_to_string(Path::new(path))
        .map(|value| value.trim().to_string())
        .unwrap_or_default()
}

/// The channel itself.
struct Smtp {
    /// `host:port` of the egress proxy, the one route out.
    proxy: Option<String>,
    /// What the operator configured.
    configuration: Configuration,
    /// Keys already delivered. In memory and bounded; a restart forgets, which
    /// the `README` says rather than implying otherwise.
    delivered: Remembered,
}

#[tonic::async_trait]
impl NotificationChannel for Smtp {
    async fn describe(
        &self,
        _request: Request<NotificationDescribeRequest>,
    ) -> Result<Response<NotificationChannelDescriptor>, Status> {
        Ok(Response::new(NotificationChannelDescriptor {
            channel_key: "email".into(),
            name: "E-mail".into(),
            // Both spellings a subscription holds in practice, and they mean the
            // same thing.
            address_schemes: vec!["mailto".into(), "email".into()],
            supports_html: true,
        }))
    }

    async fn deliver(
        &self,
        request: Request<NotificationDeliverRequest>,
    ) -> Result<Response<NotificationDeliverResponse>, Status> {
        let message = request.into_inner();
        let context = message.context.unwrap_or_default();

        let proxy = self.proxy.as_deref().ok_or_else(|| {
            Status::failed_precondition(
                "this plugin has no egress proxy configured, so it has no route out at all",
            )
        })?;
        let server = self.configuration.server().ok_or_else(|| {
            Status::failed_precondition(format!(
                "this plugin is installed and not configured: {}",
                self.configuration.missing(Some(proxy)).join("; ")
            ))
        })?;
        let sender = self
            .configuration
            .sender
            .clone()
            .ok_or_else(|| Status::failed_precondition("no envelope sender is configured"))?;

        // INVALID_ARGUMENT and not UNAVAILABLE: "that is not an address" does not
        // become one by being repeated, so the core dead-letters it on the first
        // attempt rather than retrying for a day.
        let recipient = address::normalise(&message.recipient).map_err(Status::invalid_argument)?;

        if self.delivered.already(&message.idempotency_key) {
            return Ok(Response::new(NotificationDeliverResponse {
                provider_message_id: String::new(),
                detail: "already delivered".into(),
                deduplicated: true,
            }));
        }

        let attachments: Vec<mime::Attachment<'_>> = message
            .attachments
            .iter()
            .map(|attachment| mime::Attachment {
                file_name: &attachment.file_name,
                media_type: &attachment.media_type,
                content: &attachment.content,
            })
            .collect();

        let sender_name = context
            .settings
            .get(SENDER_NAME)
            .map(String::as_str)
            .unwrap_or("");

        let rendered = mime::render(&mime::Message {
            sender: &sender,
            sender_name,
            recipient: &recipient,
            subject: &message.subject,
            text: &message.text,
            html: &message.html,
            language: &message.language,
            idempotency_key: &message.idempotency_key,
            attachments: &attachments,
            now: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|since| since.as_secs())
                .unwrap_or(0),
        });

        match smtp::send(proxy, &server, &sender, &recipient, &rendered).await {
            Ok(accepted) => {
                // The host, never the recipient: a delivery log line is read by
                // an operator, and who was written to is the tenant's business
                // (REQ-SEC-066).
                info!(host = %server.host, "a message was accepted for delivery");
                Ok(Response::new(NotificationDeliverResponse {
                    provider_message_id: queue_id(&accepted),
                    detail: accepted,
                    deduplicated: false,
                }))
            }
            Err(reason) => {
                warn!(host = %server.host, reason = %reason, "a message could not be delivered");
                // A 5xx from the server is permanent and a 4xx is temporary --
                // the other way round from HTTP, and the reason this mapping is
                // written out rather than assumed.
                if reason.contains(": 5") {
                    Err(Status::invalid_argument(reason))
                } else {
                    Err(Status::unavailable(reason))
                }
            }
        }
    }
}

/// The queue id a server usually puts in its acceptance, for tracing a delivery.
///
/// Best effort by design: most servers answer `250 2.0.0 Ok: queued as ABC123`
/// and some answer `250 OK`. An id that is not there is an empty string rather
/// than a guess.
fn queue_id(reply: &str) -> String {
    reply
        .rsplit_once("queued as ")
        .map(|(_, id)| id.trim().to_string())
        .unwrap_or_default()
}

/// What this plugin says about itself when asked.
struct Health {
    /// What is missing before it can deliver; empty when it can.
    ready: Vec<String>,
}

#[tonic::async_trait]
impl PluginHealth for Health {
    async fn check(
        &self,
        _request: Request<HealthRequest>,
    ) -> Result<Response<HealthResponse>, Status> {
        let checks: Vec<Check> = if self.ready.is_empty() {
            vec![Check {
                name: "configured".into(),
                passed: true,
                detail: String::new(),
            }]
        } else {
            self.ready
                .iter()
                .map(|missing| Check {
                    name: "configured".into(),
                    passed: false,
                    detail: missing.clone(),
                })
                .collect()
        };

        Ok(Response::new(HealthResponse {
            state: if self.ready.is_empty() {
                HealthState::Ok as i32
            } else {
                // NOT_CONFIGURED and not a failure: the difference matters to an
                // operator, and `plugin-health-states.yaml` names it for this
                // reason (REQ-PLG-015).
                HealthState::NotConfigured as i32
            },
            detail: self.ready.join("; "),
            checks,
        }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn configured() -> Configuration {
        Configuration {
            host: Some("mail.example.org".into()),
            port: 587,
            security: Some(Security::StartTls),
            username: "inventory".into(),
            password: "a-password".into(),
            sender: Some("inventory@example.org".into()),
            ehlo_name: "home-inventory".into(),
            timeout: Duration::from_secs(30),
        }
    }

    #[test]
    fn a_complete_configuration_is_missing_nothing() {
        assert!(configured().missing(Some("egress-proxy:8118")).is_empty());
    }

    #[test]
    fn each_missing_piece_is_named_rather_than_summarised() {
        // REQ-PLG-015: an outbound plugin refuses service on a partial state and
        // says which part. "It is broken" is the diagnosis an operator can do
        // least with.
        let mut configuration = configured();
        configuration.host = None;
        configuration.sender = None;
        let missing = configuration.missing(None);
        assert_eq!(missing.len(), 3);
        assert!(missing
            .iter()
            .any(|line| line.contains("HOMEINV_SMTP_HOST")));
        assert!(missing
            .iter()
            .any(|line| line.contains("HOMEINV_SMTP_SENDER")));
        assert!(missing
            .iter()
            .any(|line| line.contains("HOMEINV_EGRESS_PROXY")));
    }

    #[test]
    fn a_user_name_without_a_password_is_a_missing_piece() {
        let mut configuration = configured();
        configuration.password = String::new();
        assert!(configuration
            .missing(Some("egress-proxy:8118"))
            .iter()
            .any(|line| line.contains("password file")));
    }

    #[test]
    fn an_unreadable_security_setting_is_missing_rather_than_assumed() {
        // Guessing here means guessing whether the credentials travel in the
        // clear, so nothing is guessed.
        let mut configuration = configured();
        configuration.security = None;
        assert!(configuration
            .missing(Some("egress-proxy:8118"))
            .iter()
            .any(|line| line.contains("starttls")));
        assert!(configuration.server().is_none());
    }

    #[test]
    fn the_queue_id_is_read_when_a_server_gives_one() {
        assert_eq!(queue_id("250 2.0.0 Ok: queued as 4X1Y2Z"), "4X1Y2Z");
        assert_eq!(queue_id("250 OK"), "");
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
