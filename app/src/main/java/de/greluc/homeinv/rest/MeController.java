/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.PublicEndpoint;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.identity.api.UserSessions;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.tenancy.api.MembershipLookup;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
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
 * The session's own view of itself: which tenants the caller belongs to, and which one they are
 * acting for (REQ-TEN-003).
 *
 * <h2>Why this is {@code /me} and not {@code /tenants/{id}/switch}</h2>
 *
 * <p>The active tenant is a property of the session and of nothing else. Two people signed in at
 * once act for different tenants in the same instance, and a switch changes one of those sessions —
 * so the resource being changed is the session, which 08 §8.1 already calls {@code /me}. The
 * alternative spelling is a verb on a tenant, and 08 §8.1 asks for resources rather than actions.
 *
 * <h2>The switch changes the principal and nothing else</h2>
 *
 * <p>No re-authentication, no new session, no second credential — REQ-TEN-003 is explicit that a
 * person switches "without re-authenticating". What changes is the {@code tenantId} and the
 * {@code role} in the stored principal, which is what every later request reads its tenant context
 * and its permissions from. The session id stays: rotating it is the answer to a privilege
 * <em>escalation</em>, and this is a move sideways between tenants the caller already belongs to.
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
@Slf4j
public class MeController {

  private final MembershipLookup memberships;
  private final UserSessions sessions;

  private final SecurityContextRepository securityContextRepository =
      new HttpSessionSecurityContextRepository();

