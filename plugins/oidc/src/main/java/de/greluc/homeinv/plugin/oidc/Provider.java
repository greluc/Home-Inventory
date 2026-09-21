/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugin.oidc;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The provider itself: discovery, the authorization URL, the token exchange and the one check that
 * matters.
 *
 * <h2>What is verified before anything is returned</h2>
 *
 * <p>The signature against the provider's published key set, the issuer, the audience, the expiry
 * and the nonce the core minted. A provider that handed back unverified claims would have turned a
 * redirect into an authentication, and the core has no way to tell the difference — which is why
 * the contract says the implementation verifies, and why this plugin is the one written in Java
 * (ADR-0072): that list is what an audited library gets right and a hand-written check gets
 * subtly wrong.
 *
 * <h2>Everything outbound goes through the tunnel</h2>
 *
 * <p>Discovery, the key set and the token endpoint are all reached through the egress proxy, which
 * applies this plugin's own allowlist (ADR-0027). Nimbus fetches the key set itself, so it is
 * given a retriever that knows the proxy rather than left to find its own way out — there is no
 * other way out of a plugin segment.
 */
public final class Provider {

  private static final Logger log = LoggerFactory.getLogger(Provider.class);

  /** How large a discovery or key-set document may be. Both are a few kilobytes. */
  private static final int MAX_DOCUMENT = 128 * 1024;

  private final Configuration configuration;
  private final HttpClient http;
  private final AtomicReference<Metadata> metadata = new AtomicReference<>();
  private final AtomicReference<JWKSource<SecurityContext>> keys = new AtomicReference<>();

  /**
   * Builds one against a configuration.
   *
   * @param configuration what the operator set
   */
  public Provider(Configuration configuration) {
    this.configuration = configuration;
    HttpClient.Builder builder =
        HttpClient.newBuilder()
            .connectTimeout(configuration.timeout())
            // NEVER. A redirect is a target the far side chose, and following one
            // would send a client secret or an authorization code somewhere the
            // operator's allowlist never approved.
            .followRedirects(HttpClient.Redirect.NEVER);
    if (configuration.proxy() != null && !configuration.proxy().isBlank()) {
      builder.proxy(java.net.ProxySelector.of(address(configuration.proxy())));
    }
    this.http = builder.build();
  }

  /**
   * Where to send the browser to begin (RFC 6749 §4.1.1, OIDC Core §3.1.2.1).
   *
   * @param redirectUri where the provider sends it back, which the core owns
   * @param state the core's one-time value, returned unchanged
   * @param nonce the core's one-time value for the token
   * @param codeChallenge the PKCE challenge, S256
   * @param loginHint an address to prefill, or empty
   * @return the URL
   * @throws IOException when the provider's metadata cannot be read
   */
  public String authorizationUrl(
      String redirectUri, String state, String nonce, String codeChallenge, String loginHint)
      throws IOException {
    Metadata found = metadata();
    StringBuilder url = new StringBuilder(found.authorizationEndpoint());
    url.append(found.authorizationEndpoint().contains("?") ? '&' : '?');
    url.append("response_type=code");
    append(url, "client_id", configuration.clientId());
    append(url, "redirect_uri", redirectUri);
    append(url, "scope", configuration.scopeParameter());
    append(url, "state", state);
    append(url, "nonce", nonce);
    append(url, "code_challenge", codeChallenge);
    append(url, "code_challenge_method", "S256");
    if (loginHint != null && !loginHint.isBlank()) {
      append(url, "login_hint", loginHint);
    }
    return url.toString();
  }

