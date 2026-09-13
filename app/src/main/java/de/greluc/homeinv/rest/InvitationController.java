/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.PublicEndpoint;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.tenancy.api.InvitationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Redeeming an invitation (REQ-TEN-004), which is also how an account is created (REQ-AUTH-004).
 *
 * <p>The one endpoint besides the login that a caller with no session may reach, and it has to be:
 * accepting an invitation is what makes somebody a person on this instance. There is no open
 * registration — {@code HOMEINV_REGISTRATION_MODE} defaults to {@code invite_only} — so this is the
 * whole of the way in.
 *
 * <p>The tenant context is established from the token, through the {@code SECURITY DEFINER} lookup
 * of 07 §7.5, and never from anything in the request. That is the same rule REQ-SEC-004 states for
 * a session's tenant, applied to the one flow that has no session yet.
 */
@RestController
@RequestMapping("/api/v1/invitations")
@RequiredArgsConstructor
public class InvitationController {

  private final InvitationService invitations;

  /**
   * Redeems an invitation.
   *
   * <p>What the body must carry depends on the address. If it has no account, this creates one and
   * {@code displayName} and {@code password} are required; if it has one, the caller must already
   * be signed in as it and the body's account fields are ignored.
   *
   * @param token the secret from the invitation link
   * @param request the new account's details, where an account is being created
   * @param user the caller's session, or null when nobody is signed in
   * @return the tenant joined, the account, and whether this created it
   */
  @PostMapping(path = "/{token}/accept", produces = MediaType.APPLICATION_JSON_VALUE)
  @PublicEndpoint(
      reason =
          "Accepting an invitation is how somebody becomes a person on this instance, so "
              + "requiring an account to do it is circular. The token is the credential: 256 "
              + "bits from a secure source, stored only as a hash, single-use and "
              + "time-limited (REQ-TEN-004).")
  @CanFail({
    ProblemType.INVITATION_UNUSABLE,
    ProblemType.INVITATION_NOT_YOURS,
    ProblemType.VALIDATION_FAILED
  })
  public InvitationService.AcceptedInvitation accept(
      @PathVariable @Size(max = 200) String token,
      @Valid @RequestBody AcceptRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {

    return invitations.accept(
        token,
        request.displayName(),
        request.locale(),
        request.password(),
        user == null ? null : user.userId());
  }

  /**
   * The body of an acceptance.
   *
   * @param displayName what the interface should call the new account; ignored when the address
   *     already has one
   * @param locale the interface language to start in; ignored when the account exists
   * @param password the new account's password. Length-bounded for the reason the login's is:
   *     Argon2id spends 19 MiB per hash, and an unbounded field is an invitation to spend it on a
   *     megabyte of nothing
   */
  public record AcceptRequest(
      @Size(max = 200) String displayName,
      @Size(max = 16) String locale,
      @Size(max = 200) String password) {

    /**
     * The record without its password.
     *
     * <p>Spring MVC logs the deserialised body at {@code DEBUG} through the generated
     * {@code toString}, which is how every password anybody signed in with once reached the log
     * (REQ-SEC-050). {@code LogHygieneIT} keeps that found; this keeps it absent.
     *
     * @return the record with the password masked
     */
    @Override
    public String toString() {
      return "AcceptRequest[displayName=" + displayName + ", locale=" + locale + ", password=***]";
    }
  }
}
