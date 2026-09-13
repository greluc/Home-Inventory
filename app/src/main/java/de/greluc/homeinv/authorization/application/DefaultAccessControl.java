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
import de.greluc.homeinv.authorization.api.TenantOwned;
import de.greluc.homeinv.platform.CallerContext;
import de.greluc.homeinv.platform.NotFoundException;
import java.util.Optional;
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
      throw new NotFoundException(resourceNameOf(permission), null);
    }
    require(permission);
  }

  @Override
  public boolean mayGrant(String actorRole, String targetRole) {
    Optional<Role> actor = Role.named(actorRole);
    Optional<Role> target = Role.named(targetRole);
    if (actor.isEmpty() || target.isEmpty()) {
      // An unknown role on either side grants nothing and receives nothing. A
      // downgraded build or a hand-edited membership row must not be an argument
      // for allowing something.
      log.warn(
          "Refused a grant involving a role this build does not know: {} -> {}",
          actorRole,
          targetRole);
      return false;
    }
    if (!actor.get().permissions().containsAll(target.get().permissions())) {
      return false;
    }
    // OWNER and ADMIN hold the same permissions today, so the set test alone
    // would let an administrator hand out ownership. Ownership decides who may
    // delete the tenant and who may step down, and neither follows from a set.
    return target.get() != Role.OWNER || actor.get() == Role.OWNER;
  }

  @Override
  public boolean holds(Permission permission) {
    return roleOf(CallerContext.require()).map(role -> role.holds(permission)).orElse(false);
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

  /**
   * The caller's role, if it is one this build knows.
   *
   * @param caller the current caller
   * @return the role, or empty
   */
  private static Optional<Role> roleOf(CallerContext.Caller caller) {
    Optional<Role> role = Role.named(caller.role());
    if (role.isEmpty()) {
      // A membership row carrying a role this build does not know - a downgrade,
      // or a hand-edited row. It grants nothing, and it says so once per denial
      // rather than being silently equivalent to GUEST.
      log.warn("The caller's role '{}' is not one this build knows; it grants nothing.", caller.role());
    }
    return role;
  }
}