  /**
   * The sessions this account has open (REQ-AUTH-009).
   *
   * <p>Every device that is signed in, most recently seen first, each with a handle that stands in
   * for its session id. The id itself is never returned: it is what the cookie carries, so a list
   * of them would be a list of working credentials.
   *
   * <p>Bounded like every other collection (`REQ-NFR-010`), and the bound is on the answer rather
   * than on a query: the store returns one account's sessions and there is no stable order to page
   * through. Somebody with more open sessions than the limit sees the most recently used ones,
   * which are the ones a person deciding what to end is looking for.
   *
   * @param limit how many at most; capped at 200
   * @param user the authenticated principal
   * @param httpRequest the servlet request, so the caller's own session can be marked
   * @return the open sessions
   */
  @GetMapping(path = "/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
  @CanFail({ProblemType.UNAUTHENTICATED, ProblemType.MALFORMED_REQUEST})
  @PublicEndpoint(
      reason =
          "It lists the caller's own sessions and nobody else's. There is no role low "
              + "enough to be denied knowing where its own account is signed in, and an "
              + "account with no tenant has sessions like any other.")
  public List<UserSessions.OpenSession> sessions(
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit,
      @AuthenticationPrincipal AuthenticatedUser user,
      HttpServletRequest httpRequest) {
    return sessions.of(user.userId(), sessionIdOf(httpRequest)).stream().limit(limit).toList();
  }

  /**
   * Ends one session remotely (REQ-AUTH-009).
   *
   * <p>Immediately, not within the ten minutes the requirement allows: the session is removed from
   * the store every request reads, so the next request that device makes has none.
   *
   * <p>Ending the current one is allowed and is simply a sign-out — refusing it would be a rule to
   * explain for no benefit, since the caller can sign out anyway.
   *
   * @param handle which session, from the list
   * @param user the authenticated principal
   */
  @DeleteMapping("/sessions/{handle}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @CanFail({ProblemType.UNAUTHENTICATED, ProblemType.NOT_FOUND})
  @PublicEndpoint(
      reason =
          "It ends one of the caller's own sessions. The handle is looked up in that "
              + "account's own list, so a handle naming somebody else's session is not "
              + "found rather than refused.")
  public void endSession(
      @PathVariable @Size(max = 64) String handle, @AuthenticationPrincipal AuthenticatedUser user) {
    sessions.end(user.userId(), handle);
  }

  /**
   * The caller's own session id, for marking it in the list.
   *
   * @param request the servlet request
   * @return the id, or null when there is somehow no session
   */
  private static String sessionIdOf(HttpServletRequest request) {
    jakarta.servlet.http.HttpSession session = request.getSession(false);
    return session == null ? null : session.getId();
  }

  /**
   * Every tenant the caller belongs to, oldest membership first.
   *
   * <p>Read through the lookup that works without a tenant context, because the caller is asking
   * which tenant to have and therefore cannot be required to already have one.
   *
   * <p>There is no cursor, and there does not need to be one: a tenant limit is capped at 200
   * ({@code AppUser.MAX_TENANT_LIMIT}), which is REQ-NFR-010's page size, so one page holds every
   * membership anybody can have. The {@code limit} exists so that a client asking for fewer gets
   * fewer, and so that the bound is visible in the contract rather than only in a constraint.
   *
   * @param user the authenticated caller
   * @param limit how many at most; capped at 200, which is also the most anybody can have
   * @return the memberships, each with the tenant's name and the role held in it
   */
  @GetMapping(path = "/tenants", produces = MediaType.APPLICATION_JSON_VALUE)
  @PublicEndpoint(
      reason =
          "It reports on the caller's own memberships rather than on any tenant's data, and "
              + "the filter chain has already refused an unauthenticated caller with 401. There "
              + "is no role low enough to be denied the list of tenants it is a member of.")
  @CanFail({ProblemType.UNAUTHENTICATED, ProblemType.MALFORMED_REQUEST})
  public List<TenantMembershipView> tenants(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false, defaultValue = "200") @Positive @Max(200) int limit) {
    return memberships.membershipsOf(user.userId()).stream()
        .limit(limit)
        .map(m -> new TenantMembershipView(m.tenantId(), m.tenantName(), m.role()))
        .toList();
  }

  /**
   * Switches the session to another tenant the caller belongs to.
   *
   * <p>A tenant the caller is not a member of is a {@code 404} and never a {@code 403}: a denial
   * would confirm the tenant exists, which for somebody outside it is the fact they were not
   * supposed to learn (REQ-SEC-025). Switching to the tenant already active is allowed and does
   * nothing, because a client retrying a request whose answer it never saw must not get an error.
   *
   * @param request the tenant to act for
   * @param user the authenticated caller
   * @param httpRequest the servlet request, carrying the session
   * @param httpResponse the servlet response, which the rewritten context is saved to
   * @return the session as it now stands
   */
  @PostMapping(path = "/tenant", produces = MediaType.APPLICATION_JSON_VALUE)
  @PublicEndpoint(
      reason =
          "It changes which of the caller's own memberships the session uses. The membership "
              + "is the authorisation, and it is checked here; a permission would have to be "
              + "held in the tenant being left, which is the wrong tenant to ask.")
  @CanFail({ProblemType.NOT_FOUND, ProblemType.VALIDATION_FAILED, ProblemType.UNAUTHENTICATED})
  public SessionView switchTenant(
      @Valid @RequestBody SwitchTenantRequest request,
      @AuthenticationPrincipal AuthenticatedUser user,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse) {

    MembershipLookup.Membership membership =
        memberships.membershipsOf(user.userId()).stream()
            .filter(m -> m.tenantId().equals(request.tenantId()))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("tenant", request.tenantId()));

    AuthenticatedUser switched =
        new AuthenticatedUser(
            user.userId(),
            membership.tenantId(),
            user.email(),
            user.locale(),
            membership.role(),
            membership.roleDefinitionId(),
            membership.scopeLocationId());

    Authentication token =
        UsernamePasswordAuthenticationToken.authenticated(switched, null, List.of());
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(token);
    SecurityContextHolder.setContext(context);
    securityContextRepository.saveContext(context, httpRequest, httpResponse);

    log.info(
        "User {} switched from tenant {} to tenant {} as {}",
        user.userId(),
        user.tenantId(),
        membership.tenantId(),
        membership.role());

    return new SessionView(
        switched.userId(),
        switched.tenantId(),
        switched.email(),
        switched.locale(),
        switched.role());
  }

  /**
   * One of the caller's memberships.
   *
   * @param id the tenant
   * @param name its display name, which is what a switcher shows
   * @param role the role the caller holds in it
   */
  public record TenantMembershipView(UUID id, String name, String role) {}

  /**
   * The body of a switch.
   *
   * @param tenantId the tenant to act for. Must be one the caller is a member of; anything else is
   *     answered as though it did not exist
   */
  public record SwitchTenantRequest(@NotNull UUID tenantId) {}

  /**
   * Who the caller is after the switch.
   *
   * <p>The same shape {@code GET /api/v1/auth/me} answers with, so a client has one type for "the
   * session" however it changed.
   *
   * @param userId the person
   * @param tenantId the tenant this session now acts for
   * @param email the address they signed in with
   * @param locale their interface language
   * @param role the role they hold in the new tenant
   */
  public record SessionView(
      UUID userId, UUID tenantId, String email, String locale, String role) {}
}
