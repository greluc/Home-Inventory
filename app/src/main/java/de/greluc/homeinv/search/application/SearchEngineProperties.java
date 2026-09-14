/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.search.application;

import java.net.URI;
import java.net.URISyntaxException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which engine answers a search, and what it needs to be reached (REQ-SRCH-005, ADR-0008).
 *
 * <p>In {@code application} and not in {@code infrastructure}, although it reads environment
 * variables: what it holds is a <b>decision</b> — which engine answers — and a decision is not an
 * adapter's to take (ADR-0010). The client that reaches OpenSearch is infrastructure and depends
 * on this, which is the direction that dependency goes in.
 *
 * <h2>A switch, not a probe</h2>
 *
 * <p>{@code HOMEINV_SEARCH_ENGINE} is {@code postgresql} or {@code opensearch}, and the default is
 * {@code postgresql} because that is the engine every profile has. The {@code minimal} profile
 * never gets OpenSearch (ADR-0008) and says so by leaving the variable unset.
 *
 * <p>Deliberately not "use OpenSearch if it answers". Decided with the owner on 2026-09-14: that
 * would erase the difference between an installation deliberately running without it and one whose
 * index is down — and the second is exactly what {@code meta.degraded} exists to report
 * (ADR-0039). An installation that never had OpenSearch is not degraded; it is small.
 *
 * <h2>A missing setting aborts startup</h2>
 *
 * <p>Choosing {@code opensearch} without a URL, a user or a password file is a configuration that
 * cannot work, and it fails here with a message naming what is missing rather than on the first
 * search. There is no fallback to {@code postgresql} in that case: an operator who asked for
 * OpenSearch and silently got the other engine has a deployment that is not the one they
 * described, and would find out from a performance report months later (CLAUDE.md, Security).
 *
 * <p>The password arrives as a <b>file</b> like every other secret, through
 * {@link de.greluc.homeinv.platform.SecretFiles}: an environment variable names the path, never the
 * value (REQ-SEC-050).
 */
@Slf4j
@Component
public final class SearchEngineProperties {

  /**
   * The engines this product ships.
   *
   * <p>An enum and {@code valueOf} rather than two string constants and {@code equals}, which is
   * how {@code RegistrationPolicy} reads its own setting — and what keeps a case fold away from a
   * comparison, which find-sec-bugs flags as {@code IMPROPER_UNICODE} for a good reason.
   */
  public enum Engine {
    /** The shipped fallback, and the only search the {@code minimal} profile has. */
    POSTGRESQL("postgresql"),
    /** The primary engine of ADR-0008, in the {@code standard} and {@code ha} profiles. */
    OPENSEARCH("opensearch");

    private final String token;

    Engine(String token) {
      this.token = token;
    }

    /**
     * What the engine calls itself, which is what {@code SearchIndex.name()} answers.
     *
     * <p>A constant rather than {@code name().toLowerCase()}: folding a name in order to compare it
     * is what find-sec-bugs flags as {@code IMPROPER_UNICODE}, and it is right to — the fold is
     * not needed when the spelling is written down once.
     *
     * @return the lower-case token
     */
    public String token() {
      return token;
    }
  }

  /** Which engine was chosen. */
  @Getter private final Engine engine;

  /** Where OpenSearch is, or {@code null} when it was not chosen. */
  @Getter private final URI url;

  /** The OpenSearch user, or {@code null} when it was not chosen. */
  @Getter private final String username;

  /** That user's password, or {@code null} when it was not chosen. */
  @Getter private final String password;

  /**
   * The SHA-256 fingerprint of the one certificate OpenSearch may present, or {@code null}.
   *
   * <p>Pinned rather than CA-validated, for the reason {@code HOMEINV_BLOBSTORE_FINGERPRINT} is:
   * the deployment's CA signs every service and every plugin, so trusting it alone would let any of
   * them answer as the index (REQ-SEC-056, ADR-0044).
   *
   * <p>Empty is allowed and means "no TLS pin", which is only ever a test's shape — a URL that is
   * not {@code https} does not reach a pinned connection at all.
   */
  @Getter private final String fingerprint;

