/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.application;

import de.greluc.homeinv.crypto.api.SensitiveValues;
import de.greluc.homeinv.notification.api.Notifications;
import de.greluc.homeinv.notification.api.WebhookTargets;
import de.greluc.homeinv.notification.infrastructure.WebhookTargetQueries;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.platform.Versions;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Webhook targets (REQ-API-010).
 *
 * <p>Thin, like the reminder rules beside it: a target is data. The three judgements it does make
 * are all about the value a tenant supplies and something else then connects to, so all three are
 * made <b>when the target is written</b>, by the person who can still fix them, rather than when a
 * delivery fails hours later:
 *
 * <ul>
 *   <li>the URL is {@code https} and is a URL at all (REQ-SEC-034);
 *   <li>the signing secret is long enough to be one — an unsigned or weakly signed webhook is one
 *       anybody who learns the URL can forge;
 *   <li>every event type is one this deployment raises, so a subscription cannot be silently empty.
 * </ul>
 *
 * <h2>The secret is sealed here and opened nowhere else</h2>
 *
 * <p>{@link SensitiveValues} binds the ciphertext to the target's id and the key below, so it
 * cannot be moved to another target or another tenant and still open (ADR-0019). It is written and
 * never read back to a person: {@link WebhookTargets.WebhookTargetView} has no field for it. The
 * one reader is {@link DeliveryRunner}, which puts it into the envelope of the one call that needs
 * it (ADR-0077).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultWebhookTargets implements WebhookTargets {

  /**
   * The field key the secret is sealed under.
   *
   * <p>Spelled as the plugin's manifest spells the setting, because that is what it arrives as on
   * the far side of the call. One name for one thing.
   */
  public static final String SIGNING_SECRET = "webhook.signingSecret";

  /**
   * The shortest secret that is one.
   *
   * <p>Thirty-two characters, which is what a generated key looks like in every documented example.
   * Not a password policy: nobody types this, it is agreed between two systems, and the only reason
   * a short one appears is that somebody typed a word.
   */
  private static final int SHORTEST_SECRET = 32;

  /**
   * The one scheme a webhook target may have, matched the way RFC 3986 means it.
   *
   * <p>A regular expression rather than {@code equalsIgnoreCase} or {@code toLowerCase}, and the
   * reason is not style. Both of those fold <b>Unicode</b>: U+212A KELVIN SIGN lower-cases to
   * {@code k}, and under a Turkish locale {@code I} folds to a dotless {@code ı} — which is why
   * find-sec-bugs refuses them (IMPROPER_UNICODE) on any value that is then compared. {@link
   * java.util.regex.Pattern#CASE_INSENSITIVE} <b>without</b> {@code UNICODE_CASE} folds US-ASCII
   * and nothing else, which is exactly what a scheme is: RFC 3986 restricts it to ASCII letters
   * and allows either case, so the case must be accepted rather than demanded.
   *
   * <p>This is the one value in the system a <b>tenant</b> chooses and that something then opens a
   * connection to, so it is worth the sentence.
   */
  private static final Pattern HTTPS_SCHEME = Pattern.compile("https", Pattern.CASE_INSENSITIVE);

  private final WebhookTargetQueries targets;
  private final SensitiveValues sealing;

  @Override
  @Transactional(readOnly = true)
  public List<WebhookTargetView> list() {
    return targets.targets(TenantContext.require());
  }

  @Override
  @Transactional(readOnly = true)
  public WebhookTargetView get(UUID id) {
    return targets
        .target(TenantContext.require(), id)
        .orElseThrow(() -> new NotFoundException("webhook target", id));
  }

  @Override
  @Transactional
  public WebhookTargetView create(NewWebhookTarget command, UUID actor) {
    UUID tenantId = TenantContext.require();
    check(command, true);
    requireUrlFree(tenantId, command.url(), null);

    // Generated here and not by the column default: the secret is sealed against
    // this id, so the id has to exist before the row does.
    UUID id = UUID.randomUUID();
    targets.insert(
        tenantId,
        id,
        command.url().strip(),
        blankToNull(command.description()),
        command.eventTypes(),
        sealing.seal(id, SIGNING_SECRET, command.signingSecret()),
        command.enabled(),
        actor);
    log.info(
        "A webhook target was created for {} event type(s); deliveries are signed per target",
        command.eventTypes().size());
    return targets.target(tenantId, id).orElseThrow();
  }

  @Override
  @Transactional
  public WebhookTargetView update(
      UUID id, NewWebhookTarget command, OptionalLong expectedVersion, UUID actor) {
    UUID tenantId = TenantContext.require();
    WebhookTargetView current =
        targets.target(tenantId, id).orElseThrow(() -> new NotFoundException("webhook target", id));
    Versions.requireCurrent("webhook target", id, expectedVersion, current.version());
    check(command, false);
    requireUrlFree(tenantId, command.url(), id);

    targets.update(
        tenantId,
        id,
        command.url().strip(),
        blankToNull(command.description()),
        command.eventTypes(),
        command.signingSecret() == null || command.signingSecret().isBlank()
            ? null
            : sealing.seal(id, SIGNING_SECRET, command.signingSecret()),
        command.enabled(),
        actor);
    return targets.target(tenantId, id).orElseThrow();
  }

  @Override
  @Transactional
  public void remove(UUID id, UUID actor) {
    UUID tenantId = TenantContext.require();
    if (targets.remove(tenantId, id) == 0) {
      throw new NotFoundException("webhook target", id);
    }
    log.info("A webhook target was removed, with everything that had been delivered to it");
  }

  @Override
  @Transactional(readOnly = true)
  public List<Notifications.QueuedNotification> deliveries(UUID id, int limit) {
    UUID tenantId = TenantContext.require();
    // Asked first, so that another tenant's id is a 404 rather than an empty
    // list -- which would be a "this exists and is quiet" that REQ-SEC-025 does
    // not allow us to say.
    targets.target(tenantId, id).orElseThrow(() -> new NotFoundException("webhook target", id));
    return targets.deliveries(tenantId, id, limit);
  }

  /**
   * Refuses a second target at an address this tenant already uses.
   *
   * @param tenantId whose
   * @param url the submitted address
   * @param exceptId the target being updated, or {@code null} on a create
   */
  private void requireUrlFree(UUID tenantId, String url, UUID exceptId) {
    if (targets.urlTaken(tenantId, url.strip(), exceptId)) {
      throw new WebhookTargetUrlTakenException(url.strip());
    }
  }

  /**
   * Refuses what a delivery run could only discover later.
   *
   * @param command what was submitted
   * @param secretRequired whether the secret must be present, which it is on a create and is not on
   *     an update that keeps the stored one
   */
  private void check(NewWebhookTarget command, boolean secretRequired) {
    if (command.eventTypes() == null || command.eventTypes().isEmpty()) {
      throw new IllegalArgumentException(
          "A webhook target that subscribes to nothing would never be delivered to. Name at least"
              + " one event type.");
    }
    requireHttpsUrl(command.url());

    String secret = command.signingSecret();
    if (secret == null || secret.isBlank()) {
      if (secretRequired) {
        throw new IllegalArgumentException(
            "A webhook target needs a signing secret. An unsigned delivery is one anybody who"
                + " learns the URL can forge, so nothing is sent without one.");
      }
      return;
    }
    if (secret.strip().length() < SHORTEST_SECRET) {
      throw new IllegalArgumentException(
          "A webhook signing secret is at least "
              + SHORTEST_SECRET
              + " characters. It is agreed between two systems and typed by nobody, so there is no"
              + " reason for a short one.");
    }
  }

  /**
   * Refuses anything that is not an absolute {@code https} URL (REQ-SEC-034).
   *
   * <p>The database checks the prefix too, and the plugin checks the whole thing again including
   * the address it resolves to. Three checks on one value, deliberately: this is the only value in
   * the system that a <b>tenant</b> chooses and that something then opens a connection to.
   *
   * @param url what was submitted
   */
  private void requireHttpsUrl(String url) {
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("A webhook target needs a URL.");
    }
    URI parsed;
    try {
      parsed = new URI(url.strip());
    } catch (URISyntaxException notAUrl) {
      throw new IllegalArgumentException("That is not a URL: " + notAUrl.getReason(), notAUrl);
    }
    if (parsed.getScheme() == null
        || !HTTPS_SCHEME.matcher(parsed.getScheme()).matches()
        || parsed.getHost() == null) {
      throw new IllegalArgumentException(
          "A webhook target is an absolute https URL. Plain http would carry the payload and its"
              + " signature across the internet in clear text, and the plugin refuses it as well.");
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
