/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.application;

import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.api.AlreadyPendingDeletionException;
import de.greluc.homeinv.tenancy.api.RevocationUnusableException;
import de.greluc.homeinv.tenancy.api.TenantLifecycle;
import de.greluc.homeinv.tenancy.domain.Tenant;
import de.greluc.homeinv.tenancy.infrastructure.TenantRepository;
import de.greluc.homeinv.tenancy.infrastructure.TenantRevocationLookup;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asking for a tenant to be erased, and withdrawing the request (REQ-TEN-011, REQ-PRIV-005).
 *
 * <h2>Nothing is erased here</h2>
 *
 * <p>This is 05 §5.9's first half: a state change and a grace period. The tenant stops answering at
 * once and its data stays untouched, which is what makes the thirty days worth having. The second
 * half — the blocks erasing their share, reporting, and the certificate — runs after the period
 * elapses.
 *
 * <p>The token is 256 bits from {@link SecureRandom}, base64url so it survives a URL, and stored
 * only as its SHA-256 (REQ-SEC-048). Not a password hash: a password hash is slow because people
 * choose passwords, and nobody chose this one.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultTenantLifecycle implements TenantLifecycle {

  /** Bytes of randomness in a revocation token. 256 bits, so guessing is not a strategy. */
  private static final int TOKEN_BYTES = 32;

  private static final SecureRandom RANDOM = new SecureRandom();

  private final TenantRepository tenants;
  private final TenantRevocationLookup revocations;
  private final TenantRevocation revocation;
  private final Clock clock;

  /**
   * How long a tenant waits before it is erased.
   *
   * <p>Thirty days, which is what REQ-TEN-011 and REQ-PRIV-005 both say. Configurable so that a
   * test does not have to wait a month, and bounded below by nothing on purpose: an operator who
   * sets it to zero has decided their instance erases immediately, and the requirement is about the
   * shipped default rather than about forbidding a choice.
   */
  @Value("${HOMEINV_TENANT_ERASURE_GRACE_DAYS:30}")
  private long graceDays;

  @Override
  @Transactional(readOnly = true)
  public State state() {
    return State.valueOf(current().getLifecycleState());
  }

  @Override
  @Transactional
  public DeletionRequest requestDeletion(UUID actor) {
    Tenant tenant = current();
    Instant now = Instant.now(clock);

    if (tenant.isPendingDeletion()) {
      // Not a second token. Two live revocations would be two ways to withdraw
      // one request, and withdrawing with the one somebody remembers would leave
      // the other working.
      throw new AlreadyPendingDeletionException(
          tenant.getDeletionRequestedAt().plus(grace()));
    }

    String token = newToken();
    tenant.requestDeletion(hash(token), actor, now);

    log.warn(
        "Tenant {} was asked to be erased by {}; the grace period runs until {}.",
        tenant.getId(),
        actor,
        now.plus(grace()));
    return new DeletionRequest(token, now.plus(grace()));
  }

  @Override
  public UUID revokeDeletion(String revocationToken) {
    // The one lookup that runs with no tenant set: whoever follows the link
    // cannot sign in, because signing in is exactly what the pending deletion
    // stopped.
    UUID tenantId =
        revocations.locate(hash(revocationToken)).orElseThrow(RevocationUnusableException::new);
    return TenantContext.callAs(tenantId, () -> revocation.revoke(tenantId, grace()));
  }

  /**
   * The tenant the session is acting for.
   *
   * @return its row
   * @throws NotFoundException when there is none, which under row-level security means the context
   *     names a tenant this caller cannot see
   */
  private Tenant current() {
    UUID tenantId = TenantContext.require();
    return tenants
        .findById(tenantId)
        .orElseThrow(() -> new NotFoundException("tenant", tenantId));
  }

  /**
   * How long a request waits before it is carried out.
   *
   * @return the grace period
   */
  private Duration grace() {
    return Duration.ofDays(graceDays);
  }

  /**
   * A fresh revocation token.
   *
   * @return 256 bits of randomness, base64url without padding
   */
  private static String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * The SHA-256 of a token, in lower-case hexadecimal.
   *
   * @param token the token as presented
   * @return the hash the row stores
   */
  private static String hash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is not available in this JVM", impossible);
    }
  }
}
