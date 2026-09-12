/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.application;

import de.greluc.homeinv.identity.api.UserProvisioning;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates accounts.
 *
 * <p>The address is folded to lower case before it is stored or compared. An e-mail address's local
 * part is case-sensitive by the letter of RFC 5321 and is treated as insensitive by every mail
 * provider in practice; storing it as typed would let the same person create a second account by
 * capitalising one letter, and then fail to sign in with the other spelling.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultUserProvisioning implements UserProvisioning {

  private final AppUserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final Clock clock;

  @Override
  @Transactional
  public Optional<UUID> createIfAbsent(
      String email, String displayName, String locale, String password) {

    String address = email.trim().toLowerCase(Locale.ROOT);
    if (users.findByEmail(address).isPresent()) {
      // Not an error and not a warning: running this twice is the expected way to
      // deploy a stack a second time.
      log.info("An account for this address already exists; nothing to create.");
      return Optional.empty();
    }

    UUID userId = UUID.randomUUID();
    users.save(
        AppUser.create(
            userId,
            address,
            displayName,
            locale,
            passwordEncoder.encode(password),
            Instant.now(clock)));
    log.info("Created account {}.", userId);
    return Optional.of(userId);
  }
}
