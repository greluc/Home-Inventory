/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.authorization.api.RequiresRecentSecondFactor;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.identity.api.ServiceAccounts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
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
 * Machine access to a tenant (REQ-AUTH-010).
 *
 * <p>Under {@code /tenants/{tenantId}} like the members and the invitations, because a service
 * account is a member of the tenant in every sense that matters: it holds one of the same roles,
 * inside one tenant, and an administrator gives it and takes it away.
 *
 * <p>Issuing and revoking both ask for the second factor again (REQ-AUTH-011). Handing out a
 * credential that acts as an {@code ADMIN} is the same kind of act as granting somebody the role,
 * and an open session somebody walked away from must not be able to do either.
 *
 * <p>The {@code tenantId} in the path is checked against the session's tenant and then ignored: the
 * tenant a request acts for comes from the principal (REQ-SEC-004), and a path that could choose it
 * would be the second place it is decided.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/service-accounts")
@RequiredArgsConstructor
public class ServiceAccountController {

  private final ServiceAccounts serviceAccounts;

  /**
   * The tenant's service accounts, newest first.
   *
   * @param tenantId the tenant, which must be the session's own
   * @param limit how many at most; capped at 200
   * @param user the authenticated principal
   * @return the accounts, never their tokens
   */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.SERVICE_ACCOUNT_ADMINISTER)
  @CanFail({ProblemType.UNAUTHENTICATED, ProblemType.FORBIDDEN, ProblemType.NOT_FOUND})
  public List<ServiceAccounts.ServiceAccountView> list(
      @PathVariable UUID tenantId,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit,
      @AuthenticationPrincipal AuthenticatedUser user) {
    requireOwnTenant(tenantId, user);
    return serviceAccounts.all(limit);
  }

  /**
   * Issues a service account, and returns its token once.
   *
   * @param tenantId the tenant, which must be the session's own
   * @param request what to call it, what it may do, and when it stops working
   * @param user who is issuing it
   * @return the account and its token, which is readable here and nowhere else
   */
  @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresPermission(Permission.SERVICE_ACCOUNT_ADMINISTER)
  @RequiresRecentSecondFactor
  @CanFail({
    ProblemType.UNAUTHENTICATED,
    ProblemType.FORBIDDEN,
    ProblemType.SECOND_FACTOR_STALE,
    ProblemType.VALIDATION_FAILED,
    ProblemType.NOT_FOUND
  })
  public ServiceAccounts.IssuedServiceAccount issue(
      @PathVariable UUID tenantId,
      @Valid @RequestBody ServiceAccountRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    requireOwnTenant(tenantId, user);
    return serviceAccounts.issue(
        request.name(),
        request.description(),
        request.role(),
        request.roleDefinitionId(),
        request.expiresAt(),
        user.userId());
  }

  /**
   * Revokes one, at once.
   *
   * @param tenantId the tenant, which must be the session's own
   * @param id the account
   * @param user who is revoking it
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresPermission(Permission.SERVICE_ACCOUNT_ADMINISTER)
  @RequiresRecentSecondFactor
  @CanFail({
    ProblemType.UNAUTHENTICATED,
    ProblemType.FORBIDDEN,
    ProblemType.SECOND_FACTOR_STALE,
    ProblemType.NOT_FOUND
  })
  public void revoke(
      @PathVariable UUID tenantId,
      @PathVariable UUID id,
      @AuthenticationPrincipal AuthenticatedUser user) {
    requireOwnTenant(tenantId, user);
    serviceAccounts.revoke(id, user.userId());
  }

  /**
   * Refuses a path naming a tenant the session is not acting for.
   *
   * <p>A {@code 404} and not a {@code 403}, for {@code REQ-SEC-025}'s reason: a refusal would
   * confirm that the tenant exists. The same check {@code MemberController} makes, and it is here
   * rather than shared because a helper in a third place would be a third thing to keep in step
   * with the rule.
   *
   * @param tenantId the tenant named in the path
   * @param user the authenticated principal
   */
  private static void requireOwnTenant(UUID tenantId, AuthenticatedUser user) {
    if (!tenantId.equals(user.tenantId())) {
      throw new de.greluc.homeinv.platform.NotFoundException("tenant", tenantId);
    }
  }

  /**
   * What a new service account needs.
   *
   * @param name what to call it, so a list of tokens is a list somebody can act on
   * @param description what it is for, or null
   * @param role the built-in role it holds, one of the six names
   * @param roleDefinitionId a tenant-owned role extending it (REQ-TEN-006), or null
   * @param expiresAt when it stops working. Required — REQ-AUTH-010 asks for an expiry date, and a
   *     machine credential nobody reviews is one nobody revokes
   */
  public record ServiceAccountRequest(
      @NotBlank @Size(max = 200) String name,
      @Size(max = 1000) String description,
      @NotBlank @Size(max = 20) String role,
      UUID roleDefinitionId,
      @NotNull Instant expiresAt) {}
}
