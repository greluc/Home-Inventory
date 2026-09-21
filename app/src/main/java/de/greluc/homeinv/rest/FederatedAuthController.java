/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.PublicEndpoint;
import de.greluc.homeinv.authorization.api.RequiresRecentSecondFactor;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.identity.api.AuthenticationService;
import de.greluc.homeinv.identity.api.FederatedSignIn;
import de.greluc.homeinv.identity.api.SecondFactor;
import de.greluc.homeinv.identity.api.SecondFactorRequiredException;
import de.greluc.homeinv.platform.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * Signing in through a provider the operator installed (REQ-AUTH-005, REQ-AUTH-006).
 *
 * <h2>Two kinds of caller, one endpoint</h2>
 *
 * <p>The callback is reached by a <b>browser following the provider's redirect</b>, not by a client
 * that can read a JSON body. So a refusal answers a browser with a redirect to the sign-in page
 * carrying the problem token, and anything else with {@code application/problem+json} as every
 * other endpoint does. One branch, on {@code Accept}, and both are tested.
 *
 * <h2>What this class decides and what it does not</h2>
 *
 * <p>Who the caller turned out to be is {@link FederatedSignIn}'s answer. What this adds is the
 * part every login shares: a second factor is asked for when the account has one, and the session
 * is established exactly as a password login establishes it — including the fixation defence, which
 * is one call rather than a rule to remember.
 */
@Slf4j
@Tag(name = "Federated sign-in", description = "Signing in through an identity provider the operator installed (REQ-AUTH-012).")
@RestController
@RequestMapping("/api/v1/auth/federated")
@RequiredArgsConstructor
public class FederatedAuthController {

  /** Where a browser is sent when a flow ends badly. The web client renders the reason. */
  private static final String SIGN_IN_PATH = "/sign-in";

  private final FederatedSignIn federated;
  private final AuthenticationService authentication;
  private final SecondFactor secondFactor;
  private final SessionEstablisher sessions;
  private final PendingLogin pendingLogin;

  /**
   * What the sign-in page offers besides a password.
   *
   * @return the installed provider, or an empty list
   */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @CanFail({})
  @Operation(
      summary = "The federated providers this instance offers",
      description =
          "Empty when none is installed or the plugin cannot be reached, so that a sign-in "
              + "page renders either way rather than failing with it.")
  @PublicEndpoint(
      reason =
          "It is read by the sign-in page, where nobody is authenticated yet. It says what "
              + "this instance offers and nothing about who has an account here.")
  public List<FederatedSignIn.Provider> providers(
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    // At most one today, and bounded anyway: REQ-NFR-010 is a rule about every
    // collection this API returns rather than about the ones somebody judged
    // large, and a second provider is a configuration change rather than a
    // release.
    return federated.installed().map(List::of).orElseGet(List::<FederatedSignIn.Provider>of).stream()
        .limit(limit)
        .toList();
  }

  /**
   * Starts a sign-in and says where to send the browser.
   *
   * @param request where to return to afterwards, and an address to prefill
   * @return the provider's authorization URL
   */
  @PostMapping(path = "/begin", produces = MediaType.APPLICATION_JSON_VALUE)
  @CanFail({ProblemType.PLUGIN_UNAVAILABLE, ProblemType.VALIDATION_FAILED})
  @Operation(
      summary = "Start a federated sign-in",
      description =
          "The PKCE verifier and the nonce stay on the server, keyed by a single-use handle "
              + "in the `state` parameter (ADR-0029).")
  @PublicEndpoint(
      reason =
          "It is the first half of a login and the caller has no session yet. It creates "
              + "nothing but a ten-minute flow record and reveals nothing about any account.")
  public Started begin(@Valid @RequestBody(required = false) BeginRequest request) {
    BeginRequest asked = request == null ? new BeginRequest(null, null) : request;
    return new Started(
        federated
            .begin(FederatedSignIn.Purpose.SIGN_IN, null, asked.returnTo(), asked.loginHint())
            .authorizationUrl());
  }

