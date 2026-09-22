/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import jakarta.annotation.Nullable;
import io.swagger.v3.oas.annotations.tags.Tag;
import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.authorization.api.Entitlement;
import de.greluc.homeinv.authorization.api.RequiresEntitlement;
import de.greluc.homeinv.identity.api.AccountAdministration;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.identity.api.OperatorDirectory;
import de.greluc.homeinv.identity.api.UserSessions;
import de.greluc.homeinv.tenancy.api.ErasureCertificates;
import de.greluc.homeinv.tenancy.api.QuotaAdministration;
import de.greluc.homeinv.tenancy.api.QuotaGuard;
import de.greluc.homeinv.tenancy.api.TenantAdministration;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.plugin.api.PluginManifestReader;
import de.greluc.homeinv.plugins.api.PluginRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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
@Tag(name = "Instance", description = "What the instance operator administers, across tenants (ADR-0057).")
@RestController
@RequestMapping("/api/v1/instance")
@RequiredArgsConstructor
@Slf4j
public class InstanceController {

  private final AccountAdministration accounts;
  private final OperatorDirectory operators;
  private final QuotaAdministration quotas;
  private final ErasureCertificates certificates;
  private final PluginRegistry plugins;
  private final TenantAdministration tenantAdministration;
  private final UserSessions sessions;

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
        .orElseThrow(() -> new NotFoundException("account", (UUID) null));
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
  public Page<AccountView> operators(
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {

    Page<AccountAdministration.AccountView> page = operators.operators(cursor, limit);
    return Page.of(
        page.data().stream().map(InstanceController::viewOf).toList(), page.nextCursor());
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
   * One page of erasure certificates, newest first (REQ-TEN-011).
   *
   * <p>Read here and nowhere else. A tenant that has been erased has no members left to ask, and
   * the table is instance-wide for exactly that reason: evidence of an erasure has to outlive the
   * thing it is about (07 §7.1).
   *
   * @param cursor an opaque cursor from a previous page, or omitted for the first
   * @param limit how many at most; capped at 200
   * @return the certificates
   */
  /**
   * Suspends a tenant, or lets it back in (REQ-SEC-082, REQ-TEN-011).
   *
   * <p>The immediate measure with the widest reach and the least damage: a suspended tenant answers
   * nothing at all — every request for it gets {@code 403 tenant-inaccessible} from the interceptor
   * that already handles the other inaccessible states — and not one row of its data is touched.
   * Reinstating it is this call with {@code ACTIVE} and leaves no trace in what the tenant sees.
   *
   * <p>It does <b>not</b> reach {@code PENDING_DELETION} or {@code ERASED}, in either direction. An
   * erasure is the tenant's own decision with a grace period and a revocation token, and an
   * operator who could set the state directly would start that clock without either — or, worse,
   * end it: withdrawing a deletion request is what the token is for.
   *
   * @param tenantId which tenant
   * @param request the state to move it to
   * @param user the operator taking the measure
   * @return the state as it now stands
   */
  @PutMapping(path = "/tenants/{tenantId}/state", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresEntitlement(Entitlement.INSTANCE_OPERATOR)
  @CanFail({ProblemType.FORBIDDEN, ProblemType.NOT_FOUND, ProblemType.VALIDATION_FAILED})
  public TenantLifecycleView setTenantState(
      @PathVariable UUID tenantId,
      @Valid @RequestBody TenantStateRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {

    return tenantAdministration
        .setState(tenantId, request.state(), user.userId())
        .map(state -> new TenantLifecycleView(tenantId, state.name()))
        .orElseThrow(() -> new NotFoundException("tenant", tenantId));
  }

  /**
   * Where a tenant stands, for the operator view.
   *
   * <p>All four states and not only the two the call above moves between: an operator looking at a
   * tenant that is waiting to be erased should see that, which is exactly why suspension will
   * refuse it.
   *
   * @param tenantId which tenant
   * @param user the operator
   * @return the state
   */
  @GetMapping(path = "/tenants/{tenantId}/state", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresEntitlement(Entitlement.INSTANCE_OPERATOR)
  @CanFail({ProblemType.FORBIDDEN, ProblemType.NOT_FOUND})
  public TenantLifecycleView tenantState(
      @PathVariable UUID tenantId, @AuthenticationPrincipal AuthenticatedUser user) {
    return tenantAdministration
        .stateOf(tenantId)
        .map(state -> new TenantLifecycleView(tenantId, state))
        .orElseThrow(() -> new NotFoundException("tenant", tenantId));
  }

  /**
   * Which state to move a tenant to.
   *
   * @param state {@code ACTIVE} or {@code SUSPENDED}
   */
  public record TenantStateRequest(@NotNull TenantAdministration.State state) {}

  /**
   * Where a tenant stands.
   *
   * <p>Not {@code TenantStateView}: {@code MemberController} already publishes one under that
   * name, for a tenant reading its own state, and springdoc names a schema after the simple class
   * name — so two of them would publish one shape and lose the other ({@code SchemaNameTest},
   * ADR-0080). The two are genuinely different views: that one answers a member about their own
   * tenant, this one answers the operator about any.
   *
   * @param id the tenant
   * @param state its lifecycle state
   */
  public record TenantLifecycleView(UUID id, String state) {}

  /**
   * Ends every session of one account (REQ-SEC-082).
   *
   * <p>The operator's half of {@code DELETE /api/v1/me/sessions}: the person whose account it is
   * can sign every device out themselves, and this is for when they cannot — a stolen account, an
   * employee who has left, a session somebody else is holding.
   *
   * <p>It signs them out and does not lock them out. An account whose password is still good signs
   * straight back in, which is correct: locking is a different measure with different consequences,
   * and an operator who wants it clears the entitlements or suspends the tenant.
   *
   * @param id the account
   * @param user the operator taking the measure
   * @return how many sessions were ended
   */
  @DeleteMapping(path = "/accounts/{id}/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresEntitlement(Entitlement.INSTANCE_OPERATOR)
  @CanFail({ProblemType.FORBIDDEN, ProblemType.NOT_FOUND})
  public SessionsEndedView endEverySession(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    accounts.byId(id).orElseThrow(() -> new NotFoundException("account", id));
    int ended = sessions.endAll(id);
    log.warn("Operator {} ended every session of account {} ({})", user.userId(), id, ended);
    return new SessionsEndedView(id, ended);
  }

  /**
   * What a mass sign-out did.
   *
   * @param accountId whose sessions
   * @param ended how many stopped
   */
  public record SessionsEndedView(UUID accountId, int ended) {}

  /**
   * Takes a plugin out of service, or puts it back (REQ-SEC-082, 09 §9.4).
   *
   * <p>One plugin stops being called at once, for every tenant, without uninstalling it and without
   * touching a grant — so putting it back is this call again and not a re-consent by every tenant
   * that had granted it.
   *
   * @param pluginId which plugin
   * @param request whether to disable it
   * @param user the operator
   * @return the plugin as it now stands
   */
  @PutMapping(path = "/plugins/{pluginId}/state", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresEntitlement(Entitlement.INSTANCE_OPERATOR)
  @CanFail({ProblemType.FORBIDDEN, ProblemType.NOT_FOUND, ProblemType.VALIDATION_FAILED})
  public PluginStateView setPluginState(
      @PathVariable @Size(max = 200) @Pattern(regexp = PluginManifestReader.ID_SHAPE)
          String pluginId,
      @Valid @RequestBody PluginStateRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {

    if (!plugins.setDisabled(pluginId, request.disabled(), user.userId())) {
      throw new NotFoundException("plugin", pluginId);
    }
    return new PluginStateView(pluginId, request.disabled());
  }

  /**
   * Whether a plugin should be out of service.
   *
   * @param disabled true to disable it
   */
  public record PluginStateRequest(@NotNull Boolean disabled) {}

  /**
   * Where a plugin stands.
   *
   * @param pluginId which plugin
   * @param disabled whether it is out of service
   */
  public record PluginStateView(String pluginId, boolean disabled) {}

  @GetMapping(path = "/erasures", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresEntitlement(Entitlement.INSTANCE_OPERATOR)
  @CanFail({ProblemType.FORBIDDEN, ProblemType.MALFORMED_REQUEST})
  public Page<ErasureCertificates.Certificate> erasures(
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return certificates.certificates(cursor, limit);
  }

  /**
   * The certificate for one tenant.
   *
   * @param tenantId the tenant that was erased
   * @return its certificate
   */
  @GetMapping(path = "/erasures/{tenantId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresEntitlement(Entitlement.INSTANCE_OPERATOR)
  @CanFail({ProblemType.FORBIDDEN, ProblemType.NOT_FOUND})
  public ErasureCertificates.Certificate erasure(@PathVariable UUID tenantId) {
    return certificates
        .forTenant(tenantId)
        .orElseThrow(() -> new NotFoundException("erasure", tenantId));
  }

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
      @Nullable Integer tenantLimit,
      boolean locked) {}

  /**
   * What is installed, and what the <b>instance</b> has permitted each one (ADR-0066).
   *
   * <p>The operator's half of the plugin surface. The tenant's half is {@code /api/v1/plugins},
   * which shows the same plugins with that tenant's grants beside them; the two answer different
   * questions and neither can answer the other's.
   *
   * @param limit how many at most; capped at 200
   * @return what is installed, with the instance-level grants
   */
  @GetMapping(path = "/plugins", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresEntitlement(Entitlement.INSTANCE_OPERATOR)
  @CanFail({ProblemType.FORBIDDEN, ProblemType.MALFORMED_REQUEST})
  public List<InstancePluginView> plugins(
      @RequestParam(required = false, defaultValue = "200") @Positive @Max(200) int limit) {
    return plugins.installed(limit).stream().map(this::instanceViewOf).toList();
  }

  /**
   * Permits one capability <b>for the instance itself</b> (ADR-0066).
   *
   * <p>This is not consent on any tenant's behalf and reaches no tenant's data: a call made under
   * an instance grant carries no tenant context, so every row-level policy yields zero rows. What
   * it makes possible is the account mail the deployment owes a person — a password reset, a new
   * second factor, a remote sign-out — including for somebody who is a member of no tenant
   * (REQ-NOTI-004, REQ-SEC-018).
   *
   * <p>Idempotent, and a capability the manifest does not declare is refused rather than recorded,
   * exactly as on the tenant path.
   *
   * @param pluginId the plugin
   * @param capability the capability, spelled as 09 §9.4 spells it
   * @param user the operator agreeing
   */
  @PutMapping(path = "/plugins/{pluginId}/capabilities/{capability}")
  @RequiresEntitlement(Entitlement.INSTANCE_OPERATOR)
  @CanFail({ProblemType.FORBIDDEN, ProblemType.NOT_FOUND, ProblemType.VALIDATION_FAILED})
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void grantInstanceCapability(
      @PathVariable @Size(max = 200) String pluginId,
      @PathVariable @Size(max = 100) String capability,
      @AuthenticationPrincipal AuthenticatedUser user) {
    plugins.grantForInstance(pluginId, capability, user.userId());
  }

  /**
   * Withdraws one instance-level capability (ADR-0066).
   *
   * <p>Withdrawing what was never granted is not an error: the outcome the caller wants is "this
   * plugin may not do this for the instance", and that is already true.
   *
   * @param pluginId the plugin
   * @param capability the capability
   * @param user the operator withdrawing it
   */
  @DeleteMapping(path = "/plugins/{pluginId}/capabilities/{capability}")
  @RequiresEntitlement(Entitlement.INSTANCE_OPERATOR)
  @CanFail({ProblemType.FORBIDDEN, ProblemType.NOT_FOUND})
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void withdrawInstanceCapability(
      @PathVariable @Size(max = 200) String pluginId,
      @PathVariable @Size(max = 100) String capability,
      @AuthenticationPrincipal AuthenticatedUser user) {
    plugins.revokeForInstance(pluginId, capability, user.userId());
  }

  /**
   * Maps a registration onto the operator's view of it.
   *
   * @param installed the registration
   * @return the response body, with the instance-level grants marked
   */
  private InstancePluginView instanceViewOf(PluginRegistry.Registration installed) {
    List<String> granted =
        plugins.instanceGrants(installed.pluginId()).stream()
            .map(PluginRegistry.Grant::capability)
            .toList();
    return new InstancePluginView(
        installed.pluginId(),
        installed.name(),
        installed.version(),
        installed.signed(),
        installed.disabled(),
        installed.stateReason(),
        installed.registeredAt(),
        installed.capabilities().stream()
            .map(capability -> new InstanceCapabilityView(capability, granted.contains(capability)))
            .toList());
  }

  /**
   * An installed plugin as the operator sees it.
   *
   * @param id its reverse-domain id
   * @param name what a person sees
   * @param version the plugin's own version
   * @param signed whether this core verified its manifest signature against the public key the
   *     operator installed for it (REQ-PLG-004, ADR-0085)
   * @param disabled whether it is out of service
   * @param stateReason why it is in that state, in a sentence, or null when there is nothing to say.
   *     This is the operator's surface, and a plugin taken out of service by its signature is
   *     exactly the case where "disabled" on its own tells them nothing they can act on — an altered
   *     manifest and a publisher who never signed are different problems with different answers
   * @param registeredAt when it was first seen
   * @param capabilities everything its manifest asks for, each with whether the <b>instance</b> has
   *     granted it. A tenant's grants are not shown here and are not this surface's business
   */
  public record InstancePluginView(
      String id,
      String name,
      String version,
      boolean signed,
      boolean disabled,
      @Nullable String stateReason,
      Instant registeredAt,
      List<InstanceCapabilityView> capabilities) {}

  /**
   * One capability, and whether the instance has granted it.
   *
   * @param id the capability, from the closed set of 09 §9.4
   * @param grantedForInstance whether the instance operator has agreed to it. False is where every
   *     capability starts, on this level as on the tenant's
   */
  public record InstanceCapabilityView(String id, boolean grantedForInstance) {}
}
