/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.PublicEndpoint;
import de.greluc.homeinv.authorization.api.RequiresRecentSecondFactor;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.identity.api.SecondFactor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Setting up the second factor, and taking it off again (REQ-AUTH-002).
 *
 * <p>Everything here is about the caller's own account and takes no user id. There is deliberately
 * no path by which anybody administers somebody else's authenticator: an instance operator
 * administers entitlements (ADR-0057), and a person who has lost their phone uses a recovery code
 * rather than asking an administrator to become able to sign in as them.
 *
 * <p>Enrolment is two calls. The first generates a secret and shows it once; the second proves a
 * code from it, and only then does the factor count. An enrolment that counted on the first call
 * would lock somebody out of their own account for scanning a QR code and closing the app.
 */
@RestController
@RequestMapping("/api/v1/auth/mfa")
@RequiredArgsConstructor
public class SecondFactorController {

  private final SecondFactor secondFactor;
  private final SessionEstablisher sessions;
  private final PendingLogin pendingLogin;

  /**
   * Proves the second factor again, for an operation that asks (REQ-AUTH-011).
   *
   * <p>The same code the login takes, against a session that already exists. What it changes is one
   * instant in that session: for the next fifteen minutes the operations of 12 §12.4 are permitted
   * and sensitive fields are shown.
   *
   * @param request the code
   * @param user the authenticated principal
   * @param httpRequest the servlet request, whose session records the proof
   */
  @PostMapping(value = "/step-up")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @CanFail({ProblemType.UNAUTHENTICATED, ProblemType.SECOND_FACTOR_INVALID})
  @PublicEndpoint(
      reason =
          "It proves the caller's own second factor and grants nothing else. A "
              + "permission would be a way for a role to decide whether somebody may "
              + "confirm who they are.")
  public void stepUp(
      @Valid @RequestBody AuthController.SecondFactorRequest request,
      @AuthenticationPrincipal AuthenticatedUser user,
      jakarta.servlet.http.HttpServletRequest httpRequest) {
    if (request.isPasskey()) {
      secondFactor.verifyPasskey(
          user.userId(), request.credential(), PasskeyChallenge.claim(httpRequest));
    } else {
      secondFactor.verify(user.userId(), request.requireCode());
    }
    sessions.secondFactorProved(httpRequest);
  }

  /**
   * The options a browser needs to prove a passkey (REQ-AUTH-002).
   *
   * <p>Serves both moments a passkey is proved: the second half of a login, where the account comes
   * from the pending login the password left behind, and a re-confirmation, where it comes from the
   * session. Hence no session requirement and no user id in the request — the one thing a caller
   * may not do is say whose passkeys it wants the options for.
   *
   * @param user the authenticated principal, or null in the middle of a login
   * @param httpRequest the servlet request, whose session carries the pending login and takes the
   *     challenge
   * @return the options, as JSON, for {@code navigator.credentials.get()}
   */
  @PostMapping(value = "/passkeys/challenge", produces = MediaType.APPLICATION_JSON_VALUE)
  @CanFail({ProblemType.UNAUTHENTICATED, ProblemType.SECOND_FACTOR_INVALID})
  @PublicEndpoint(
      reason =
          "Half of it happens between the password and the session, where there is "
              + "nothing to be permitted by. It reveals the credential ids of an account "
              + "whose password has just been presented, and nothing else.")
  public CeremonyView passkeyChallenge(
      @AuthenticationPrincipal AuthenticatedUser user,
      jakarta.servlet.http.HttpServletRequest httpRequest) {
    UUID account = user != null ? user.userId() : pendingLogin.peek(httpRequest).userId();
    SecondFactor.Ceremony ceremony = secondFactor.beginPasskeyAssertion(account);
    PasskeyChallenge.rememberAssertion(httpRequest, ceremony.challenge());
    return new CeremonyView(ceremony.optionsJson());
  }

  /**
   * The options a browser needs to create a passkey (REQ-AUTH-002).
   *
   * @param user the authenticated principal
   * @param httpRequest the servlet request, whose session takes the challenge
   * @return the options, as JSON, for {@code navigator.credentials.create()}
   */
  @PostMapping(value = "/passkeys", produces = MediaType.APPLICATION_JSON_VALUE)
  @CanFail(ProblemType.UNAUTHENTICATED)
  @PublicEndpoint(
      reason =
          "Registering a passkey is something every account may do for itself, whatever "
              + "role it holds — the same case the TOTP enrolment beside it makes.")
  public CeremonyView beginPasskey(
      @AuthenticationPrincipal AuthenticatedUser user,
      jakarta.servlet.http.HttpServletRequest httpRequest) {
    SecondFactor.Ceremony ceremony =
        secondFactor.beginPasskeyRegistration(user.userId(), user.email(), user.email());
    PasskeyChallenge.rememberRegistration(httpRequest, ceremony.challenge());
    return new CeremonyView(ceremony.optionsJson());
  }

