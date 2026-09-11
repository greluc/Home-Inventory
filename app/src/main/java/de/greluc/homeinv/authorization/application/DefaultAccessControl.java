/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.application;

import de.greluc.homeinv.authorization.api.AccessControl;
import de.greluc.homeinv.authorization.api.AccessDeniedException;
import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.Role;
import de.greluc.homeinv.authorization.api.TenantOwned;
import de.greluc.homeinv.platform.CallerContext;
import de.greluc.homeinv.platform.NotFoundException;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Evaluates a permission against the caller's built-in role.
 *
 * <p>Stage 0 has no stored role definitions: the six built-in roles and their grants are code, in
 * {@link Role}. Stage 1 adds tenant-owned roles that extend them, and this is where that lookup
 * lands — the callers of {@link AccessControl} do not change when it does, which is the reason the
 * interface exists at all.
 */
@Slf4j
@Service
public class DefaultAccessControl implements AccessControl {

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
