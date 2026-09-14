/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.application;

import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.authorization.api.RoleRef;
import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.api.AccountRegistry;
import de.greluc.homeinv.tenancy.api.InvitationAlreadyOpenException;
import de.greluc.homeinv.tenancy.api.InvitationService;
import de.greluc.homeinv.tenancy.api.InvitationUnusableException;
import de.greluc.homeinv.tenancy.domain.Invitation;
import de.greluc.homeinv.tenancy.infrastructure.InvitationLookupAdapter;
import de.greluc.homeinv.tenancy.infrastructure.InvitationRepository;
import de.greluc.homeinv.tenancy.infrastructure.MembershipRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issuing, withdrawing and redeeming invitations (REQ-TEN-004, REQ-AUTH-004).
 *
 * <h2>The token</h2>
 *
 * <p>256 bits from {@link SecureRandom}, base64url without padding so it survives a URL, and stored
 * only as its SHA-256 (REQ-SEC-048). SHA-256 rather than Argon2id on purpose: a password hash is
 * slow because people choose passwords, and nobody chose this one — there is no dictionary to walk
 * and nothing to slow down.
 *
 * <h2>Why redeeming opens its own tenant context</h2>
 *
 * <p>Nobody is signed in yet, and the tenant is not known until the token has been looked up. The
 * lookup runs through the {@code SECURITY DEFINER} function of 07 §7.5; everything after it runs
 * inside {@code TenantContext.runAs}, so the invitation, the membership and the tenant's own row
 * are read and written under the ordinary policies. The context comes from the token rather than
 * from anything the caller said, which is the distinction {@code REQ-SEC-004} draws.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultInvitationService implements InvitationService {

  /** The cap REQ-NFR-010 puts on every page of every collection. */
  private static final int MAX_PAGE = 200;

  /** What an invitation cursor is bound to. */
  private static final String CURSOR = "tenant-invitations";

  /**
   * How long an invitation works.
   *
   * <p>REQ-TEN-004 says time-limited and does not say how long; a week is long enough to survive a
   * holiday and short enough that a forwarded mail from last year opens nothing. An administrator
   * whose invitation ran out issues another, which is one act rather than a setting to get wrong.
   */
  private static final Duration VALIDITY = Duration.ofDays(7);

  /** Bytes of randomness in a token. 256 bits, so guessing is not a strategy. */
  private static final int TOKEN_BYTES = 32;

  private static final SecureRandom RANDOM = new SecureRandom();

  private final InvitationRepository invitations;
  private final InvitationLookupAdapter lookup;
  private final InvitationRedemption redemption;
  private final MembershipRepository memberships;
  private final AccountRegistry accounts;
  private final RoleGrantRules grants;
  private final CursorCodec cursors;
  private final Clock clock;

  @Override
  @Transactional
  public IssuedInvitation invite(String email, String role, UUID actor) {
    UUID tenantId = TenantContext.require();
    String address = email.trim().toLowerCase(Locale.ROOT);
    Instant now = Instant.now(clock);

    grants.requireGrantable(roleOf(actor), RoleRef.of(role));

    invitations
        .findOpenFor(address, now)
        .ifPresent(open -> {
          throw new InvitationAlreadyOpenException(open.getId());
        });

    // Somebody who is already here does not need an invitation. Issuing one would
    // produce a token that spends itself on nothing, and an administrator would
    // read the resulting "accepted" as somebody having joined.
    accounts
        .byEmail(address)
        .flatMap(account -> memberships.findLiveInTenant(account.id()))
        .ifPresent(
            existing -> {
              throw new InvitationAlreadyOpenException(null);
            });

    String token = newToken();
    Invitation invitation =
        Invitation.issue(
            UUID.randomUUID(),
            tenantId,
            address,
            role,
            hash(token),
            now.plus(VALIDITY),
            actor,
            now);
    invitations.save(invitation);

    log.info("Invitation {} issued for tenant {} as {} by {}", invitation.getId(), tenantId, role,
        actor);
    return new IssuedInvitation(viewOf(invitation, now), token);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<InvitationView> invitations(String cursor, int limit) {
    int size = Math.clamp(limit, 1, MAX_PAGE);
    Instant now = Instant.now(clock);

    List<Invitation> rows;
    if (cursor == null || cursor.isBlank()) {
      rows = invitations.findPage(Limit.of(size));
    } else {
      CursorCodec.Position from = cursors.decode(cursor, CURSOR);
      rows = invitations.findPageAfter(from.createdAt(), from.id(), Limit.of(size));
    }

    String next =
        rows.size() == size
            ? cursors.encode(
                new CursorCodec.Position(rows.getLast().getCreatedAt(), rows.getLast().getId()),
                CURSOR)
            : null;
    return Page.of(rows.stream().map(row -> viewOf(row, now)).toList(), next);
  }

  @Override
  @Transactional
  public void revoke(UUID invitationId, UUID actor) {
    Invitation invitation =
        invitations
            .findById(invitationId)
            .filter(row -> row.getDeletedAt() == null)
            .orElseThrow(() -> new NotFoundException("invitation", invitationId));

    if (invitation.getAcceptedAt() == null && invitation.getRevokedAt() == null) {
      invitation.revoke(actor, Instant.now(clock));
      log.info("Invitation {} was withdrawn by {}", invitationId, actor);
    }
  }

  @Override
  public AcceptedInvitation accept(
      String token, String displayName, String locale, String password, UUID signedInAs) {

    // The one lookup that runs with no tenant set. Everything after it happens
    // inside the context this establishes, under the ordinary policies.
    InvitationLookupAdapter.Located located =
        lookup.locate(hash(token)).orElseThrow(InvitationUnusableException::new);

    return TenantContext.callAs(
        located.tenantId(),
        () ->
            redemption.redeem(
                located.invitationId(), displayName, locale, password, signedInAs));
  }

  /**
   * The role somebody holds in the tenant being acted for.
   *
   * <p>Read from the membership row rather than from the session's principal. The two agree in the
   * ordinary case; where they do not — a role changed while somebody was signed in — the row is
   * what is current, and this is exactly the decision that must not run on a stale copy.
   *
   * @param userId the person
   * @return their built-in role here, and the tenant-owned one extending it if any
   * @throws NotFoundException when they are not a member of this tenant
   */
  private RoleRef roleOf(UUID userId) {
    var membership =
        memberships
            .findLiveInTenant(userId)
            .orElseThrow(() -> new NotFoundException("member", userId));
    return new RoleRef(membership.getRole(), membership.getRoleDefinitionId());
  }

  /**
   * A fresh token.
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
   * @return the hash the table stores
   */
  private static String hash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      // Every JVM ships SHA-256; the checked exception is a relic of an era when
      // that was not true. Failing loudly beats pretending to have hashed.
      throw new IllegalStateException("SHA-256 is not available in this JVM", impossible);
    }
  }

  /**
   * The tenant's view of an invitation.
   *
   * @param invitation the row
   * @param now the instant to judge its state at
   * @return the view, without the token
   */
  private static InvitationView viewOf(Invitation invitation, Instant now) {
    return new InvitationView(
        invitation.getId(),
        invitation.getEmail(),
        invitation.getRole(),
        invitation.getExpiresAt(),
        invitation.stateAt(now),
        invitation.getCreatedAt());
  }
}
