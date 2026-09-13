/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.PublicEndpoint;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.identity.api.SecondFactor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
        enrolment.totpConfirmed(), enrolment.totpEnrolledAt(), enrolment.recoveryCodesLeft());
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
   * @param totpConfirmed whether a confirmed authenticator exists, which is what makes the second
   *     factor required at login
   * @param enrolledAt when it was confirmed, or null
   * @param recoveryCodesLeft how many single-use codes are unspent. Zero beside a confirmed factor
   *     is worth showing: losing the phone then loses the account
   */
  public record EnrolmentView(boolean totpConfirmed, Instant enrolledAt, int recoveryCodesLeft) {}

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
