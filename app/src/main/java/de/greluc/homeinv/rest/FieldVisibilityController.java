/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import io.swagger.v3.oas.annotations.tags.Tag;
import de.greluc.homeinv.authorization.api.RequiresRecentSecondFactor;
import de.greluc.homeinv.authorization.api.FieldVisibility;
import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.authorization.api.Role;
import de.greluc.homeinv.authorization.api.RoleRef;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.platform.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which roles may read which sensitive fields (REQ-TEN-008, REQ-SEC-027).
 *
 * <p>Gated on the member permissions for the reason {@code /api/v1/roles} is: deciding who may read
 * a purchase price is deciding who may do what, and splitting it from the rest of that decision
 * would let somebody hold one half.
 *
 * <p>The answer is keyed by field rather than listed, and not to dodge the page cap of REQ-NFR-010:
 * the question a client asks is "who may read <em>this</em> field", and a flat list makes it group
 * the rows itself. A tenant with more sensitive fields than fit in one answer has a type system
 * problem, and this endpoint is where it would show.
 */
@Tag(name = "Field visibility", description = "Which roles see which fields of a type (REQ-SEC-027).")
@RestController
@RequestMapping("/api/v1/field-visibility")
@RequiredArgsConstructor
public class FieldVisibilityController {

  private final FieldVisibility visibility;

  /**
   * Every rule this tenant has made, grouped by field.
   *
   * <p>A field that appears with an empty list has a rule for nobody, which is different from a
   * field that does not appear: the second falls back to the default, and {@code OWNER} and
   * {@code ADMIN} read it.
   *
   * <p><b>A list and not a map, since 2026-09-21.</b> It answered {@code Map<String,
   * List<RoleReference>>} — a JSON object whose keys are field names — which is valid JSON, valid
   * OpenAPI, and the one shape in this whole contract that a code generator cannot type: an object
   * with dynamic keys becomes a raw {@code Map} at best, and the Kotlin generator emitted
   * {@code Map<String, List>} without an element type, which does not compile ({@code
   * REQ-API-002}). The information is the same and the entries carry their own key.
   *
   * @param limit how many at most; capped at 200. It answered an object until this became a
   *     list, and REQ-NFR-010 bounds every collection without exception — a tenant with a rule per
   *     field of a large type system is exactly the case an unbounded list would meet first
   * @return the roles permitted per field, one entry per field that has a rule
   */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.MEMBER_READ)
  @CanFail(ProblemType.FORBIDDEN)
  public List<FieldRules> rules(
      @RequestParam(required = false, defaultValue = "200")
          @jakarta.validation.constraints.Positive
          @jakarta.validation.constraints.Max(200)
          int limit) {
    Map<String, List<RoleReference>> byField = new TreeMap<>();
    for (FieldVisibility.Rule rule : visibility.rules()) {
      byField
          .computeIfAbsent(rule.fieldKey(), ignored -> new java.util.ArrayList<>())
          .add(new RoleReference(rule.builtInRole(), rule.roleDefinitionId()));
    }
    return byField.entrySet().stream()
        .map(entry -> new FieldRules(entry.getKey(), entry.getValue()))
        .limit(limit)
        .toList();
  }

  /**
   * Which roles may read one field.
   *
   * @param fieldKey the field, as its type declares it
   * @param roles the roles permitted to read it, possibly none — which is a rule for nobody and
   *     not the absence of a rule
   */
  public record FieldRules(String fieldKey, List<RoleReference> roles) {}

  /**
   * Lets a role read a sensitive field.
   *
   * @param request the field and the role
   * @param user the authenticated caller
   */
  @RequiresRecentSecondFactor
  @PostMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresPermission(Permission.MEMBER_UPDATE)
  @CanFail({ProblemType.FORBIDDEN, ProblemType.NOT_FOUND, ProblemType.VALIDATION_FAILED})
  public void allow(
      @Valid @RequestBody FieldVisibilityRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    visibility.allow(request.fieldKey(), refOf(request.role(), request.roleDefinitionId()),
        user.userId());
  }

  /**
   * Withdraws such a grant.
   *
   * <p>Withdrawing one that was never made is not an error, for the reason deleting twice is not.
   * What it cannot do is take a field from {@code OWNER}: that grant is the default rather than a
   * row, and a tenant whose owner cannot read its own data is one nobody can administer.
   *
   * @param fieldKey the field's key
   * @param role the built-in role to withdraw it from, or omitted
   * @param roleDefinitionId the tenant-owned role to withdraw it from, or omitted
   * @param user the authenticated caller
   */
  @RequiresRecentSecondFactor
  @DeleteMapping("/{fieldKey}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresPermission(Permission.MEMBER_UPDATE)
  @CanFail({ProblemType.FORBIDDEN, ProblemType.NOT_FOUND, ProblemType.MALFORMED_REQUEST})
  public void revoke(
      @PathVariable @Size(max = 64) String fieldKey,
      @RequestParam(required = false) @Size(max = 32) String role,
      @RequestParam(required = false) UUID roleDefinitionId,
      @AuthenticationPrincipal AuthenticatedUser user) {
    visibility.revoke(fieldKey, refOf(role, roleDefinitionId), user.userId());
  }

  /**
   * A role reference from a request, with exactly one half set.
   *
   * @param role the built-in role's name, or null
   * @param definitionId the tenant-owned role, or null
   * @return the reference
   * @throws NotFoundException when neither is given, when both are, or when the name is not one of
   *     the six. A rule about a role that does not exist would be a rule that silently applies to
   *     nobody
   */
  private static RoleRef refOf(String role, UUID definitionId) {
    if ((role == null) == (definitionId == null)) {
      throw new NotFoundException("role", definitionId);
    }
    if (definitionId != null) {
      return new RoleRef(null, definitionId);
    }
    return RoleRef.of(Role.named(role).orElseThrow(() -> new NotFoundException("role", (UUID) null)).name());
  }

  /**
   * The body of a grant.
   *
   * @param fieldKey the field's key, as the type system spells it
   * @param role the built-in role to grant it to, or omitted when a tenant-owned one is named
   * @param roleDefinitionId the tenant-owned role to grant it to, or omitted
   */
  public record FieldVisibilityRequest(
      @NotBlank @Size(max = 64) String fieldKey,
      @Size(max = 32) String role,
      UUID roleDefinitionId) {}

  /**
   * A role a rule is about.
   *
   * @param role the built-in role's name, or null
   * @param roleDefinitionId the tenant-owned role, or null
   */
  public record RoleReference(String role, UUID roleDefinitionId) {}
}