  /**
   * Starts a flow that attaches a provider identity to the caller's own account (REQ-AUTH-006).
   *
   * <p>The second factor has to be fresh. Linking a provider identity is what turns a foreign
   * account into a way in here, so it sits with the operations REQ-AUTH-011 asks to be re-confirmed
   * — and a hijacked session must not be able to attach the attacker's provider account quietly.
   *
   * <p><b>An account with no second factor therefore cannot link one</b>, and that is the
   * intended reading rather than a gap: protecting an account with a second factor before
   * adding a second door to it is the order that makes sense, and the alternative would be a
   * second re-confirmation mechanism for exactly the accounts that have the least protection.
   *
   * @param request where to return to afterwards
   * @param user the authenticated principal
   * @return the provider's authorization URL
   */
  @PostMapping(path = "/link", produces = MediaType.APPLICATION_JSON_VALUE)
  @CanFail({
    ProblemType.UNAUTHENTICATED,
    ProblemType.SECOND_FACTOR_STALE,
    ProblemType.PLUGIN_UNAVAILABLE,
    ProblemType.VALIDATION_FAILED
  })
  @Operation(
      summary = "Link a provider identity to the signed-in account",
      description =
          "Requires a fresh second factor. The identity is attached only when the callback "
              + "comes back to the flow this started: a callback cannot change its own purpose.")
  @PublicEndpoint(
      reason =
          "It links an identity to the caller's OWN account and no other. There is no role "
              + "low enough to be denied that, and an account belonging to no tenant links "
              + "like any other. What guards it is the second factor, not a permission.")
  @RequiresRecentSecondFactor
  public Started link(
      @Valid @RequestBody(required = false) BeginRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    BeginRequest asked = request == null ? new BeginRequest(null, null) : request;
    return new Started(
        federated
            .begin(FederatedSignIn.Purpose.LINK, user.userId(), asked.returnTo(), null)
            .authorizationUrl());
  }

  /**
   * Where the provider sends the browser back (REQ-AUTH-005).
   *
   * @param state the single-use handle
   * @param code the authorization code
   * @param httpRequest the servlet request, for the new session and the {@code Accept} header
   * @param httpResponse the servlet response, which the security context is written to
   * @return a redirect, or a problem for a caller that asked for one
   */
  @GetMapping("/callback")
  @CanFail({
    ProblemType.FEDERATED_FLOW_UNKNOWN,
    ProblemType.FEDERATED_IDENTITY_UNLINKED,
    ProblemType.FEDERATED_ADDRESS_TAKEN,
    ProblemType.FEDERATED_ADDRESS_UNVERIFIED,
    ProblemType.FEDERATED_IDENTITY_LINKED_ELSEWHERE,
    ProblemType.SECOND_FACTOR_REQUIRED,
    ProblemType.PLUGIN_UNAVAILABLE
  })
  @Operation(
      summary = "Finish a federated sign-in",
      description =
          "Reached by the browser following the provider's redirect. Answers a redirect, or "
              + "`application/problem+json` to a caller that does not accept HTML.")
  @PublicEndpoint(
      reason =
          "It is the second half of a login: the caller has no session yet, and what it "
              + "presents instead is a single-use handle this consumes on the first attempt "
              + "whether or not the rest succeeds.")
  public ResponseEntity<Void> callback(
      @RequestParam @Size(max = 128) String state,
      @RequestParam @Size(max = 4096) String code,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse) {

    FederatedSignIn.Outcome outcome = federated.complete(state, code);
    if (!outcome.signsIn() && outcome.result() != FederatedSignIn.Result.LINKED) {
      return refusal(outcome, httpRequest);
    }

    if (outcome.result() == FederatedSignIn.Result.LINKED) {
      // The caller already had a session; the link changed nothing about it.
      return redirectTo(outcome.returnTo());
    }

    AuthenticatedUser user = federatedPrincipal(outcome.userId(), httpRequest);
    if (secondFactor.isRequiredFor(user.userId())) {
      // The provider proved who they are at the provider. It did not prove the
      // factor this instance holds, so the login stops here exactly as a password
      // one does and the caller answers at /api/v1/auth/mfa.
      pendingLogin.remember(httpRequest, user);
      throw new SecondFactorRequiredException();
    }
    sessions.establish(user, httpRequest, httpResponse, false);
    return redirectTo(outcome.returnTo());
  }

  /**
   * The provider identities attached to the caller's own account.
   *
   * @param user the authenticated principal
   * @return the links, newest first
   */
  @GetMapping(path = "/links", produces = MediaType.APPLICATION_JSON_VALUE)
  @CanFail({ProblemType.UNAUTHENTICATED})
  @Operation(summary = "The provider identities linked to this account")
  @PublicEndpoint(
      reason =
          "It lists the caller's own links and nobody else's, the same self-service shape "
              + "as the session list.")
  public List<LinkView> myLinks(
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit,
      @AuthenticationPrincipal AuthenticatedUser user) {
    return federated.linksOf(user.userId()).stream()
        .limit(limit)
        .map(
            link ->
                new LinkView(
                    link.id(),
                    link.providerKey(),
                    link.issuer(),
                    link.email(),
                    link.linkedAt().toString(),
                    link.lastUsedAt() == null ? null : link.lastUsedAt().toString()))
        .toList();
  }

