/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.Entitlement;
import de.greluc.homeinv.authorization.api.RequiresEntitlement;
import de.greluc.homeinv.identity.api.AccountAdministration;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.identity.api.OperatorDirectory;
import de.greluc.homeinv.tenancy.api.QuotaAdministration;
import de.greluc.homeinv.tenancy.api.QuotaGuard;
import de.greluc.homeinv.platform.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administering the instance itself, rather than any tenant on it (ADR-0057).
 *
 * <p>Every endpoint here is gated on {@link Entitlement#INSTANCE_OPERATOR}, and the gate is the
 * whole point: these are the only paths in the application that read an account across every tenant
 * boundary there is. What they do <b>not</b> do is reach a tenant's data — row-level security
 * answers to {@code app.tenant_id} and not to who asked, so an operator who is not a member of a
 * tenant still reads none of its rows. Getting inside one is impersonation (REQ-SEC-072), which is
 * a separate, audited, time-limited act and not a side effect of this flag.
 *
 * <p>Re-confirmation of the second factor is required of every operator action (REQ-SEC-021,
 * REQ-AUTH-011) and is wired in with the second factor itself; until then these endpoints require
 * the entitlement and write a log line naming the operator, the account and the new values.
 */
@RestController
@RequestMapping("/api/v1/instance")
@RequiredArgsConstructor
public class InstanceController {

  private final AccountAdministration accounts;
  private final OperatorDirectory operators;
  private final QuotaAdministration quotas;

  /**
   * Finds an account by its login address.
   *
   * <p>The operator's way in: they know the address somebody wrote to them, not a UUID. An address
   * with no live account is a {@code 404}, which here says exactly what it means — the operator is
   * entitled to know whether an account exists, which is the difference between this endpoint and
   * every tenant-scoped one.
   *
   * @param email the address to look up
   * @return the account
   */
  @GetMapping(path = "/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresEntitlement(Entitlement.INSTANCE_OPERATOR)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.FORBIDDEN, ProblemType.VALIDATION_FAILED})
  public AccountView byEmail(@RequestParam @Email @Size(max = 320) String email) {
    return accounts
        .byEmail(email)
        .map(InstanceController::viewOf)
        .orElseThrow(() -> new NotFoundException("account", null));
  }

  /**
   * One account by id.
   *
   * @param id the account
   * @return the account
   */
  @GetMapping(path = "/accounts/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresEntitlement(Entitlement.INSTANCE_OPERATOR)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.FORBIDDEN})
  public AccountView byId(@PathVariable UUID id) {
    return accounts
        .byId(id)
        .map(InstanceController::viewOf)
        .orElseThrow(() -> new NotFoundException("account", id));
  }

  /**
   * One page of the accounts that administer the instance.
   *
   * <p>"Who can do this to us" — the question an operator asks before granting and an auditor asks
   * afterwards.
   *
   * @param cursor an opaque cursor from a previous page, or omitted for the first
   * @param limit how many at most; capped at 200
   * @return the operators, oldest account first
   */
  @GetMapping(path = "/operators", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresEntitlement(Entitlement.INSTANCE_OPERATOR)
  @CanFail({ProblemType.FORBIDDEN, ProblemType.MALFORMED_REQUEST})
  public OperatorPage operators(
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {

    OperatorDirectory.OperatorPage page = operators.operators(cursor, limit);
    return new OperatorPage(
        page.items().stream().map(InstanceController::viewOf).toList(), page.nextCursor());
  }

  /**
   * Replaces what an account is entitled to do on the instance.
   *
   * <p>All three values in one request, because they are one decision. Nothing here stops an
   * operator clearing their own flag and locking the instance out of its own administration; the
   * way back is the one-shot {@code bootstrap} service, run again with the same address, and
   * guarding against it in code would mean deciding in the application which operator is the last
   * one — a question the application cannot answer while somebody else is deleting an account.
   *
   * @param id the account to change
   * @param request the new entitlements
   * @param user the operator making the change
   * @return the account as it now stands
   */
  @PutMapping(path = "/accounts/{id}/entitlements", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresEntitlement(Entitlement.INSTANCE_OPERATOR)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.FORBIDDEN, ProblemType.VALIDATION_FAILED})
  public AccountView setEntitlements(
      @PathVariable UUID id,
      @Valid @RequestBody EntitlementsRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {

    return viewOf(
        accounts.replaceEntitlements(
            id,
            request.instanceOperator(),
            request.mayCreateTenants(),
            request.tenantLimit(),
            user.userId()));
  }

  /**
   * What has been decided about one tenant's quotas (REQ-TEN-009, 13 §13.9).
   *
   * <p>Limits only, and an empty answer means nothing has been decided rather than that the tenant
   * may do nothing — a quota with no entry falls back to the instance-wide default. What the tenant
   * has <b>used</b> is its own data and is not readable here: an operator who needs to see inside a
   * tenant impersonates (REQ-SEC-072), which is audited and which the affected user can see.
   *
   * @param tenantId the tenant
   * @return the limits set for it
   */
  @GetMapping(path = "/tenants/{tenantId}/quotas", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresEntitlement(Entitlement.INSTANCE_OPERATOR)
  @CanFail(ProblemType.FORBIDDEN)
  public Map<QuotaGuard.Quota, Long> quotasOf(@PathVariable UUID tenantId) {
    return limitsOf(tenantId);
  }

  /**
   * Sets what a tenant may use.
   *
   * <p>The write goes through the {@code SECURITY DEFINER} function of migration {@code V24},
   * which is what 07 §7.5 names for cross-tenant administration. The operator establishes no tenant
   * context and gains no other reach into the tenant by doing this.
   *
   * @param tenantId the tenant
   * @param quota which bound
   * @param request the new limit
   * @param user the operator making the change
   * @return the limits as they now stand
   */
  @PutMapping(
      path = "/tenants/{tenantId}/quotas/{quota}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresEntitlement(Entitlement.INSTANCE_OPERATOR)
  @CanFail({ProblemType.FORBIDDEN, ProblemType.VALIDATION_FAILED})
  public Map<QuotaGuard.Quota, Long> setQuota(
      @PathVariable UUID tenantId,
      @PathVariable QuotaGuard.Quota quota,
      @Valid @RequestBody QuotaRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {

    quotas.setLimit(tenantId, quota, request.permitted(), user.userId());
    return limitsOf(tenantId);
  }

  /**
   * The limits set for a tenant, keyed by quota.
   *
   * <p>A map and not a list, and not to dodge the page cap of REQ-NFR-010: there are exactly four
   * quotas and there will be exactly four tomorrow, so a cursor would page a set that cannot grow.
   *
   * @param tenantId the tenant
   * @return what has been decided about it, which may be nothing
   */
  private Map<QuotaGuard.Quota, Long> limitsOf(UUID tenantId) {
    Map<QuotaGuard.Quota, Long> limits = new EnumMap<>(QuotaGuard.Quota.class);
    for (QuotaAdministration.QuotaLimit limit : quotas.limitsOf(tenantId)) {
      limits.put(limit.quota(), limit.permitted());
    }
    return limits;
  }

  /**
   * The body of a quota change.
   *
   * @param permitted how much the tenant may use. Zero is legal and means "none for now"; there is
   *     no way to say "unlimited", because an unbounded quota is not a quota
   */
  public record QuotaRequest(@PositiveOrZero long permitted) {}

  /**
   * Maps the port's view onto the wire.
   *
   * @param account the account
   * @return the response body
   */
  private static AccountView viewOf(AccountAdministration.AccountView account) {
    return new AccountView(
        account.id(),
        account.email(),
        account.displayName(),
        account.instanceOperator(),
        account.mayCreateTenants(),
        account.tenantLimit(),
        account.locked());
  }

  /**
   * One page of operators.
   *
   * @param items the accounts
   * @param nextCursor where the next page starts, or null when this was the last
   */
  public record OperatorPage(List<AccountView> items, String nextCursor) {}

  /**
   * The body of an entitlement change.
   *
   * @param instanceOperator whether the account administers the instance
   * @param mayCreateTenants whether it may create tenants (REQ-TEN-002)
   * @param tenantLimit how many tenants it may be in, or omitted for the instance-wide default
   */
  public record EntitlementsRequest(
      boolean instanceOperator,
      boolean mayCreateTenants,
      @PositiveOrZero @Max(200) Integer tenantLimit) {}

  /**
   * An account as the operator's view shows it.
   *
   * <p>No password hash, no session information, no list of tenants: an operator administers
   * entitlements, and the rest is the account holder's.
   *
   * @param id the account
   * @param email the login address
   * @param displayName what the interface calls them
   * @param instanceOperator whether they administer the instance
   * @param mayCreateTenants whether they may create tenants
   * @param tenantLimit their own limit, or null when the instance-wide default applies
   * @param locked whether the account is barred from authenticating
   */
  public record AccountView(
      UUID id,
      String email,
      String displayName,
      boolean instanceOperator,
      boolean mayCreateTenants,
      Integer tenantLimit,
      boolean locked) {}
}
