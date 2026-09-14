/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api.port;

import de.greluc.homeinv.plugin.api.CallContext;
import java.util.Map;

/**
 * Signs somebody in against a foreign directory (09 §9.2).
 *
 * <p>OIDC is a first-party plugin, LDAP and SAML are plugins too. There is no in-core
 * implementation, because every one of them talks to a host outside the deployment (ADR-0026).
 *
 * <h2>What this port must never do</h2>
 *
 * <p><b>It does not link accounts.</b> A verified e-mail address that matches an existing local
 * account is <i>not</i> permission to sign in as that account: that is the account-takeover this
 * project refuses by design (REQ-AUTH-005, REQ-AUTH-006, REQ-SEC-020). This port reports who the
 * far side says the person is; whether that becomes a session, and against which account, is the
 * core's decision and a person's confirmation.
 *
 * <p>It also issues no session and no token of the core's. It returns claims.
 *
 * <p>Stage 1 (REQ-AUTH-007).
 */
public interface IdentityProvider {

  /**
   * What this provider is.
   *
   * @param context who is asking
   * @return its description, which the core shows on the sign-in page
   */
  Descriptor describe(CallContext context);

  /**
   * Starts a sign-in and says where to send the browser.
   *
   * <p>The state and the nonce are the core's and are passed in rather than minted here: the core
   * is what has to recognise them coming back, and a value only the plugin knows is a value the
   * core cannot check.
   *
   * @param context who is signing in — the tenant whose provider configuration applies
   * @param request the redirect the core will be called back on, and the one-time values it will
   *     verify
   * @return where to send the browser
   * @throws de.greluc.homeinv.plugin.api.PluginException when the provider's metadata could not be
   *     read or the configuration is incomplete
   */
  Authorization begin(CallContext context, AuthorizationRequest request);

  /**
   * Finishes a sign-in and reports who the far side says this is.
   *
   * <p>The implementation verifies the token's signature, issuer, audience, expiry and nonce before
   * returning. A provider that hands back unverified claims has turned a redirect into an
   * authentication, and the core has no way to tell the difference.
   *
   * @param context who is signing in
   * @param request the code the browser came back with, and the values to verify it against
   * @return the verified identity
   * @throws de.greluc.homeinv.plugin.api.PluginException when the code is not valid, the token does
   *     not verify, or the provider could not be reached
   */
  Identity complete(CallContext context, CompletionRequest request);

  /**
   * What a provider is.
   *
   * @param providerKey the stable key, for example {@code oidc-main}
   * @param displayName what the sign-in button says
   * @param pkceRequired whether the flow uses PKCE. The core always sends a challenge; this says
   *     whether the provider insists on it, which an operator wants to see
   */
  record Descriptor(String providerKey, String displayName, boolean pkceRequired) {}

  /**
   * The start of a sign-in.
   *
   * @param redirectUri where the provider sends the browser back to. The core's own URL, registered
   *     with the provider — a plugin does not get to choose it
   * @param state the core's one-time value, returned unchanged and checked by the core
   * @param nonce the core's one-time value for the token, checked by the plugin when it verifies
   * @param codeChallenge the PKCE challenge, S256
   * @param loginHint what to prefill, or empty. Passed on as the protocol allows and never invented
   */
  record AuthorizationRequest(
      String redirectUri, String state, String nonce, String codeChallenge, String loginHint) {}

  /**
   * Where to send the browser.
   *
   * @param authorizationUrl the provider's URL with the parameters already on it
   */
  record Authorization(String authorizationUrl) {}

  /**
   * The end of a sign-in.
   *
   * @param code what the provider handed the browser
   * @param codeVerifier the PKCE verifier matching the challenge
   * @param redirectUri the same redirect as before, which the provider checks again
   * @param nonce the same nonce as before, which the plugin checks in the token
   */
  record CompletionRequest(
      String code, String codeVerifier, String redirectUri, String nonce) {}

  /**
   * Who the far side says this is.
   *
   * @param subject the provider's own stable id for the person. <b>This, with the issuer, is what
   *     an account is linked by</b> — never the e-mail address, which people change and providers
   *     reuse
   * @param issuer which provider said so
   * @param email the address, or empty
   * @param emailVerified whether the provider says it verified that address. The core treats an
   *     unverified address as absent, because an unverified address is a claim about somebody else
   * @param displayName the name, or empty
   * @param claims everything else the token carried, for an operator to map onto roles. The core
   *     maps nothing by itself
   */
  record Identity(
      String subject,
      String issuer,
      String email,
      boolean emailVerified,
      String displayName,
      Map<String, String> claims) {}
}
