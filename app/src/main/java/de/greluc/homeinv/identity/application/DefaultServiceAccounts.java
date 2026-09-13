/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.application;

import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.identity.api.ServiceAccounts;
import de.greluc.homeinv.identity.domain.ServiceAccount;
import de.greluc.homeinv.identity.infrastructure.ServiceAccountRepository;
import de.greluc.homeinv.identity.infrastructure.ServiceAccountTokens;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, lists, revokes and authenticates service accounts (REQ-AUTH-010).
 *
 * <h2>The token</h2>
 *
 * <p>Thirty-two random bytes behind a fixed prefix. The prefix is not decoration: a secret scanner
 * — and the {@code gitleaks} run this repository already has — recognises a credential by its
 * shape, and a bare base64 string in a configuration file looks like every other bare base64
 * string. What is stored is the SHA-256 of the whole token and never the token, so "shown exactly
 * once" is a property of the storage rather than a promise in a screen.
 *
 * <p>SHA-256 rather than Argon2id, and deliberately: a token is thirty-two random bytes, so there
 * is no dictionary to slow down, and it is presented on <em>every</em> request a machine makes —
 * 19 MiB of hashing per call would be a denial of service with a valid credential.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultServiceAccounts implements ServiceAccounts {

  /** What every token starts with, so a scanner can recognise one. */
  public static final String PREFIX = "homeinv_sa_";

  /** Thirty-two bytes: as much entropy as the session id, and not guessable in any number of tries. */
  private static final int TOKEN_BYTES = 32;

  private static final SecureRandom RANDOM = new SecureRandom();

  private final ServiceAccountRepository accounts;
  private final ServiceAccountTokens tokens;
  private final Clock clock;

  @Override
  @Transactional(readOnly = true)
  public List<ServiceAccountView> all(int limit) {
    return accounts.findLive(TenantContext.require(), org.springframework.data.domain.Limit.of(limit))
        .stream()
        .map(DefaultServiceAccounts::viewOf)
        .toList();
  }

  @Override
  @Transactional
  public IssuedServiceAccount issue(
      String name,
      String description,
      String role,
      UUID roleDefinitionId,
      Instant expiresAt,
      UUID actor) {

    Instant now = Instant.now(clock);
    if (!expiresAt.isAfter(now)) {
      throw new IllegalArgumentException("A service account's expiry must be in the future.");
    }

    String token = PREFIX + randomPart();
    ServiceAccount account =
        ServiceAccount.issue(
            TenantContext.require(),
            name,
            description,
            role,
            roleDefinitionId,
            hash(token),
            expiresAt,
            actor,
            now);
    accounts.save(account);
    log.info(
        "Service account {} issued for tenant {} as {} until {}, by {}",
        account.getId(),
        account.getTenantId(),
        role,
        expiresAt,
        actor);
    return new IssuedServiceAccount(viewOf(account), token);
  }

  @Override
  @Transactional
  public void revoke(UUID id, UUID actor) {
    ServiceAccount account =
        accounts
            .findById(id)
            .filter(candidate -> candidate.getDeletedAt() == null)
            .orElseThrow(() -> new NotFoundException("service account", id));
    account.revoke(actor, Instant.now(clock));
    log.warn("Service account {} was revoked by {}", id, actor);
  }

  @Override
  @Transactional
  public Optional<AuthenticatedUser> authenticate(String token) {
    if (token == null || !token.startsWith(PREFIX)) {
      return Optional.empty();
    }
    Instant now = Instant.now(clock);
    Optional<ServiceAccountTokens.TokenHolder> holder = tokens.byToken(hash(token));
    if (holder.isEmpty() || !holder.get().isUsable(now)) {
      // One answer for unknown, revoked and expired. Telling them apart says
      // which tokens once existed, which is the same reasoning REQ-SEC-016
      // applies to addresses.
      return Optional.empty();
    }

    ServiceAccountTokens.TokenHolder found = holder.get();
    // The last-used stamp is written inside the token's own tenant context: the
    // row is tenant-scoped, and the policy is what makes the update touch this
    // tenant's row and no other.
    TenantContext.runAs(
        found.tenantId(),
        () -> accounts.findById(found.id()).ifPresent(account -> account.used(now)));

    return Optional.of(
        new AuthenticatedUser(
            found.id(),
            found.tenantId(),
            // No address: a service account has none, and the field is what the
            // interface shows and the log records. The id is what identifies it.
            "service-account:" + found.id(),
            "en",
            found.role(),
            found.roleDefinitionId(),
            null,
            true));
  }

  /**
   * The random half of a token.
   *
   * @return thirty-two bytes, base64url
   */
  private static String randomPart() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * The SHA-256 of a token, as it is stored and looked up.
   *
   * @param token the token as presented
   * @return the hash, lower-case hexadecimal
   */
  private static String hash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable in this runtime.", impossible);
    }
  }

  /**
   * What a stored account looks like to its tenant.
   *
   * @param account the stored account
   * @return the view, which never carries the token
   */
  private static ServiceAccountView viewOf(ServiceAccount account) {
    return new ServiceAccountView(
        account.getId(),
        account.getName(),
        account.getDescription(),
        account.getRole(),
        account.getRoleDefinitionId(),
        account.getExpiresAt(),
        account.getLastUsedAt());
  }
}
