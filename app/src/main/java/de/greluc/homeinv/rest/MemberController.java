/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.authorization.api.RequiresRecentSecondFactor;
import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.tenancy.api.InvitationService;
import de.greluc.homeinv.tenancy.api.MembershipAdministration;
import de.greluc.homeinv.tenancy.api.QuotaGuard;
import de.greluc.homeinv.tenancy.api.TenantLifecycle;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * Who is in a tenant, and who has been asked (REQ-TEN-004, REQ-TEN-005, REQ-TEN-010).
 *
 * <h2>The tenant in the path is checked, not used</h2>
 *
 * <p>08 §8.1 spells these paths {@code /tenants/{id}/members} and {@code /tenants/{id}/invitations},
 * and the {@code id} is deliberately <b>not</b> what decides which tenant is read. That comes from
 * the session, as REQ-SEC-004 requires; a path segment the caller controls must never set the
 * context. What the segment is for is catching a client that has switched tenants in one tab and
 * not another: an id that is not the session's is answered {@code 404}, the same answer a tenant
 * the caller does not belong to gets, because to somebody outside it the two are the same fact.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
@RequiredArgsConstructor
public class MemberController {

  private final MembershipAdministration members;
  private final InvitationService invitations;
  private final QuotaGuard quotaGuard;
  private final TenantLifecycle lifecycle;

