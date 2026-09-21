/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import io.swagger.v3.oas.annotations.tags.Tag;
import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.authorization.api.RequiresRecentSecondFactor;
import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.authorization.api.Role;
import de.greluc.homeinv.authorization.api.RoleAdministration;
import de.greluc.homeinv.authorization.api.RoleRef;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.tenancy.api.RoleEscalationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roles a tenant defines for itself (REQ-TEN-006).
 *
 * <h2>Why there is no permission of its own</h2>
 *
 * <p>Gated on {@code tenancy:member:read} and {@code tenancy:member:update}, which look like the
 * wrong block until one asks what defining a role <em>is</em>: deciding who may do what. Assigning a
 * role and defining one are the same act split in two, and giving the second its own permission
 * would let somebody hold one half — able to define a role they cannot hand out, or to hand out one
 * they cannot inspect.
 *
 * <h2>Nobody defines a role they could not hold</h2>
 *
 * <p>REQ-TEN-010 reaches further than assignment. An administrator who could define a role adding
 * a permission they lack would only have to assign it to somebody to exercise it, so the same check
 * runs when a definition is written, not only when it is given out.
 */
@Tag(name = "Roles", description = "Custom roles and the permissions they hold.")
@RestController
@RequestMapping("/api/v1/roles")
@RequiredArgsConstructor
public class RoleController {

  private final RoleAdministration roles;
  private final de.greluc.homeinv.authorization.api.AccessControl accessControl;