  /**
   * Finishes registering a passkey.
   *
   * @param request what the browser produced, and what to call this authenticator
   * @param user the authenticated principal
   * @param httpRequest the servlet request, whose session holds the challenge
   */
  @PostMapping(value = "/passkeys/confirmation")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @CanFail({ProblemType.UNAUTHENTICATED, ProblemType.SECOND_FACTOR_INVALID})
  @PublicEndpoint(
      reason =
          "The other half of a registration every account may make for itself. It "
              + "verifies a response against the caller's own challenge and can do nothing "
              + "else.")
  public void confirmPasskey(
      @Valid @RequestBody PasskeyRegistrationRequest request,
      @AuthenticationPrincipal AuthenticatedUser user,
      jakarta.servlet.http.HttpServletRequest httpRequest) {
    secondFactor.confirmPasskeyRegistration(
        user.userId(),
        request.credential(),
        PasskeyChallenge.claimRegistration(httpRequest),
        request.label());
  }

  /**
   * Removes one passkey.
   *
   * <p>Asks for the second factor to have been proved recently (REQ-AUTH-011) rather than for a
   * code in the body, which is how the TOTP removal beside it works: an account whose only factor
   * is a passkey has no code to type, and the re-confirmation takes either kind.
   *
   * @param passkeyId which one
   * @param user the authenticated principal
   */
  @PostMapping(value = "/passkeys/{passkeyId}/removal")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresRecentSecondFactor
  @CanFail({ProblemType.UNAUTHENTICATED, ProblemType.NOT_FOUND, ProblemType.SECOND_FACTOR_STALE})
  @PublicEndpoint(
      reason =
          "Taking off one's own passkey needs the second factor proved again and nothing "
              + "else. A permission would be a way for somebody else's role to decide it.")
  public void removePasskey(
      @PathVariable UUID passkeyId, @AuthenticationPrincipal AuthenticatedUser user) {
    secondFactor.removePasskey(user.userId(), passkeyId);
  }

  /**
   * What the caller's account holds.

  /**
   * What the caller's account holds.
   *
   * @param user the authenticated principal
   * @return the enrolment
   */
  @GetMapping(value = "/enrolment", produces = MediaType.APPLICATION_JSON_VALUE)
  @CanFail(ProblemType.UNAUTHENTICATED)
  @PublicEndpoint(
      reason =
          "It reports on the caller's own credentials rather than on tenant data, and "
              + "the filter chain has already refused an unauthenticated caller. There is "
              + "no role low enough to be denied knowing whether it has a second factor.")
  public EnrolmentView enrolment(@AuthenticationPrincipal AuthenticatedUser user) {
    SecondFactor.Enrolment enrolment = secondFactor.enrolmentOf(user.userId());
    return new EnrolmentView(
        enrolment.totpConfirmed(),
        enrolment.totpEnrolledAt(),
        enrolment.recoveryCodesLeft(),
        enrolment.passkeys().stream()
            .map(
                passkey ->
                    new PasskeyView(
                        passkey.id(),
                        passkey.label(),
                        passkey.registeredAt(),
                        passkey.lastUsedAt()))
            .toList());
  }

  /**
   * Begins a TOTP enrolment.
   *
   * <p>The secret is returned <b>once</b>, here, and is unreadable afterwards: it is sealed under
   * the instance's credential key before it is stored. A client that loses it asks again, which
   * replaces the unconfirmed enrolment.
   *
   * @param user the authenticated principal
   * @return the secret and the {@code otpauth://} URI a QR code carries
   */
  @PostMapping(value = "/totp", produces = MediaType.APPLICATION_JSON_VALUE)
  @CanFail({ProblemType.UNAUTHENTICATED, ProblemType.SECOND_FACTOR_ENROLLED})
  @PublicEndpoint(
      reason =
          "Enrolling a second factor is something every account may do for itself, "
              + "whatever role it holds — including an account that holds none because it "
              + "is in no tenant yet.")
  public TotpEnrolmentView beginTotp(@AuthenticationPrincipal AuthenticatedUser user) {
    SecondFactor.TotpEnrolment enrolment =
        secondFactor.beginTotpEnrolment(user.userId(), user.email());
    return new TotpEnrolmentView(enrolment.secret(), enrolment.provisioningUri());
  }

