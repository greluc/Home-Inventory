/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Signing in against a provider the operator installed (REQ-AUTH-005).
 *
 * <h2>What this decides and what it refuses</h2>
 *
 * <p>An account is found by <b>{@code (issuer, subject)}</b> and by nothing else. A verified
 * e-mail address that matches an existing account is <b>not</b> permission to sign in as that
 * account (REQ-AUTH-006, REQ-SEC-020): that is the account takeover this project refuses by
 * design, and it is refused with the same sentence whether or not the address is one this instance
 * knows — a different answer for a known address would be an oracle.
 *
 * <p>An instance whose {@link RegistrationMode} is {@link RegistrationMode#OPEN} may <b>create</b>
 * an account for an identity nobody has linked, on a verified address that has none. The other two
 * modes refuse; that is the same setting deciding who may come to exist here as it does for a
 * sign-up form (REQ-AUTH-004).
 *
 * <h2>The flow's state never touches the browser</h2>
 *
 * <p>The PKCE verifier and the nonce are held server-side, keyed by a single-use handle that
 * travels in the {@code state} parameter ([ADR-0029]). The session cookie is {@code
 * SameSite=Strict} and a provider's callback is a cross-site top-level navigation, so anything
 * written into a cookie before the redirect would not come back with it. A replayed callback finds
 * the handle spent and fails, which is what REQ-AUTH-005 is verified by.
 *
 * <h2>This block establishes no session</h2>
 *
 * <p>It says <b>who</b> the caller turned out to be. Whether that becomes a session, and whether a
 * second factor is asked for first, is the same decision a password login goes through and is made
 * where that one is made.
 */
public interface FederatedSignIn {

  /**
   * The provider this instance has installed, if any.
   *
   * <p>Resolved through the <b>operator's</b> grant rather than a tenant's ([ADR-0066]): a sign-in
   * happens before any tenant is known.
   *
   * @return what the sign-in page shows, or empty when nothing is installed or it cannot be reached
   */
  Optional<Provider> installed();

  /**
   * Starts a sign-in or a link and says where to send the browser.
   *
   * @param purpose whether this may create a session or only attach an identity
   * @param userId the account a {@link Purpose#LINK} attaches to, which is the caller's own and
   *     was re-confirmed before this call; null for a sign-in
   * @param returnTo where to send the browser afterwards, a path on this instance beginning with a
   *     single {@code /}, or null
   * @param loginHint an address to prefill, or null. Passed on as the protocol allows
   * @return where to send the browser
   * @throws NoIdentityProviderException when no provider is installed or it cannot be reached
   * @throws IllegalArgumentException when {@code returnTo} is not a local path — an absolute one
   *     would be an open redirect with a sign-in in front of it
   */
  Started begin(Purpose purpose, UUID userId, String returnTo, String loginHint);

  /**
   * Finishes one and says what happened.
   *
   * <p>Consumes the handle whether or not the rest succeeds: one callback per start, so that a
   * replay finds nothing.
   *
   * @param state the handle the provider handed back in {@code state}
   * @param code the authorization code
   * @return what the flow turned out to be
   * @throws NoIdentityProviderException when the provider cannot be reached to finish
   */
  Outcome complete(String state, String code);

  /**
   * What one account has linked, for its own account page.
   *
   * @param userId whose — the caller's own, never somebody else's
   * @return the links, newest first
   */
  java.util.List<Link> linksOf(UUID userId);

  /**
   * Removes one of an account's links.
   *
   * <p>Scoped to the account in the statement rather than checked before it: a removal that
   * trusted a caller's id would take somebody else's link away on a guessed one.
   *
   * @param userId whose
   * @param id which link
   * @return {@code true} when a link was removed, {@code false} when that account has none
   *     with this id — which is what a caller naming another account's link gets
   */
  boolean unlink(UUID userId, UUID id);

  /**
   * What a federated flow is allowed to do.
   *
   * <p>Fixed before the redirect and never read from the callback. A flow that could decide its own
   * purpose on the way back is the difference between linking an account and taking one over.
   */
  enum Purpose {
    /** May sign somebody in, and on an {@code open} instance may create an account. */
    SIGN_IN,
    /** May only attach an identity to the account that started it. */
    LINK
  }

  /**
   * A provider as the sign-in page shows it.
   *
   * @param providerKey the stable key the {@code begin} call names
   * @param displayName what the button says
   */
  record Provider(String providerKey, String displayName) {}

  /**
   * One linked identity, as an account page shows it.
   *
   * @param id the link, for unlinking
   * @param providerKey which configuration it was made through
   * @param issuer the provider that said so
   * @param email the address at linking, or null. Evidence of what it was and never an
   *     identifier: nothing looks an account up by it (REQ-AUTH-006)
   * @param linkedAt when it was made
   * @param lastUsedAt when it last signed somebody in, or null
   */
  record Link(
      UUID id,
      String providerKey,
      String issuer,
      String email,
      java.time.Instant linkedAt,
      java.time.Instant lastUsedAt) {}

  /**
   * Where to send the browser to begin.
   *
   * @param authorizationUrl the provider's URL, with the parameters already on it
   */
  record Started(String authorizationUrl) {}

  /**
   * What a completed flow turned out to be.
   *
   * @param result what happened
   * @param userId who it was, for the three results that name somebody; null otherwise
   * @param returnTo where the browser was going, or null
   */
  record Outcome(Result result, UUID userId, String returnTo) {

    /** Whether this outcome names somebody a session may be established for. */
    public boolean signsIn() {
      return result == Result.SIGNED_IN || result == Result.ACCOUNT_CREATED;
    }
  }

  /** The five endings, each of which the REST layer answers with its own problem type. */
  enum Result {
    /** A linked identity: this is its account. */
    SIGNED_IN,
    /** Nobody was linked, the instance is {@code open}, and the address was free. */
    ACCOUNT_CREATED,
    /** A {@link Purpose#LINK} flow attached the identity to the account that started it. */
    LINKED,
    /** Nobody is linked and this instance does not create accounts. */
    NOT_LINKED,
    /** The instance would have created one and the verified address already has an account. */
    ADDRESS_TAKEN,
    /** The instance would have created one and the provider did not confirm the address. */
    ADDRESS_UNVERIFIED,
    /** A link was asked for and that identity belongs to another account here. */
    LINKED_ELSEWHERE
  }

  /**
   * No provider is installed, or the one that is cannot be reached.
   *
   * <p>One exception for both, deliberately: the caller's way out is the same, and telling a
   * stranger at a login page which of the two it is says something about the deployment.
   */
  class NoIdentityProviderException extends RuntimeException {

    /**
     * States what is missing.
     *
     * @param message what to tell the caller
     */
    public NoIdentityProviderException(String message) {
      super(message);
    }
  }

  /**
   * The handle is unknown, expired or already spent.
   *
   * <p>One exception for all three, for the reason the {@code federated-flow-unknown} problem type
   * gives: distinguishing "never seen" from "already used" would let somebody probe for sign-ins in
   * flight.
   */
  class UnknownFlowException extends RuntimeException {

    /** States that the flow is gone. */
    public UnknownFlowException() {
      super("This sign-in is unknown, expired or already finished.");
    }
  }
}
