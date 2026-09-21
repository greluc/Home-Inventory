/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.infrastructure;

import de.greluc.homeinv.identity.api.RegistrationMode;
import de.greluc.homeinv.plugin.api.port.IdentityProvider;
import de.greluc.homeinv.plugin.api.port.NotificationChannel;
import de.greluc.homeinv.plugins.api.ExtensionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * An {@code open} instance has to be able to confirm an address (REQ-AUTH-004).
 *
 * <h2>Why this is a check and not a constructor</h2>
 *
 * <p>{@code RegistrationPolicy} refused {@code open} outright while the reason was true: confirming
 * an address needs a plugin, and there was no plugin runtime. There is one now (ADR-0028), and the
 * question is answerable — but only once the database is reachable, which a bean constructed during
 * the context's own startup cannot rely on. So it is asked at {@link ApplicationReadyEvent} and it
 * still <b>stops the instance</b>: an installation that offers sign-up it cannot complete is the
 * failure REQ-AUTH-004 names, and it is worse than one that refuses to start, because it is found
 * by the first person who tries.
 *
 * <h2>What counts as being able to confirm</h2>
 *
 * <p>Either of two things, and the requirement's own words are why: it asks for an account created
 * <i>"after the address is confirmed"</i> rather than for a particular mechanism.
 *
 * <ul>
 *   <li>a {@link NotificationChannel} — the mail sender, which confirms an address by sending a
 *       link to it and seeing it followed;
 *   <li>an {@link IdentityProvider} — which confirms one by <b>verifying a token that asserts it</b>
 *       (REQ-AUTH-005). That is a stronger confirmation than a mailed link and it arrives without
 *       this deployment sending anything.
 * </ul>
 *
 * <p>Neither installed means nothing here can establish that an address belongs to the person
 * typing it, and {@code open} would then be a mode that creates accounts on unverified claims.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenRegistrationReadiness {

  private final RegistrationPolicy policy;
  private final ExtensionRegistry extensions;

  /**
   * Stops an {@code open} instance that cannot confirm anything.
   *
   * @throws IllegalStateException when the mode is {@code open} and neither a notification channel
   *     nor an identity provider is installed for the instance
   */
  @EventListener(ApplicationReadyEvent.class)
  public void check() {
    if (policy.mode() != RegistrationMode.OPEN) {
      return;
    }
    boolean canSend = extensions.lookupForInstance(NotificationChannel.class).isPresent();
    boolean canFederate = extensions.lookupForInstance(IdentityProvider.class).isPresent();
    if (!canSend && !canFederate) {
      throw new IllegalStateException(
          "HOMEINV_REGISTRATION_MODE=open creates an account only after the address is confirmed"
              + " (REQ-AUTH-004), and nothing installed here can confirm one: no notification"
              + " channel to send a link through, and no identity provider to verify a token from."
              + " Install one of the two, or use invite_only or closed.");
    }
    log.info(
        "Open registration is served by {}",
        canSend && canFederate
            ? "a notification channel and an identity provider"
            : canSend ? "a notification channel" : "an identity provider");
  }
}