  /**
   * Exchanges the code and returns the claims of a <b>verified</b> ID token.
   *
   * @param code what the provider handed the browser
   * @param codeVerifier the PKCE verifier matching the challenge
   * @param redirectUri the same redirect as before, which the provider checks again
   * @param nonce the same nonce as before, which is checked inside the token
   * @return the verified claims
   * @throws IOException when the provider cannot be reached or answers something else
   * @throws VerificationException when the token does not verify, which is never a transport
   *     problem and must not be reported as one
   */
  public JWTClaimsSet verifiedIdentity(
      String code, String codeVerifier, String redirectUri, String nonce)
      throws IOException, VerificationException {

    Metadata found = metadata();
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "authorization_code");
    form.put("code", code);
    form.put("redirect_uri", redirectUri);
    form.put("code_verifier", codeVerifier);
    form.put("client_id", configuration.clientId());

    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(found.tokenEndpoint()))
            .timeout(configuration.timeout())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(encode(form), StandardCharsets.UTF_8));
    if (configuration.isConfidential()) {
      // `client_secret_basic`, which every provider supports and which keeps the
      // secret out of a body that ends up in somebody's access log.
      String credentials =
          configuration.clientId() + ":" + configuration.clientSecret();
      request.header(
          "Authorization",
          "Basic "
              + Base64.getEncoder()
                  .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
    }

    HttpResponse<String> response = send(request.build());
    if (response.statusCode() != 200) {
      // The provider's own error, passed through without the body: a token
      // endpoint's error document carries the code that was presented.
      throw new IOException(
          "the token endpoint answered " + response.statusCode() + " to the exchange");
    }

    Map<String, Object> answer = parse(response.body());
    Object idToken = answer.get("id_token");
    if (!(idToken instanceof String token) || token.isBlank()) {
      throw new VerificationException(
          "the provider returned no ID token, so there is nothing this plugin may believe");
    }

    JWTClaimsSet claims = verify(token, found);
    String returned = claims.getClaims().get("nonce") instanceof String value ? value : null;
    if (!nonce.equals(returned)) {
      // The one check a library cannot make for us, because the expected value
      // is the core's. Without it an ID token obtained for another sign-in
      // completes this one.
      throw new VerificationException("the ID token's nonce is not the one this sign-in minted");
    }
    return claims;
  }

  /**
   * Verifies a token's signature, issuer, audience and expiry.
   *
   * @param token the compact JWT
   * @param found the provider's metadata, for its key set
   * @return the claims, once they are established
   * @throws VerificationException when any of it does not hold
   */
  private JWTClaimsSet verify(String token, Metadata found) throws VerificationException {
    try {
      DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
      processor.setJWSKeySelector(
          new JWSVerificationKeySelector<>(
              // The two a self-hosted provider ships with. `none` and the HMAC
              // family are absent deliberately: an ID token signed with a shared
              // secret is one anybody holding that secret can mint.
              Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256), keySource(found)));
      processor.setJWTClaimsSetVerifier(
          new DefaultJWTClaimsVerifier<>(
              // The ACCEPTED AUDIENCE first and the exact-match claims second, which is
              // the order that cost a test run: the two were the other way round, and
              // every valid token was refused for the one reason a verifier should never
              // give -- `aud` rejected on a token whose audience was right.
              configuration.clientId(),
              new JWTClaimsSet.Builder().issuer(found.issuer()).build(),
              // Present or the token is refused. `sub` is the identity itself,
              // and the other three are what make it a token rather than a
              // sentence somebody wrote down.
              Set.of("sub", "iss", "aud", "exp")));
      return processor.process(token, null);
    } catch (ParseException
        | com.nimbusds.jose.proc.BadJOSEException
        | com.nimbusds.jose.JOSEException
        | java.net.MalformedURLException refused) {
      throw new VerificationException("the ID token did not verify: " + refused.getMessage());
    }
  }

  /**
   * The provider's key set, fetched through the tunnel and cached with its own rotation
   * rules.
   *
   * @param found the metadata that names it
   * @return the source Nimbus verifies against
   * @throws java.net.MalformedURLException when the discovery document named something
   *     that is not a URL, which is a provider problem rather than a token problem
   */
  private JWKSource<SecurityContext> keySource(Metadata found) throws java.net.MalformedURLException {
    JWKSource<SecurityContext> existing = keys.get();
    if (existing != null) {
      return existing;
    }
    DefaultResourceRetriever retriever =
        new DefaultResourceRetriever(
            (int) configuration.timeout().toMillis(),
            (int) configuration.timeout().toMillis(),
            MAX_DOCUMENT);
    if (configuration.proxy() != null && !configuration.proxy().isBlank()) {
      retriever.setProxy(new Proxy(Proxy.Type.HTTP, address(configuration.proxy())));
    }
    JWKSource<SecurityContext> source =
        JWKSourceBuilder.create(uri(found.jwksUri()).toURL(), retriever)
            // A key set rotates, and a plugin that cached one for ever would
            // refuse every token signed with the new one. Nimbus refreshes on an
            // unknown key id and rate-limits that, which is the behaviour this
            // needs and the reason it is not a map in a field.
            .retrying(true)
            .build();
    keys.set(source);
    return source;
  }

  /**
   * The provider's metadata, read once and kept.
   *
   * @return the three endpoints this plugin uses
   * @throws IOException when discovery cannot be read
   */
  private Metadata metadata() throws IOException {
    Metadata known = metadata.get();
    if (known != null) {
      return known;
    }
    String document = configuration.issuer().replaceAll("/+$", "") + "/.well-known/openid-configuration";
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(document))
                .timeout(configuration.timeout())
                .header("Accept", "application/json")
                .GET()
                .build());
    if (response.statusCode() != 200) {
      throw new IOException(
          "discovery at " + document + " answered " + response.statusCode());
    }
    Map<String, Object> parsed = parse(response.body());
    Metadata found =
        new Metadata(
            string(parsed, "issuer"),
            string(parsed, "authorization_endpoint"),
            string(parsed, "token_endpoint"),
            string(parsed, "jwks_uri"));
    if (!configuration.issuer().replaceAll("/+$", "").equals(found.issuer().replaceAll("/+$", ""))) {
      // OIDC Discovery §4.3 asks for exactly this comparison. A document that
      // names another issuer is a document that was not written for this issuer,
      // and following its endpoints would be following somebody else's.
      throw new IOException(
          "the discovery document at " + document + " names issuer " + found.issuer());
    }
    log.info("Discovered {} with authorization endpoint {}", found.issuer(), found.authorizationEndpoint());
    metadata.set(found);
    return found;
  }

  private HttpResponse<String> send(HttpRequest request) throws IOException {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("the call to the provider was interrupted", interrupted);
    }
  }

  private static Map<String, Object> parse(String body) throws IOException {
    try {
      return JSONObjectUtils.parse(body);
    } catch (ParseException notJson) {
      throw new IOException("the provider answered something that is not JSON", notJson);
    }
  }

  private static String string(Map<String, Object> document, String key) throws IOException {
    if (document.get(key) instanceof String value && !value.isBlank()) {
      return value;
    }
    throw new IOException("the discovery document has no " + key);
  }

  private static URI uri(String value) {
    return URI.create(value);
  }

  private static InetSocketAddress address(String hostAndPort) {
    int colon = hostAndPort.lastIndexOf(':');
    if (colon < 0) {
      return new InetSocketAddress(hostAndPort, 8118);
    }
    return new InetSocketAddress(
        hostAndPort.substring(0, colon), Integer.parseInt(hostAndPort.substring(colon + 1)));
  }

  private static void append(StringBuilder url, String name, String value) {
    url.append('&')
        .append(URLEncoder.encode(name, StandardCharsets.UTF_8))
        .append('=')
        .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
  }

  private static String encode(Map<String, String> form) {
    StringBuilder body = new StringBuilder();
    for (Map.Entry<String, String> entry : form.entrySet()) {
      if (body.length() > 0) {
        body.append('&');
      }
      body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
          .append('=')
          .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
    }
    return body.toString();
  }

  /**
   * The three endpoints this plugin uses, and the issuer that has to match.
   *
   * @param issuer what the document says it is
   * @param authorizationEndpoint where the browser goes
   * @param tokenEndpoint where the code is exchanged
   * @param jwksUri where the signing keys are published
   */
  public record Metadata(
      String issuer, String authorizationEndpoint, String tokenEndpoint, String jwksUri) {}

  /**
   * The token did not verify.
   *
   * <p>Its own type, and never reported as a transport failure: a provider that is unreachable is
   * something to retry, and a token that does not verify is something to refuse.
   */
  public static class VerificationException extends Exception {

    /** Never serialised; the field is here because the compiler asks for it. */
    private static final long serialVersionUID = 1L;

    /**
     * States what did not hold.
     *
     * @param message what to say, which never contains the token
     */
    public VerificationException(String message) {
      super(message);
    }
  }
}
