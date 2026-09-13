/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.infrastructure;

import de.greluc.homeinv.identity.api.UserProvisioning;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.tenancy.api.AccountRegistry;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers {@code tenancy}'s questions about accounts, and creates one for an accepted invitation.
 *
 * <p>The port is declared in {@code tenancy} and implemented here, which is the direction that
 * keeps the blocks acyclic — this block already depends on that one for the login's tenant lookup.
 *
 * <p>Every read here crosses no tenant boundary, because there is none to cross: {@code
 * identity.app_user} is instance-wide (07 §7.1). What keeps it from being an enumeration surface is
 * the caller: {@code tenancy} asks about ids it read from its own RLS-protected membership rows, or
 * about an address that was written into an invitation by somebody who may invite.
 */
@Component
@RequiredArgsConstructor
public class AccountRegistryAdapter implements AccountRegistry {

  private final AppUserRepository users;
  private final UserProvisioning provisioning;
  private final RegistrationPolicy registration;

  @Override
  @Transactional(readOnly = true)
  public Optional<Account> byId(UUID userId) {
    return userId == null
        ? Optional.empty()
        : users.findById(userId).filter(user -> user.getDeletedAt() == null).map(
            AccountRegistryAdapter::accountOf);
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, Account> byIds(Collection<UUID> userIds) {
    Map<UUID, Account> accounts = new LinkedHashMap<>();
    if (userIds == null || userIds.isEmpty()) {
      return accounts;
    }
    for (AppUser user : users.findAllById(userIds)) {
      if (user.getDeletedAt() == null) {
        accounts.put(user.getId(), accountOf(user));
      }
    }
    return accounts;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Account> byEmail(String email) {
    return email == null || email.isBlank()
        ? Optional.empty()
        : users.findByEmail(email).map(AccountRegistryAdapter::accountOf);
  }

  @Override
  @Transactional
  public UUID register(String email, String displayName, String locale, String password) {
    // REQ-AUTH-004: on an instance that creates no accounts, an invitation for an
    // address nobody has creates none either. Checked here rather than in the
    // controller because this is the one place an account comes into existence
    // through an invitation, and a second caller must not be able to go round it.
    registration.requireRegistrationPermitted();
    return provisioning
        .createIfAbsent(email, displayName, locale, password)
        .orElseThrow(
            () ->
                // The caller checked, and between that check and this write somebody
                // else created the account. Refused rather than silently returning
                // the existing one: that would hand an invitation's acceptance to
                // whoever won the race.
                new IllegalStateException(
                    "An account for this address already exists; the invitation cannot create it."));
  }

  /**
   * The directory's view of an account.
   *
   * @param user the entity
   * @return the view, without the credential
   */
  private static Account accountOf(AppUser user) {
    return new Account(user.getId(), user.getEmail(), user.getDisplayName());
  }
}
