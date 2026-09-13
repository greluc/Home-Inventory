/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.application;

import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.api.AccountRegistry;
import de.greluc.homeinv.tenancy.api.LastOwnerException;
import de.greluc.homeinv.tenancy.api.MembershipAdministration;
import de.greluc.homeinv.tenancy.domain.Membership;
import de.greluc.homeinv.tenancy.infrastructure.MembershipRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who is in this tenant, and what changing that is allowed to mean (REQ-TEN-005, REQ-TEN-010).
 *
 * <p>Every read and write is scoped by row-level security rather than by a tenant parameter, so a
 * caller cannot name a tenant at all — the one they act for comes from their session (REQ-SEC-004).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultMembershipAdministration implements MembershipAdministration {

  /** The cap REQ-NFR-010 puts on every page of every collection. */
  private static final int MAX_PAGE = 200;

  /** What a member cursor is bound to, so it cannot be replayed against another listing. */
  private static final String CURSOR = "tenant-members";

  private final MembershipRepository memberships;
  private final AccountRegistry accounts;
  private final RoleGrantRules grants;
  private final CursorCodec cursors;
  private final Clock clock;

  @Override
  @Transactional(readOnly = true)
  public MemberPage members(String cursor, int limit) {
    int size = Math.clamp(limit, 1, MAX_PAGE);
    List<Membership> rows;
    if (cursor == null || cursor.isBlank()) {
      rows = memberships.findPage(Limit.of(size));
    } else {
      CursorCodec.Position from = cursors.decode(cursor, CURSOR);
      rows = memberships.findPageAfter(from.createdAt(), from.id(), Limit.of(size));
    }

    // One lookup for the whole page. A directory call per row is what turns a
    // page of twenty into twenty round trips, and a member list is read often.
    Map<UUID, AccountRegistry.Account> people =
        accounts.byIds(rows.stream().map(Membership::getUserId).toList());

    List<MemberView> items =
        rows.stream()
            .map(
                membership -> {
                  AccountRegistry.Account account = people.get(membership.getUserId());
                  return new MemberView(
                      membership.getUserId(),
                      account == null ? null : account.email(),
                      account == null ? null : account.displayName(),
                      membership.getRole(),
                      membership.getCreatedAt());
                })
            .toList();

    String next =
        rows.size() == size
            ? cursors.encode(
                new CursorCodec.Position(rows.getLast().getCreatedAt(), rows.getLast().getId()),
                CURSOR)
            : null;
    return new MemberPage(items, next);
  }

  @Override
  @Transactional
  public MemberView changeRole(UUID userId, String role, UUID actor) {
    Membership membership = liveMembership(userId);
    String actorRole = roleOf(actor);

    // Both directions are checked: the role being granted, and the role being
    // taken away. An administrator who could demote an owner could demote every
    // owner and then be the only person left who may invite.
    grants.requireGrantable(actorRole, role);
    grants.requireGrantable(actorRole, membership.getRole());

    if ("OWNER".equals(membership.getRole()) && !"OWNER".equals(role) && isLastOwner(userId)) {
      throw new LastOwnerException();
    }

    membership.changeRole(role, actor, Instant.now(clock));
    log.info(
        "Member {} of tenant {} is now {} (changed by {})",
        userId,
        TenantContext.require(),
        role,
        actor);
    return viewOf(membership);
  }

  @Override
  @Transactional
  public void remove(UUID userId, UUID actor) {
    memberships
        .findLiveInTenant(userId)
        .ifPresent(
            membership -> {
              grants.requireGrantable(roleOf(actor), membership.getRole());
              if ("OWNER".equals(membership.getRole()) && isLastOwner(userId)) {
                throw new LastOwnerException();
              }
              membership.remove(actor, Instant.now(clock));
              log.info(
                  "Member {} was removed from tenant {} by {}",
                  userId,
                  TenantContext.require(),
                  actor);
            });
  }

  /**
   * The live membership of somebody in this tenant.
   *
   * @param userId the person
   * @return their membership
   * @throws NotFoundException when they are not a member here
   */
  private Membership liveMembership(UUID userId) {
    return memberships
        .findLiveInTenant(userId)
        .orElseThrow(() -> new NotFoundException("member", userId));
  }

  /**
   * The role somebody holds in this tenant.
   *
   * <p>Read from the membership rather than from the session's principal. The two agree in the
   * ordinary case, and where they do not — a role changed while somebody was signed in — the row is
   * the one that is current, and this is exactly the decision that must not be made on a stale one.
   *
   * @param userId the person
   * @return their role
   * @throws NotFoundException when they are not a member here
   */
  private String roleOf(UUID userId) {
    return liveMembership(userId).getRole();
  }

  /**
   * Whether this person is the only owner left.
   *
   * @param userId the person
   * @return {@code true} when no other live membership in this tenant is an owner
   */
  private boolean isLastOwner(UUID userId) {
    return memberships.countOtherOwners(userId) == 0;
  }

  /**
   * A member, with the name and address the directory holds.
   *
   * @param membership the membership
   * @return the view
   */
  private MemberView viewOf(Membership membership) {
    AccountRegistry.Account account = accounts.byId(membership.getUserId()).orElse(null);
    return new MemberView(
        membership.getUserId(),
        account == null ? null : account.email(),
        account == null ? null : account.displayName(),
        membership.getRole(),
        membership.getCreatedAt());
  }
}
