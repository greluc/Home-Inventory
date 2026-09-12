/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.PublicEndpoint;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.identity.api.AuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthenticationService authentication;

  private final SecurityContextRepository securityContextRepository =
      new HttpSessionSecurityContextRepository();

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
  @CanFail({ProblemType.UNAUTHENTICATED, ProblemType.RATE_LIMITED})
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

    // Rotate the session id rather than discarding the session. This is the
    // servlet container's own fixation defence: the id an attacker may have
    // planted is replaced, while the session object itself survives — which
    // invalidate-and-recreate does not, and which is what a client holding the
    // session would otherwise lose.
    if (httpRequest.getSession(false) != null) {
      httpRequest.changeSessionId();
    } else {
      httpRequest.getSession(true);
    }

    Authentication token = UsernamePasswordAuthenticationToken.authenticated(user, null, List.of());
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(token);
    SecurityContextHolder.setContext(context);
    securityContextRepository.saveContext(context, httpRequest, httpResponse);

    return new SessionView(user.userId(), user.tenantId(), user.email(), user.role());
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
    return new SessionView(user.userId(), user.tenantId(), user.email(), user.role());
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
   * The login body.
   *
   * @param email the address
   * @param password the password. Length-bounded, because Argon2id spends 19 MiB per attempt and an
   *     unbounded field is an invitation to spend it on a megabyte of nothing
   */
  public record LoginRequest(
      @NotBlank @Size(max = 320) String email, @NotBlank @Size(max = 200) String password) {}

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
  public record SessionView(UUID userId, UUID tenantId, String email, String role) {}
}