  /**
   * Confirms an enrolment with a code, and issues the recovery codes.
   *
   * <p>The codes are returned <b>once</b>, here. They are stored as Argon2id hashes, so nothing —
   * this application included — can show them again.
   *
   * @param request the code from the authenticator app
   * @param user the authenticated principal
   * @return the recovery codes, in clear, for the only time they are readable
   */
  @PostMapping(
      value = "/totp/confirmation",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @CanFail({ProblemType.UNAUTHENTICATED, ProblemType.SECOND_FACTOR_INVALID})
  @PublicEndpoint(
      reason =
          "The other half of an enrolment every account may make for itself. It proves a "
              + "code against the caller's own unconfirmed secret and can do nothing else.")
  public RecoveryCodesView confirmTotp(
      @Valid @RequestBody AuthController.SecondFactorRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    return new RecoveryCodesView(
        secondFactor.confirmTotpEnrolment(user.userId(), request.code()));
  }

  /**
   * Issues a fresh set of recovery codes, retiring whatever is left of the old one.
   *
   * @param user the authenticated principal
   * @return the new codes, in clear, for the only time they are readable
   */
  @PostMapping(value = "/recovery-codes", produces = MediaType.APPLICATION_JSON_VALUE)
  @CanFail({ProblemType.UNAUTHENTICATED, ProblemType.SECOND_FACTOR_INVALID})
  @PublicEndpoint(
      reason =
          "It reissues the caller's own recovery codes and refuses when the caller holds "
              + "no second factor. No role decides that; holding the factor does.")
  public RecoveryCodesView reissueRecoveryCodes(@AuthenticationPrincipal AuthenticatedUser user) {
    return new RecoveryCodesView(secondFactor.reissueRecoveryCodes(user.userId()));
  }

  /**
   * Removes the second factor, with a code as the proof.
   *
   * <p>A {@code POST} to a noun rather than a {@code DELETE}, because the proof travels in a body:
   * a one-time code in a query string is a one-time code in an access log.
   *
   * @param request a current code, or a recovery code
   * @param user the authenticated principal
   */
  @PostMapping(value = "/totp/removal")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @CanFail({ProblemType.UNAUTHENTICATED, ProblemType.SECOND_FACTOR_INVALID})
  @PublicEndpoint(
      reason =
          "Taking off one's own second factor needs the factor itself and nothing else. "
              + "A permission would be a way for somebody else's role to decide it.")
  public void removeTotp(
      @Valid @RequestBody AuthController.SecondFactorRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    secondFactor.removeTotp(user.userId(), request.code());
  }

  /**
   * What an account holds.
   *
   * @param totpConfirmed whether a confirmed authenticator app exists
   * @param enrolledAt when it was confirmed, or null
   * @param recoveryCodesLeft how many single-use codes are unspent. Zero beside a confirmed factor
   *     is worth showing: losing the phone then loses the account
   * @param passkeys the registered passkeys, oldest first
   */
  public record EnrolmentView(
      boolean totpConfirmed,
      Instant enrolledAt,
      int recoveryCodesLeft,
      List<PasskeyView> passkeys) {}

  /**
   * One registered passkey.
   *
   * @param id the credential, for removing it
   * @param label what the person called it
   * @param registeredAt when it was registered
   * @param lastUsedAt when it was last used, or null
   */
  public record PasskeyView(UUID id, String label, Instant registeredAt, Instant lastUsedAt) {}

  /**
   * The options one side of a ceremony needs.
   *
   * @param options the WebAuthn options, as the JSON {@code navigator.credentials} takes. Passed
   *     through as text rather than re-modelled: the shape is the specification's, and a second
   *     model of it here would be a second thing to keep in step with browsers
   */
  public record CeremonyView(String options) {}

  /**
   * What finishes a passkey registration.
   *
   * @param credential what {@code navigator.credentials.create()} produced, as JSON
   * @param label what to call this authenticator, or null
   */
  public record PasskeyRegistrationRequest(
      @NotBlank @Size(max = 20_000) String credential, @Size(max = 100) String label) {

    /**
     * The fact that there was a response, and never its contents.
     *
     * @return the record with the response masked
     */
    @Override
    public String toString() {
      return "PasskeyRegistrationRequest[credential=***, label=" + label + "]";
    }
  }

  /**
   * A secret that has just been generated.
   *
   * @param secret the shared secret in base32, for somebody typing it in by hand
   * @param provisioningUri the {@code otpauth://} URI a QR code carries
   */
  public record TotpEnrolmentView(String secret, String provisioningUri) {

    /**
     * The fact that there was a secret, and never the secret.
     *
     * @return the record with both values masked
     */
    @Override
    public String toString() {
      return "TotpEnrolmentView[secret=***, provisioningUri=***]";
    }
  }

  /**
   * The recovery codes, readable this once.
   *
   * @param codes ten single-use codes
   */
  public record RecoveryCodesView(List<String> codes) {

    /**
     * How many codes there were, and never the codes.
     *
     * @return the record with the codes masked
     */
    @Override
    public String toString() {
      return "RecoveryCodesView[codes=*** (" + codes.size() + ")]";
    }
  }
}
