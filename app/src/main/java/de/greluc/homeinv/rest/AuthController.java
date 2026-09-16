/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.PublicEndpoint;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.identity.api.AuthenticationService;
import de.greluc.homeinv.identity.api.PasswordReset;
import de.greluc.homeinv.identity.api.SecondFactor;
import de.greluc.homeinv.identity.api.SecondFactorRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Logging in, logging out, and asking who you are.
 *
 * <p>An adapter, like every controller here: whether the credentials are good is decided by
 * {@link AuthenticationService}, and this class turns the outcome into a session.
 *
 * <p>A login is one call or two. An account with a second factor gets {@code 401
 * second-factor-required} from {@code /login} — the password was right, and nothing is
 * authenticated yet — and finishes at {@code /mfa} with the code (REQ-AUTH-002).
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

  private final AuthenticationService authentication;
  private final SecondFactor secondFactor;
  private final PendingLogin pendingLogin;
  private final SessionEstablisher sessions;
  private final PasswordReset passwordReset;

  /**
   * Verifies credentials and establishes a session.
   *
   * <p>Any session that existed before the login is invalidated and a new one created. That is what
   * prevents session fixation: an id an attacker planted beforehand is never the id the
   * authenticated session ends up with.
   *
   * @param request the credentials
   * @param httpRequest the servlet request, for the client address and the new session
   * @param httpResponse the servlet response, which the security context is written to
   * @return who the caller now is
   */
  @PostMapping(value = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
  @CanFail({
    ProblemType.UNAUTHENTICATED,
    ProblemType.SECOND_FACTOR_REQUIRED,
    ProblemType.RATE_LIMITED
  })
  @PublicEndpoint(
      reason =
          "It establishes the session every other permission is evaluated against. "
              + "Requiring one to obtain one is circular. Protected instead by the "
              + "per-account and per-address throttle of REQ-SEC-014.")
  public SessionView login(
      @Valid @RequestBody LoginRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse) {

    AuthenticatedUser user =
        authentication.login(request.email(), request.password(), clientAddressOf(httpRequest));

    // Half a login. The password was right, and the account has a second factor,
    // so nothing is authenticated yet: the principal waits in the session and the
    // caller answers at /api/v1/auth/mfa.
    if (secondFactor.isRequiredFor(user.userId())) {
      pendingLogin.remember(httpRequest, user);
      throw new SecondFactorRequiredException();
    }

    return sessions.establish(user, httpRequest, httpResponse, false);
  }

  /**
   * Answers the second factor and finishes the login (REQ-AUTH-002).
   *
   * <p>The pending login is consumed whether or not the code is right: a wrong one costs the
   * password again. An attacker holding a stolen cookie therefore gets one guess per password,
   * rather than as many as they like against a session that keeps waiting.
   *
   * @param request the code, which may be a TOTP code or a recovery code
   * @param httpRequest the servlet request, carrying the pending login
   * @param httpResponse the servlet response, which the security context is written to
   * @return who the caller now is
   */
  @PostMapping(value = "/mfa", produces = MediaType.APPLICATION_JSON_VALUE)
  @CanFail({ProblemType.UNAUTHENTICATED, ProblemType.SECOND_FACTOR_INVALID})
  @PublicEndpoint(
      reason =
          "It is the second half of the login, and the caller has no session to be "
              + "permitted by yet. What it does have is a pending login this rejects "
              + "without, and which is consumed on the first attempt either way.")
  public SessionView completeLogin(
      @Valid @RequestBody SecondFactorRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse) {

    AuthenticatedUser user = pendingLogin.claim(httpRequest);
    SecondFactor.Kind kind = SecondFactor.Kind.PASSKEY;
    if (request.isPasskey()) {
      secondFactor.verifyPasskey(
          user.userId(), request.credential(), PasskeyChallenge.claim(httpRequest));
    } else {
      kind = secondFactor.verify(user.userId(), request.requireCode());
    }
    SessionView session = sessions.establish(user, httpRequest, httpResponse, true);
    if (kind == SecondFactor.Kind.RECOVERY_CODE) {
      log.warn("Account {} completed a login with a recovery code.", user.userId());
    }
    return session;
  }

  /**
   * Ends the session.
   *
   * <p>Always {@code 204}, whether or not there was a session. A client cleaning up after itself
   * should not have to care, and the answer says nothing about whether the caller was logged in.
   *
   * @param httpRequest the servlet request
   */
  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PublicEndpoint(
      reason =
          "Ending a session needs no permission, and a caller whose session already "
              + "expired must still get a clean answer rather than a 401 they can do "
              + "nothing about.")
  public void logout(HttpServletRequest httpRequest) {
    HttpSession session = httpRequest.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    SecurityContextHolder.clearContext();
  }

  /**
   * Who the caller is.
   *
   * @param user the authenticated principal
   * @return the session view
   */
  @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
  @CanFail(ProblemType.UNAUTHENTICATED)
  @PublicEndpoint(
      reason =
          "It reports on the session rather than on tenant data, and the filter chain "
              + "has already refused an unauthenticated caller with 401. There is no "
              + "role low enough to be denied knowing who it is.")
  public SessionView me(@AuthenticationPrincipal AuthenticatedUser user) {
    return new SessionView(
        user.userId(), user.tenantId(), user.email(), user.locale(), user.role());
  }

  /**
   * The caller's address, for the per-IP login throttle.
   *
   * <p>The remote address of the connection, not a forwarded header. Trusting
   * {@code X-Forwarded-For} here would let anybody reset their own throttle by sending a different
   * value; that header is trusted only where the proxy chain is configured to strip and re-add it
   * (REQ-SEC-103), which is the container's concern rather than this method's.
   *
   * @param request the servlet request
   * @return the address to count failures against
   */
  private static String clientAddressOf(HttpServletRequest request) {
    String remote = request.getRemoteAddr();
    return remote == null ? "unknown" : remote;
  }


  /**
   * Asks for a password reset, and says nothing about whether there was anything to reset
   * (REQ-SEC-018).
   *
   * <p>Always {@code 204}. An address with an account gets a message with a link good for thirty
   * minutes; an address without one gets nothing, and the two answers are identical — a reset
   * endpoint that said "no such account" would be a way to ask the instance who is registered on it
   * (REQ-SEC-110).
   *
   * <p>Throttled on its own counters rather than the login ones: what this protects is somebody
   * else's mailbox, and a flood of requests must not lock the account holder out of signing in.
   *
   * @param request the address
   * @param httpRequest the servlet request, for the caller's address
   */
  @PostMapping("/password-reset")
  @CanFail(ProblemType.RATE_LIMITED)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PublicEndpoint(
      reason =
          "Somebody who cannot sign in is the only person who needs it, so requiring a session "
              + "would be circular. Protected instead by its own throttle and by answering "
              + "identically for an address with an account and one without (REQ-SEC-018, "
              + "REQ-SEC-110).")
  public void requestPasswordReset(
      @Valid @RequestBody PasswordResetRequest request, HttpServletRequest httpRequest) {
    passwordReset.request(request.email(), clientAddressOf(httpRequest));
  }

  /**
   * Redeems a reset token and sets the new password (REQ-SEC-018).
   *
   * <p>Ends every session the account has open, then tells the address the account had. A token
   * that is unknown, expired or already spent gets one answer for all three: telling them apart
   * tells somebody holding a stolen token which one they have.
   *
   * @param request the token and the new password
   */
  @PostMapping("/password-reset/complete")
  @CanFail(ProblemType.VALIDATION_FAILED)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PublicEndpoint(
      reason =
          "It is the other half of the reset: the caller has no session, which is why they are "
              + "here. What authorises it is the single-use token from the message, checked "
              + "against its stored hash (REQ-SEC-018, REQ-SEC-048).")
  public void completePasswordReset(@Valid @RequestBody PasswordResetCompletion request) {
    passwordReset.complete(request.token(), request.password());
  }

  /**
   * The body of a reset request.
   *
   * @param email the address to send the link to
   */
  public record PasswordResetRequest(@NotBlank @Size(max = 320) String email) {}

  /**
   * The body of a redemption.
   *
   * @param token what the message carried
   * @param password the new password
   */
  public record PasswordResetCompletion(
      @NotBlank @Size(max = 500) String token, @NotBlank @Size(max = 200) String password) {

    /**
     * The token's presence, and no password.
     *
     * <p>The same reason {@code LoginRequest} masks its own: Spring MVC logs the deserialised body
     * at {@code DEBUG} through the generated {@code toString}, and this record holds both a live
     * reset token and a password in clear (REQ-SEC-050).
     *
     * @return the record with both secrets masked
     */
    @Override
    public String toString() {
      return "PasswordResetCompletion[token=***, password=***]";
    }
  }

  /**
   * The body of a second-factor answer: a code, or a passkey assertion.
   *
   * <p>Exactly one of the two. One body for both because the question is the same one — "prove the
   * second factor" — and a client that had to choose an endpoint would be a client that has to know
   * which kinds this account has before it asks.
   *
   * @param code the six digits from the authenticator app, or one of the recovery codes, or null
   *     when a passkey is answering. Bounded because it reaches an Argon2id comparison, where an
   *     unbounded field is an invitation to spend 19 MiB on a megabyte of nothing
   * @param credential what {@code navigator.credentials.get()} produced, as JSON, or null when a
   *     code is answering. Bounded generously: an assertion carries a signature, client data and
   *     the authenticator's own bytes
   */
  public record SecondFactorRequest(
      @Size(max = 64) String code, @Size(max = 20_000) String credential) {

    /**
     * Checks that exactly one way of answering was given.
     *
     * @return the code, when that is what was sent
     * @throws de.greluc.homeinv.identity.api.InvalidSecondFactorException when neither or both were
     *     sent, which is a client that does not know what it is answering with
     */
    public String requireCode() {
      if (code == null || code.isBlank() || credential != null) {
        throw new de.greluc.homeinv.identity.api.InvalidSecondFactorException();
      }
      return code;
    }

    /**
     * Whether a passkey is answering.
     *
     * @return true when the body carries an assertion
     */
    public boolean isPasskey() {
      return credential != null && !credential.isBlank();
    }

    /**
     * The fact that there was an answer, and never the answer.
     *
     * <p>Spring MVC logs the deserialised body at {@code DEBUG} through this record's generated
     * {@code toString}; a one-time code in a log is a one-time code somebody else can still use
     * inside its window (REQ-SEC-050). The same trap {@link LoginRequest} carries.
     *
     * @return the record with both fields masked
     */
    @Override
    public String toString() {
      return "SecondFactorRequest[code=***, credential=***]";
    }
  }

  /**
   * The login body.
   *
   * @param email the address
   * @param password the password. Length-bounded, because Argon2id spends 19 MiB per attempt and an
   *     unbounded field is an invitation to spend it on a megabyte of nothing
   */
  public record LoginRequest(
      @NotBlank @Size(max = 320) String email, @NotBlank @Size(max = 200) String password) {

    /**
     * The address, and the fact that there was a password.
     *
     * <p>Not decoration. Spring MVC logs the deserialised body at {@code DEBUG} — "Read
     * application/json to [...]" — through the record's generated {@code toString}, so with debug
     * logging on, every password anybody signed in with was written to the log in clear
     * (REQ-SEC-050). {@code LogHygieneIT} found it and now keeps it found.
     *
     * @return the record with the password masked
     */
    @Override
    public String toString() {
      return "LoginRequest[email=" + email + ", password=***]";
    }
  }

  /**
   * Who the caller is.
   *
   * @param userId the person
   * @param tenantId the tenant this session acts for
   * @param email the address they logged in with
   * @param role the membership's role. Returned so the client can decide what to *offer*; it never
   *     decides what is allowed, which happens in the application layer on every request
   *     (REQ-SEC-022). A UI that shows a button nobody may press teaches people to ignore errors.
   */
  public record SessionView(
      UUID userId, UUID tenantId, String email, String locale, String role) {}
}
