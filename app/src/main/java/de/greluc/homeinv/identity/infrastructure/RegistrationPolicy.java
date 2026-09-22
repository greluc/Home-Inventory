/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.infrastructure;

import de.greluc.homeinv.identity.api.RegistrationClosedException;
import de.greluc.homeinv.identity.api.RegistrationMode;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * What {@code HOMEINV_REGISTRATION_MODE} means to the code that creates accounts (REQ-AUTH-004).
 *
 * <h2>Why an unknown value stops the instance</h2>
 *
 * <p>A misspelled mode has one safe reading and one dangerous one, and no way to tell which the
 * operator meant. Treating it as {@code invite_only} would quietly close an instance somebody meant
 * to open; treating it as {@code open} would open one somebody meant to close. Refusing to start is
 * the only answer that cannot be wrong, and it is the treatment every other security-relevant
 * setting here gets (REQ-NFR-046).
 *
 * <h2>Why {@code open} does the same, for now</h2>
 *
 * <p>Open registration confirms the address before the account counts, and confirming an address
 * means sending mail — which the core does not do: it goes through {@code plugin-smtp}
 * (ADR-0026). Until that plugin exists, an instance configured for {@code open} would offer a
 * sign-up nobody could ever complete. It says so at startup instead.
 */
@Component
@Slf4j
public class RegistrationPolicy {

  private final RegistrationMode mode;

  /**
   * Reads the setting and refuses what cannot be served.
   *
   * @param configured the value of {@code HOMEINV_REGISTRATION_MODE}
   * @throws IllegalStateException when the value names no mode. Whether an {@code open}
   *     instance can confirm an address is asked by {@code OpenRegistrationReadiness},
   *     which runs when the registry is readable
   */
  public RegistrationPolicy(
      @Value("${homeinv.registration-mode:invite_only}") String configured) {
    String normalised = configured == null ? "" : configured.trim().toUpperCase(Locale.ROOT);
    try {
      this.mode = RegistrationMode.valueOf(normalised);
    } catch (IllegalArgumentException unknown) {
      throw new IllegalStateException(
          "HOMEINV_REGISTRATION_MODE is '"
              + configured
              + "', which is not one of invite_only, open or closed. It decides who may come to"
              + " exist on this instance, so it is not guessed at.",
          unknown);
    }

    // `open` used to be refused here outright, because confirming an address needs
    // a plugin and there was no plugin runtime. There is one now (ADR-0028), so the
    // refusal moved to where it can look: `OpenRegistrationReadiness` asks, once the
    // application is up and the registry is readable, whether anything installed can
    // confirm an address at all. A constructor cannot ask that — it runs before the
    // database is reachable — and a mode parsed here and checked there is one
    // decision in two places rather than two decisions.
    log.info("Registration mode: {}", mode);
  }

  /**
   * The mode this instance runs in.
   *
   * @return the mode
   */
  public RegistrationMode mode() {
    return mode;
  }

  /**
   * Refuses to create an account where the mode says none is created.
   *
   * @throws RegistrationClosedException when the mode is {@code closed}
   */
  public void requireRegistrationPermitted() {
    if (mode == RegistrationMode.CLOSED) {
      throw new RegistrationClosedException();
    }
  }
}
