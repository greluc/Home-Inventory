/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.application;

import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.authorization.api.RoleAdministration;
import de.greluc.homeinv.authorization.api.RoleRef;
import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.api.AccountRegistry;
import de.greluc.homeinv.tenancy.api.LastOwnerException;
import de.greluc.homeinv.tenancy.api.MembershipAdministration;
import de.greluc.homeinv.tenancy.api.RoleEscalationException;
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
  private final RoleAdministration roles;
  private final CursorCodec cursors;
  private final Clock clock;

  @Override
  @Transactional(readOnly = true)
  public Page<MemberView> members(String cursor, int limit) {
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
                      membership.getRoleDefinitionId(),
                      roleNameOf(membership),
                      membership.getScopeLocationId(),
                      membership.getCreatedAt());
                })
            .toList();

    String next =
        rows.size() == size
            ? cursors.encode(
                new CursorCodec.Position(rows.getLast().getCreatedAt(), rows.getLast().getId()),
                CURSOR)
            : null;
    return Page.of(items, next);
  }

  @Override
  @Transactional
  public MemberView changeRole(
      UUID userId, String role, UUID roleDefinitionId, UUID scopeLocationId, UUID actor) {
    Membership membership = liveMembership(userId);
    RoleRef actorRole = refOf(liveMembership(actor));

    // A definition whose base is not the role being given would be two answers to
    // what this person may do, and the one that applied would depend on which of
    // the two a later reader happened to look at.
    String base = role;
    if (roleDefinitionId != null) {
      base =
          roles
              .byId(roleDefinitionId)
              .orElseThrow(() -> new NotFoundException("role", roleDefinitionId))
              .baseRole()
              .name();
    }

    // Both directions are checked: the role being granted, and the role being
    // taken away. An administrator who could demote an owner could demote every
    // owner and then be the only person left who may invite.
    grants.requireGrantable(actorRole, new RoleRef(base, roleDefinitionId));
    grants.requireGrantable(actorRole, refOf(membership));

    if ("OWNER".equals(membership.getRole()) && !"OWNER".equals(base) && isLastOwner(userId)) {
      throw new LastOwnerException();
    }

    // An administrator who is confined themselves does not confine anybody else.
    // The obvious escalation is not a subtle one — they would simply hand out a
    // membership with NO scope — and comparing two subtrees here would mean this
    // block asking `locations` a question, which would close a cycle: `locations`
    // already asks this side whether a place still holds anything. Refusing the
    // whole operation is the answer that needs no dependency and no judgement.
    Membership actorMembership = liveMembership(actor);
    if (actorMembership.getScopeLocationId() != null) {
      throw new RoleEscalationException(
          actorMembership.getRole(), "a membership scope while confined to one");
    }

    membership.changeRole(base, roleDefinitionId, scopeLocationId, actor, Instant.now(clock));
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
              grants.requireGrantable(refOf(liveMembership(actor)), refOf(membership));
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
   * What a membership says its holder's role is.
   *
   * <p>Read from the row rather than from the session's principal. The two agree in the ordinary
   * case, and where they do not — a role changed while somebody was signed in — the row is the one
   * that is current, and this is exactly the decision that must not run on a stale copy.
   *
   * @param membership the membership
   * @return the built-in role and the tenant-owned one extending it, if any
   */
  private static RoleRef refOf(Membership membership) {
    return new RoleRef(membership.getRole(), membership.getRoleDefinitionId());
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
        membership.getRoleDefinitionId(),
        roleNameOf(membership),
        membership.getScopeLocationId(),
        membership.getCreatedAt());
  }

  /**
   * What to call a membership's role in a list.
   *
   * <p>The tenant-owned role's name where there is one, and the built-in name otherwise. A client
   * showing a member list wants a word, and making it join two collections to find one is how a
   * list ends up showing a UUID.
   *
   * @param membership the membership
   * @return the name
   */
  private String roleNameOf(Membership membership) {
    if (membership.getRoleDefinitionId() == null) {
      return membership.getRole();
    }
    return roles
        .byId(membership.getRoleDefinitionId())
        .map(RoleAdministration.RoleDefinitionView::name)
        // Removed while somebody still held it: the membership falls back to the
        // base, which is exactly what their permissions do too.
        .orElse(membership.getRole());
  }
}
