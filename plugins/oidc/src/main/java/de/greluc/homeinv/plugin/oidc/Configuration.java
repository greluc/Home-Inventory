/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugin.oidc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * What the operator configured for this container.
 *
 * <h2>Why there is no per-tenant half</h2>
 *
 * <p>Every other first-party plugin is configured twice (ADR-0073): the operator's half in the
 * container, a tenant's half in the call envelope. This one is not, and the reason is what a
 * sign-in <b>is</b>: it happens before any tenant is known — it is the step that decides which
 * memberships a session may act on — so there is no tenant whose settings could be read. The core
 * resolves this plugin through the <b>instance operator's</b> grant for the same reason
 * (ADR-0066).
 *
 * <p>A tenant-specific provider would therefore be a different feature with a different flow: the
 * person would have to say which tenant they are signing in to before they are anybody at all.
 *
 * @param issuer the provider's issuer URL, which is also where discovery starts
 * @param clientId this deployment's client at that provider
 * @param clientSecret its secret, or empty for a public client authenticating with PKCE alone
 * @param providerKey the stable key the core names this configuration by
 * @param displayName what the sign-in button says
 * @param scopes what to ask for, always including {@code openid}
 * @param proxy {@code host:port} of the egress proxy, or null — the one route out
 * @param timeout how long one call to the provider may take
 */
public record Configuration(
    String issuer,
    String clientId,
    String clientSecret,
    String providerKey,
    String displayName,
    List<String> scopes,
    String proxy,
    Duration timeout) {

  /** Where the runtime mounts the client secret. */
  private static final String DEFAULT_SECRET_FILE = "/run/secrets/plugin-oidc-client-secret";

  /** What is asked for when the operator names nothing: the minimum that identifies somebody. */
  private static final String DEFAULT_SCOPES = "openid email profile";

  /** How long one call to the provider may take. */
  private static final long DEFAULT_TIMEOUT_SECONDS = 20;

  /**
   * Reads it from the environment and the mounted secret.
   *
   * @return the configuration, however incomplete — {@link #missing()} says what is not there
   */
  public static Configuration fromEnvironment() {
    return new Configuration(
        trimmed(System.getenv("HOMEINV_OIDC_ISSUER")),
        trimmed(System.getenv("HOMEINV_OIDC_CLIENT_ID")),
        readSecret(
            orDefault(System.getenv("HOMEINV_OIDC_CLIENT_SECRET_FILE"), DEFAULT_SECRET_FILE)),
        orDefault(trimmed(System.getenv("HOMEINV_OIDC_PROVIDER_KEY")), "oidc"),
        orDefault(trimmed(System.getenv("HOMEINV_OIDC_DISPLAY_NAME")), "Single sign-on"),
        List.of(orDefault(trimmed(System.getenv("HOMEINV_OIDC_SCOPES")), DEFAULT_SCOPES).split(" ")),
        trimmed(System.getenv("HOMEINV_EGRESS_PROXY")),
        Duration.ofSeconds(
            parseOr(
                System.getenv("HOMEINV_OIDC_TIMEOUT_SECONDS"), DEFAULT_TIMEOUT_SECONDS)));
  }

  /**
   * What stands between this plugin and being able to sign anybody in.
   *
   * <p>Returned rather than logged, so that the same list answers the health check: REQ-PLG-015
   * asks an outbound plugin to verify its target completely and to refuse service on a partial
   * state, and "it is broken" is the diagnosis an operator can do least with.
   *
   * @return one sentence per missing piece, each naming what to set
   */
  public List<String> missing() {
    List<String> missing = new ArrayList<>();
    if (isBlank(issuer)) {
      missing.add("HOMEINV_OIDC_ISSUER names no provider");
    } else if (!issuer.startsWith("https://")) {
      // Not a style rule: discovery, the token endpoint and the key set are all
      // reached from this value, and an `http://` issuer would send a client
      // secret and an authorization code over a connection nothing protects.
      missing.add("HOMEINV_OIDC_ISSUER is not an https:// URL, and everything else follows it");
    }
    if (isBlank(clientId)) {
      missing.add("HOMEINV_OIDC_CLIENT_ID names no client");
    }
    if (isBlank(proxy)) {
      missing.add(
          "HOMEINV_EGRESS_PROXY is not set, and a plugin segment has no route out of the"
              + " deployment on its own (ADR-0026)");
    }
    return missing;
  }

  /**
   * Whether this deployment authenticates itself with a secret.
   *
   * <p>A public client is a supported configuration: PKCE is what binds the authorization code to
   * this deployment, and a provider that issues no secret is one an operator may still use.
   *
   * @return {@code true} when a secret was mounted
   */
  public boolean isConfidential() {
    return !isBlank(clientSecret);
  }

  /**
   * The scopes as the authorization request spells them.
   *
   * @return a space-separated list, always containing {@code openid}
   */
  public String scopeParameter() {
    List<String> asked = new ArrayList<>(scopes);
    if (!asked.contains("openid")) {
      // Without it the provider runs a plain OAuth flow and returns no ID token,
      // which is the only thing this plugin trusts. Added rather than refused:
      // an operator who left it out meant to sign somebody in.
      asked.add(0, "openid");
    }
    return String.join(" ", asked);
  }

  private static String trimmed(String value) {
    return value == null ? null : value.trim();
  }

  private static String orDefault(String value, String fallback) {
    return isBlank(value) ? fallback : value;
  }

  private static long parseOr(String value, long fallback) {
    try {
      return isBlank(value) ? fallback : Long.parseLong(value.trim());
    } catch (NumberFormatException notANumber) {
      return fallback;
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /**
   * Reads a mounted secret, trimming the newline a file usually ends with.
   *
   * <p>Absent and empty are the same answer: {@code setup.sh} creates this file EMPTY on purpose,
   * because a client secret belongs to somebody else's system and is not one this deployment may
   * invent.
   */
  private static String readSecret(String path) {
    try {
      return Files.readString(Path.of(path), StandardCharsets.UTF_8).trim();
    } catch (IOException | RuntimeException absent) {
      return "";
    }
  }
}
