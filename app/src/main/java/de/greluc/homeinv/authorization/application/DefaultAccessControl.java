/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.application;

import de.greluc.homeinv.authorization.api.AccessControl;
import de.greluc.homeinv.authorization.api.AccessDeniedException;
import de.greluc.homeinv.authorization.api.AccountEntitlements;
import de.greluc.homeinv.authorization.api.Entitlement;
import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.Role;
import de.greluc.homeinv.authorization.api.RoleRef;
import de.greluc.homeinv.authorization.api.TenantOwned;
import de.greluc.homeinv.authorization.infrastructure.RoleDefinitionAdapter;
import de.greluc.homeinv.platform.CallerContext;
import de.greluc.homeinv.platform.NotFoundException;
import java.util.UUID;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Evaluates a permission against the caller's built-in role, and an entitlement against their
 * account.
 *
 * <p>Stage 0 has no stored role definitions: the six built-in roles and their grants are code, in
 * {@link Role}. Stage 1 adds tenant-owned roles that extend them, and this is where that lookup
 * lands — the callers of {@link AccessControl} do not change when it does, which is the reason the
 * interface exists at all.
 *
 * <p>The two questions are answered from different places on purpose (ADR-0057). A permission is
 * decided from the role in the principal, which was established at login for one tenant; an
 * entitlement is read from the account on every call, because it is granted by an operator to
 * somebody who may be signed in while it happens.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultAccessControl implements AccessControl {

  private final AccountEntitlements entitlements;
  private final RoleDefinitionAdapter definitions;

  @Override
  public void require(Entitlement entitlement) {
    CallerContext.Caller caller = CallerContext.require();
    if (!entitlements.holds(caller.userId(), entitlement)) {
      // At WARN and named, for the same reason a permission denial is: it is
      // either an attack or an administration mistake, and an operator wants to
      // see both. REQ-TEN-010 asks for the attempt to be logged as well as
      // rejected, and this is where that happens for the instance level.
      log.warn("Denied: the caller's account does not hold {}.", entitlement.id());
      throw new AccessDeniedException(entitlement);
    }
  }

  @Override
  public void require(Permission permission) {
    if (!holds(permission)) {
      // At WARN and with the permission named, because a denial is either an
      // attack or a misconfigured role, and both are worth seeing. The caller's
      // identity is already in the MDC of every log line (REQ-NFR-041), so it is
      // not repeated here.
      log.warn("Denied: the caller's role does not hold {}.", permission.id());
      throw new AccessDeniedException(permission);
    }
  }

  @Override
  public void require(Permission permission, TenantOwned resource) {
    CallerContext.Caller caller = CallerContext.require();

    if (!resource.tenantId().equals(caller.tenantId())) {
      // Not a 403. Telling somebody "you may not touch this" confirms it exists,
      // and for a caller from another tenant its existence is the fact they were
      // not supposed to learn (REQ-SEC-025). Row-level security means this should
      // be unreachable; it is the second line, and the two are independent by
      // design (ADR-0003).
      log.warn(
          "Denied: a resource of tenant {} was reached from tenant {}. Row-level security should "
              + "have made this unreachable.",
          resource.tenantId(),
          caller.tenantId());
      throw new NotFoundException(resourceNameOf(permission), (UUID) null);
    }
    require(permission);
  }

  @Override
  public Set<Permission> permissionsOf(RoleRef role) {
    Set<Permission> held = EnumSet.noneOf(Permission.class);
    if (role == null) {
      return held;
    }
    Role.named(role.builtIn()).map(Role::permissions).ifPresent(held::addAll);
    if (role.definitionId() != null) {
      // Read now rather than carried in the session, which is what makes a change
      // to a role take effect for somebody who is signed in while it happens.
      CallerContext.current()
          .map(CallerContext.Caller::tenantId)
          .ifPresent(
              tenantId -> held.addAll(definitions.grantsOf(tenantId, role.definitionId())));
    }
    return held;
  }

  @Override
  public boolean mayDefine(RoleRef actor, Role base, Set<Permission> added) {
    Set<Permission> result = EnumSet.noneOf(Permission.class);
    result.addAll(base.permissions());
    result.addAll(added);

    if (!permissionsOf(actor).containsAll(result)) {
      return false;
    }
    // And the ownership rule, which no permission set expresses: only an OWNER
    // may define a role that starts from OWNER.
    return base != Role.OWNER || Role.OWNER.name().equals(actor == null ? null : actor.builtIn());
  }

  @Override
  public boolean mayGrant(RoleRef actor, RoleRef target) {
    Optional<Role> actorBase = Role.named(actor == null ? null : actor.builtIn());
    Optional<Role> targetBase = Role.named(target == null ? null : target.builtIn());
    if (actorBase.isEmpty() || targetBase.isEmpty()) {
      // An unknown role on either side grants nothing and receives nothing. A
      // downgraded build or a hand-edited membership row must not be an argument
      // for allowing something.
      log.warn("Refused a grant involving a role this build does not know: {} -> {}", actor, target);
      return false;
    }
    if (!permissionsOf(actor).containsAll(permissionsOf(target))) {
      return false;
    }
    // OWNER and ADMIN hold the same permissions today, so the set test alone
    // would let an administrator hand out ownership. Ownership decides who may
    // delete the tenant and who may step down, and neither follows from a set.
    return targetBase.get() != Role.OWNER || actorBase.get() == Role.OWNER;
  }

  @Override
  public boolean holds(Permission permission) {
    CallerContext.Caller caller = CallerContext.require();
    if (Role.named(caller.role()).isEmpty()) {
      // A membership row carrying a role this build does not know - a downgrade,
      // or a hand-edited row. It grants nothing, and it says so once per denial
      // rather than being silently equivalent to GUEST.
      log.warn("The caller's role '{}' is not one this build knows; it grants nothing.",
          caller.role());
      return false;
    }
    if (permission.wholeTenant() && caller.scopeLocationId() != null) {
      // A membership confined to part of the tree (REQ-TEN-007) holds no
      // permission that is about the tenant as a whole. Not a matter of rank: an
      // export of a shelf does not exist, so the only thing this caller could be
      // given is an archive of everything -- which is exactly what the scope
      // says they may not have (ADR-0068).
      log.debug(
          "A membership scoped to {} was refused the whole-tenant permission {}",
          caller.scopeLocationId(),
          permission.id());
      return false;
    }
    return permissionsOf(new RoleRef(caller.role(), caller.roleDefinitionId()))
        .contains(permission);
  }

  /**
   * The middle segment of a permission id, which names the kind of thing it is about.
   *
   * <p>{@code inventory:item:read} yields {@code item}. It is what a 404 says was not found, and it
   * is deliberately the permission's word rather than the loaded object's class name: the class is
   * an internal type and has no business in an HTTP body.
   *
   * @param permission the permission the operation needed
   * @return the resource name
   */
  private static String resourceNameOf(Permission permission) {
    String[] segments = permission.id().split(":");
    return segments.length == 3 ? segments[1] : "resource";
  }

}