  /**
   * One page of the roles this tenant has defined.
   *
   * @param cursor an opaque cursor from a previous page, or omitted for the first
   * @param limit how many at most; capped at 200
   * @return the definitions, oldest first
   */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.MEMBER_READ)
  @CanFail({ProblemType.FORBIDDEN, ProblemType.MALFORMED_REQUEST})
  public Page<RoleView> roles(
      @RequestParam(required = false) @jakarta.validation.constraints.Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50")
          @jakarta.validation.constraints.Positive
          @jakarta.validation.constraints.Max(200)
          int limit) {

    Page<RoleAdministration.RoleDefinitionView> page = roles.roles(cursor, limit);
    return Page.of(
        page.data().stream().map(RoleController::viewOf).toList(), page.nextCursor());
  }

  /**
   * One definition.
   *
   * @param id the definition
   * @return it
   */
  @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.MEMBER_READ)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.FORBIDDEN})
  public RoleView role(@PathVariable UUID id) {
    return roles.byId(id).map(RoleController::viewOf).orElseThrow(
        () -> new NotFoundException("role", id));
  }

  /**
   * Defines a role.
   *
   * @param request the name, the base and what it adds
   * @param user the authenticated caller
   * @return the new definition
   */
  @RequiresRecentSecondFactor
  @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.MEMBER_UPDATE)
  @ResponseStatus(HttpStatus.CREATED)
  @CanFail({
    ProblemType.FORBIDDEN,
    ProblemType.ROLE_ESCALATION,
    ProblemType.NAME_TAKEN,
    ProblemType.VALIDATION_FAILED
  })
  public ResponseEntity<RoleView> create(
      @Valid @RequestBody RoleDefinitionRequest request, @AuthenticationPrincipal AuthenticatedUser user) {

    Role base = baseOf(request.baseRole());
    Set<Permission> added = permissionsOf(request.permissions());
    requireWithinReach(user, base, added);

    RoleAdministration.RoleDefinitionView created =
        roles.create(request.name(), request.description(), base, added, user.userId());
    return ResponseEntity.created(URI.create("/api/v1/roles/" + created.id()))
        .body(viewOf(created));
  }

  /**
   * Replaces a definition's name, description and added permissions.
   *
   * @param id the definition
   * @param request the new state
   * @param user the authenticated caller
   * @return the definition as it now stands
   */
  @RequiresRecentSecondFactor
  @PutMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.MEMBER_UPDATE)
  @CanFail({
    ProblemType.NOT_FOUND,
    ProblemType.FORBIDDEN,
    ProblemType.ROLE_ESCALATION,
    ProblemType.NAME_TAKEN,
    ProblemType.VALIDATION_FAILED
  })
  public RoleView update(
      @PathVariable UUID id,
      @Valid @RequestBody RoleDefinitionRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {

    RoleAdministration.RoleDefinitionView existing =
        roles.byId(id).orElseThrow(() -> new NotFoundException("role", id));
    Set<Permission> added = permissionsOf(request.permissions());
    requireWithinReach(user, existing.baseRole(), added);

    return viewOf(roles.update(id, request.name(), request.description(), added, user.userId()));
  }

  /**
   * Removes a definition.
   *
   * <p>Members holding it are not blocked and not rewritten: their membership keeps naming the
   * built-in role the definition extended, so this demotes its holders to that base rather than
   * stranding them.
   *
   * @param id the definition
   * @param user the authenticated caller
   */
  @RequiresRecentSecondFactor
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresPermission(Permission.MEMBER_UPDATE)
  @CanFail({ProblemType.FORBIDDEN})
  public void remove(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    roles.remove(id, user.userId());
  }

  /**
   * Refuses a definition that reaches past what the caller holds (REQ-TEN-010).
   *
   * @param user the caller
   * @param base the built-in role the definition extends
   * @param added what it adds
   * @throws RoleEscalationException when the result would hold something the caller does not
   */
  private void requireWithinReach(AuthenticatedUser user, Role base, Set<Permission> added) {
    // The decision is `authorization`'s, and reading the grant sets here instead
    // would be a second place that answers "may" — which ArchUnit refuses, and
    // which this method did on its first attempt.
    if (!accessControl.mayDefine(
        new RoleRef(user.role(), user.roleDefinitionId()), base, added)) {
      throw new RoleEscalationException(String.valueOf(user.role()), base.name());
    }
  }

  /**
   * A built-in role by name.
   *
   * @param name the name as given
   * @return the role
   * @throws NotFoundException when it is not one of the six
   */
  private static Role baseOf(String name) {
    return Role.named(name).orElseThrow(() -> new NotFoundException("role", (UUID) null));
  }

  /**
   * Permission ids as this build knows them.
   *
   * @param ids the ids as given
   * @return the permissions
   * @throws NotFoundException when one is not a permission this build defines. Refused rather than
   *     ignored: a role that silently dropped a permission somebody asked for would be a role whose
   *     definition does not say what it does
   */
  private static Set<Permission> permissionsOf(List<String> ids) {
    Set<Permission> permissions = EnumSet.noneOf(Permission.class);
    if (ids == null) {
      return permissions;
    }
    for (String id : ids) {
      boolean known = false;
      for (Permission permission : Permission.values()) {
        if (permission.id().equals(id)) {
          permissions.add(permission);
          known = true;
          break;
        }
      }
      if (!known) {
        throw new NotFoundException("permission", (UUID) null);
      }
    }
    return permissions;
  }

  /**
   * Maps the port's view onto the wire.
   *
   * @param definition the definition
   * @return the response body
   */
  private static RoleView viewOf(RoleAdministration.RoleDefinitionView definition) {
    return new RoleView(
        definition.id(),
        definition.name(),
        definition.description(),
        definition.baseRole().name(),
        definition.added().stream().map(Permission::id).sorted().toList(),
        definition.effective().stream().map(Permission::id).sorted().toList());
  }

  /**
   * The body of a role definition.
   *
   * @param name what to call it
   * @param description what it is for, or omitted
   * @param baseRole the built-in role it extends. Ignored on an update: changing it would silently
   *     move everybody holding the role to a different floor
   * @param permissions the permission ids it adds beyond that base
   */
  public record RoleDefinitionRequest(
      @NotBlank @Size(max = 64) String name,
      @Size(max = 500) String description,
      @NotNull @Size(max = 32) String baseRole,
      @Size(max = 100) List<@Size(max = 100) String> permissions) {}


  /**
   * A tenant-owned role.
   *
   * @param id the definition
   * @param name what the tenant calls it
   * @param description what it is for, or null
   * @param baseRole the built-in role it extends
   * @param permissions the ids it adds
   * @param effectivePermissions everything it holds, base included — so a client can show what a
   *     role means without re-implementing the ladder
   */
  public record RoleView(
      UUID id,
      String name,
      String description,
      String baseRole,
      List<String> permissions,
      List<String> effectivePermissions) {}
}