  /**
   * Removes one of the caller's own links.
   *
   * <p>Behind a fresh second factor like the linking is: taking a way in away matters as much as
   * adding one, and somebody who removes a link an attacker planted should be the account's owner
   * rather than whoever holds the session.
   *
   * @param id which link
   * @param user the authenticated principal
   */
  @DeleteMapping("/links/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @CanFail({
    ProblemType.UNAUTHENTICATED,
    ProblemType.SECOND_FACTOR_STALE,
    ProblemType.NOT_FOUND
  })
  @Operation(summary = "Unlink a provider identity from this account")
  @PublicEndpoint(
      reason =
          "It removes one of the caller's OWN links. The id is looked up in that account's "
              + "own list, so one naming somebody else's link is not found rather than "
              + "refused. The second factor is what guards it.")
  @RequiresRecentSecondFactor
  public void unlink(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    if (!federated.unlink(user.userId(), id)) {
      throw new NotFoundException("federated identity", id);
    }
  }

  /**
   * Refuses a flow, in the shape the caller can read.
   *
   * @param outcome what went wrong
   * @param httpRequest the request, for its {@code Accept}
   * @return a redirect for a browser, or a problem for anything else
   */
  private ResponseEntity<Void> refusal(
      FederatedSignIn.Outcome outcome, HttpServletRequest httpRequest) {
    ProblemType type =
        switch (outcome.result()) {
          case NOT_LINKED -> ProblemType.FEDERATED_IDENTITY_UNLINKED;
          case ADDRESS_TAKEN -> ProblemType.FEDERATED_ADDRESS_TAKEN;
          case ADDRESS_UNVERIFIED -> ProblemType.FEDERATED_ADDRESS_UNVERIFIED;
          case LINKED_ELSEWHERE -> ProblemType.FEDERATED_IDENTITY_LINKED_ELSEWHERE;
          default -> ProblemType.INTERNAL_ERROR;
        };
    if (!acceptsHtml(httpRequest)) {
      throw new FederatedRefusedException(type);
    }
    // A person is looking at this in a browser. A JSON body would be a dead end,
    // so the sign-in page is told which refusal it was and says it in their
    // language — the token is the same one the problem document carries.
    return ResponseEntity.status(HttpStatus.SEE_OTHER)
        .header(HttpHeaders.LOCATION, SIGN_IN_PATH + "?failed=" + type.token())
        .build();
  }

  /** Whether the caller is a browser following a redirect rather than a client reading JSON. */
  private static boolean acceptsHtml(HttpServletRequest httpRequest) {
    String accept = httpRequest.getHeader(HttpHeaders.ACCEPT);
    return accept != null && accept.contains(MediaType.TEXT_HTML_VALUE);
  }

  /** Sends the browser where it was going, or to the application's root. */
  private static ResponseEntity<Void> redirectTo(String returnTo) {
    return ResponseEntity.status(HttpStatus.SEE_OTHER)
        .location(URI.create(returnTo == null ? "/" : returnTo))
        .build();
  }

  /**
   * The principal a verified identity signs in as.
   *
   * @param userId whose account it turned out to be
   * @param httpRequest the request, for the client address the login is recorded from
   * @return the principal, with the membership a password login would have found
   */
  private AuthenticatedUser federatedPrincipal(UUID userId, HttpServletRequest httpRequest) {
    return authentication.signInFederated(userId, clientAddressOf(httpRequest));
  }

  /** The caller's address, as the trusted-proxy rules resolve it. */
  private static String clientAddressOf(HttpServletRequest httpRequest) {
    return Optional.ofNullable(httpRequest.getRemoteAddr()).orElse("unknown");
  }

  /**
   * What a client asks for when it starts a flow.
   *
   * @param returnTo a path on this instance to return to, or null
   * @param loginHint an address to prefill at the provider, or null
   */
  public record BeginRequest(@Size(max = 512) String returnTo, @Size(max = 320) String loginHint) {}

  /**
   * Where to send the browser.
   *
   * @param authorizationUrl the provider's URL with the parameters on it
   */
  public record Started(String authorizationUrl) {}

  /**
   * One linked identity, as the account page shows it.
   *
   * @param id the link, for unlinking
   * @param providerKey which configuration it was made through
   * @param issuer the provider that said so
   * @param email the address at linking, or null
   * @param linkedAt when it was made, ISO-8601
   * @param lastUsedAt when it last signed somebody in, or null
   */
  public record LinkView(
      UUID id,
      String providerKey,
      String issuer,
      String email,
      String linkedAt,
      String lastUsedAt) {}

  /**
   * A refusal that already knows which problem type it is.
   *
   * <p>Thrown rather than returned so that the one exception handler builds the document, which is
   * what keeps every error on this surface the same shape (REQ-API-003).
   */
  public static class FederatedRefusedException extends RuntimeException {

    private final transient ProblemType type;

    /**
     * Names the refusal.
     *
     * @param type which one
     */
    public FederatedRefusedException(ProblemType type) {
      super(type.title());
      this.type = type;
    }

    /**
     * Which problem this is.
     *
     * @return the type
     */
    public ProblemType type() {
      return type;
    }
  }
}