  /**
   * One page of the tenant's members.
   *
   * @param tenantId the tenant, which must be the session's
   * @param user the authenticated caller
   * @param cursor an opaque cursor from a previous page, or omitted for the first
   * @param limit how many at most; capped at 200
   * @return the page
   */
  @GetMapping(path = "/members", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.MEMBER_READ)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.FORBIDDEN, ProblemType.MALFORMED_REQUEST})
  public Page<MembershipAdministration.MemberView> members(
      @PathVariable UUID tenantId,
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {

    requireOwnTenant(tenantId, user);
    return members.members(cursor, limit);
  }

  /**
   * Changes what a member may do.
   *
   * @param tenantId the tenant, which must be the session's
   * @param userId the member
   * @param request the new role
   * @param user the authenticated caller
   * @return the member as they now stand
   */
  @RequiresRecentSecondFactor
  @PutMapping(path = "/members/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.MEMBER_UPDATE)
  @CanFail({
    ProblemType.NOT_FOUND,
    ProblemType.FORBIDDEN,
    ProblemType.ROLE_ESCALATION,
    ProblemType.LAST_OWNER,
    ProblemType.VALIDATION_FAILED
  })
  public MembershipAdministration.MemberView changeRole(
      @PathVariable UUID tenantId,
      @PathVariable UUID userId,
      @Valid @RequestBody RoleRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {

    requireOwnTenant(tenantId, user);
    return members.changeRole(
        userId,
        request.role(),
        request.roleDefinitionId(),
        request.scopeLocationId(),
        user.userId());
  }

  /**
   * Removes somebody from the tenant.
   *
   * @param tenantId the tenant, which must be the session's
   * @param userId the member
   * @param user the authenticated caller
   */
  @DeleteMapping("/members/{userId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresPermission(Permission.MEMBER_REMOVE)
  @CanFail({
    ProblemType.NOT_FOUND,
    ProblemType.FORBIDDEN,
    ProblemType.ROLE_ESCALATION,
    ProblemType.LAST_OWNER
  })
  public void removeMember(
      @PathVariable UUID tenantId,
      @PathVariable UUID userId,
      @AuthenticationPrincipal AuthenticatedUser user) {

    requireOwnTenant(tenantId, user);
    members.remove(userId, user.userId());
  }

  /**
   * Invites somebody into the tenant.
   *
   * <p>The answer carries the token, once. Where {@code plugin-smtp} is installed the invitation
   * also goes out by mail; where it is not — which a {@code minimal} installation is by design —
   * this is the only way the link reaches anybody, and 04 §4.3 describes that as the expected
   * reduced state rather than a failure.
   *
   * @param tenantId the tenant, which must be the session's
   * @param request the address and the role
   * @param user the authenticated caller
   * @return the invitation and its one token
   */
  @RequiresRecentSecondFactor
  @PostMapping(path = "/invitations", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresPermission(Permission.MEMBER_INVITE)
  @CanFail({
    ProblemType.NOT_FOUND,
    ProblemType.FORBIDDEN,
    ProblemType.ROLE_ESCALATION,
    ProblemType.INVITATION_ALREADY_OPEN,
    ProblemType.VALIDATION_FAILED
  })
  public InvitationService.IssuedInvitation invite(
      @PathVariable UUID tenantId,
      @Valid @RequestBody InviteRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {

    requireOwnTenant(tenantId, user);
    return invitations.invite(request.email(), request.role(), user.userId());
  }

  /**
   * One page of the tenant's invitations, newest first.
   *
   * @param tenantId the tenant, which must be the session's
   * @param user the authenticated caller
   * @param cursor an opaque cursor from a previous page, or omitted for the first
   * @param limit how many at most; capped at 200
   * @return the page, without any tokens
   */
  @GetMapping(path = "/invitations", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.MEMBER_INVITE)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.FORBIDDEN, ProblemType.MALFORMED_REQUEST})
  public Page<InvitationService.InvitationView> invitations(
      @PathVariable UUID tenantId,
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {

    requireOwnTenant(tenantId, user);
    return invitations.invitations(cursor, limit);
  }

  /**
   * Withdraws an invitation that has not been used.
   *
   * @param tenantId the tenant, which must be the session's
   * @param invitationId the invitation
   * @param user the authenticated caller
   */
  @DeleteMapping("/invitations/{invitationId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresPermission(Permission.MEMBER_REMOVE)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.FORBIDDEN})
  public void revokeInvitation(
      @PathVariable UUID tenantId,
      @PathVariable UUID invitationId,
      @AuthenticationPrincipal AuthenticatedUser user) {

    requireOwnTenant(tenantId, user);
    invitations.revoke(invitationId, user.userId());
  }

  /**
   * Where this tenant stands in each of its four quotas (REQ-TEN-009).
   *
   * <p>Readable by anybody who may read the tenant, so a client can warn before a limit is reached
   * rather than after. Deliberately exempt from the API-call quota itself: "how much have I used"
   * has to be answerable when the answer is "all of it".
   *
   * <p>Keyed by quota rather than a list, and not to dodge the page cap of REQ-NFR-010: there are
   * exactly four quotas and there will be exactly four tomorrow, so a cursor would page a set that
   * cannot grow. A key is also what a client wants — it reads one quota, not the third element.
   *
   * @param tenantId the tenant, which must be the session's
   * @param user the authenticated caller
   * @return one entry per quota, with what is used and what is permitted
   */
  @GetMapping(path = "/quotas", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TENANT_READ)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.FORBIDDEN})
  public Map<QuotaGuard.Quota, QuotaState> quotas(
      @PathVariable UUID tenantId, @AuthenticationPrincipal AuthenticatedUser user) {
    requireOwnTenant(tenantId, user);
    Map<QuotaGuard.Quota, QuotaState> answer = new EnumMap<>(QuotaGuard.Quota.class);
    for (QuotaGuard.QuotaView view : quotaGuard.usage()) {
      answer.put(view.quota(), new QuotaState(view.used(), view.permitted()));
    }
    return answer;
  }

  /**
   * Where a tenant stands in one quota.
   *
   * @param used how much is in use now
   * @param permitted how much is allowed
   */
  public record QuotaState(long used, long permitted) {}

  /**
   * Asks for the tenant to be erased (REQ-TEN-011, REQ-PRIV-005).
   *
   * <p>Answered {@code 202}: nothing is erased, and for thirty days nothing will be. The tenant
   * stops answering at once — 05 §5.9's "access blocked immediately, data still present" — and the
   * answer carries the token that withdraws the request and the instant the erasure begins.
   *
   * <p>The token comes back in the response because that is the only way it reaches anybody where
   * {@code plugin-smtp} is not installed, which a {@code minimal} installation is by design. Where
   * it is, the same link goes out by mail.
   *
   * <p>{@code REQ-SEC-021} requires the second factor to be confirmed again for an operation like
   * this one. It is wired in with the second factor itself; until then the permission and the
   * owner's session are what stands in front of it, and no release ships without the rest.
   *
   * @param tenantId the tenant, which must be the session's
   * @param user the owner asking
   * @return the revocation token and when the erasure begins
   */
  @RequiresRecentSecondFactor
  @DeleteMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.ACCEPTED)
  @RequiresPermission(Permission.TENANT_DELETE)
  @CanFail({
    ProblemType.NOT_FOUND,
    ProblemType.FORBIDDEN,
    ProblemType.DELETION_PENDING
  })
  public TenantLifecycle.DeletionRequest requestDeletion(
      @PathVariable UUID tenantId, @AuthenticationPrincipal AuthenticatedUser user) {
    requireOwnTenant(tenantId, user);
    return lifecycle.requestDeletion(user.userId());
  }

  /**
   * What state the tenant is in.
   *
   * <p>Reachable while it is blocked, deliberately: a member who is being refused everything else
   * is entitled to know why, and 05 §5.9 and open point O26 both say the state is visible to a
   * member in the administration view.
   *
   * @param tenantId the tenant, which must be the session's
   * @param user the authenticated caller
   * @return the state
   */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TENANT_READ)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.FORBIDDEN})
  public TenantStateView state(
      @PathVariable UUID tenantId, @AuthenticationPrincipal AuthenticatedUser user) {
    requireOwnTenant(tenantId, user);
    return new TenantStateView(tenantId, lifecycle.state().name());
  }

  /**
   * A tenant's state.
   *
   * @param id the tenant
   * @param state {@code ACTIVE}, {@code SUSPENDED} or {@code PENDING_DELETION}
   */
  public record TenantStateView(UUID id, String state) {}

  /**
   * Refuses a path that names a tenant other than the session's.
   *
   * <p>{@code 404} rather than {@code 403}, for the reason REQ-SEC-025 gives everywhere else: a
   * denial confirms the tenant exists, and for somebody outside it that is the fact they were not
   * supposed to learn. The caller sees the same answer whether the id is a tenant they left, a
   * tenant that belongs to a stranger, or no tenant at all.
   *
   * @param tenantId the id in the path
   * @param user the authenticated caller
   */
  private static void requireOwnTenant(UUID tenantId, AuthenticatedUser user) {
    if (!tenantId.equals(user.tenantId())) {
      throw new NotFoundException("tenant", tenantId);
    }
  }

  /**
   * The body of a role change.
   *
   * @param role one of {@code OWNER}, {@code ADMIN}, {@code MEMBER}, {@code CONTRIBUTOR},
   *     {@code VIEWER}, {@code GUEST}. A name this build does not know grants nothing and is
   *     refused
   * @param roleDefinitionId a tenant-owned role extending it (REQ-TEN-006), or omitted for a plain
   *     built-in role. Where it is given it decides the base, and {@code role} is ignored
   * @param scopeLocationId a location to confine them to (REQ-TEN-007), or omitted for the whole
   *     tenant
   */
  public record RoleRequest(
      @NotBlank @Size(max = 32) String role, UUID roleDefinitionId, UUID scopeLocationId) {}

  /**
   * The body of an invitation.
   *
   * @param email the address to invite, which the invitation is bound to
   * @param role the role to grant on acceptance, bounded by what the inviter holds (REQ-TEN-010)
   */
  public record InviteRequest(
      @NotBlank @Email @Size(max = 320) String email, @NotBlank @Size(max = 32) String role) {}
}