  /**
   * Whether the connection to OpenSearch is TLS, and therefore pinned.
   *
   * <p>Decided once, here, from the URL's scheme. Everything that is not plainly {@code http} is
   * treated as TLS: the one fold in this class can then only make the connection <b>stricter</b>,
   * never weaker, which is the direction a mistake should go in.
   */
  @Getter private final boolean tls;

  /**
   * Reads the choice and refuses one that cannot work.
   *
   * @param engine {@code postgresql} or {@code opensearch}
   * @param url where OpenSearch listens, required when it was chosen
   * @param username the search user, required when it was chosen
   * @param password that user's password, from a mounted file, required when it was chosen
   * @param fingerprint the certificate OpenSearch must present, required for an {@code https} URL
   * @throws IllegalStateException when the engine is not one of the two, or when OpenSearch was
   *     chosen and something it needs is missing
   */
  public SearchEngineProperties(
      @Value("${homeinv.search.engine:postgresql}") String engine,
      @Value("${homeinv.search.url:}") String url,
      @Value("${homeinv.search.username:}") String username,
      @Value("${homeinv.search.password:}") String password,
      @Value("${homeinv.search.fingerprint:}") String fingerprint) {
    try {
      this.engine =
          Engine.valueOf(
              (engine == null ? "" : engine.trim()).toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      throw new IllegalStateException(
          "HOMEINV_SEARCH_ENGINE is '"
              + engine
              + "'; it is 'postgresql' or 'opensearch'. Leave it unset for the profile that has no"
              + " OpenSearch.",
          unknown);
    }

    if (this.engine == Engine.POSTGRESQL) {
      this.url = null;
      this.username = null;
      this.password = null;
      this.fingerprint = null;
      this.tls = false;
      log.info("Search is answered by PostgreSQL; no OpenSearch is configured.");
      return;
    }

    require(url, "HOMEINV_SEARCH_URL");
    require(username, "HOMEINV_SEARCH_USER");
    // Named by its variable and not by its value, which never appears in a log
    // line, a message or a crash dump (REQ-SEC-050).
    require(password, "HOMEINV_SEARCH_PASSWORD_FILE");
    try {
      this.url = new URI(url.trim());
    } catch (URISyntaxException notAUrl) {
      throw new IllegalStateException(
          "HOMEINV_SEARCH_URL is not a URL: " + url, notAUrl);
    }
    this.username = username.trim();
    this.password = password;
    this.fingerprint = de.greluc.homeinv.platform.PinnedCertificate.normalise(fingerprint);
    // Anything that is not plainly `http` is TLS. Stated that way round on
    // purpose: a scheme this does not recognise becomes a pinned connection that
    // refuses to be made, rather than a plaintext one that is quietly made.
    this.tls =
        !"http".equals(
            this.url.getScheme() == null
                ? ""
                : this.url.getScheme().toLowerCase(java.util.Locale.ROOT));
    if (this.tls && this.fingerprint.isEmpty()) {
      throw new IllegalStateException(
          "HOMEINV_SEARCH_FINGERPRINT is required for an https OpenSearch URL. The deployment's CA"
              + " signs every service and every plugin, so trusting it alone would let any of them"
              + " answer as the index (ADR-0044).");
    }
    log.info("Search is answered by OpenSearch at {}.", this.url);
  }

  /**
   * Whether OpenSearch was chosen.
   *
   * @return true when this installation is meant to have an index
   */
  public boolean usesOpenSearch() {
    return engine == Engine.OPENSEARCH;
  }

  /**
   * Refuses a setting OpenSearch cannot do without.
   *
   * @param value what was configured
   * @param variable the environment variable to name in the refusal
   * @throws IllegalStateException when it is missing
   */
  private static void require(String value, String variable) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          variable
              + " is required when HOMEINV_SEARCH_ENGINE is 'opensearch'. There is no fallback to"
              + " PostgreSQL here: an operator who asked for OpenSearch and silently got the other"
              + " engine has a deployment that is not the one they described.");
    }
  }
}
