/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugin.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one thing this plugin exists to get right: a token is believed only when it verifies.
 *
 * <p>Against a <b>real provider</b> — a small HTTP server that publishes a discovery document and a
 * key set, and signs tokens with a key generated for the run. A stubbed verifier would assert the
 * stub, and what is under test is precisely the thing a hand-written check gets subtly wrong.
 *
 * <p>The provider here speaks {@code http} on localhost, which {@link Configuration#missing()}
 * refuses in a deployment and refuses for a good reason ({@code ConfigurationTest} holds that).
 * What is exercised below is the verification, and a self-signed TLS certificate in front of it
 * would add a trust store to the test without adding a property to it.
 */
@DisplayName("An ID token")
class ProviderTest {

  private static final String CLIENT_ID = "home-inventory";
  private static final String SUBJECT = "0000-1111-2222";

  private static HttpServer server;
  private static RSAKey signingKey;
  private static String issuer;

  /** What the token endpoint answers next, so one test can change it. */
  private static final AtomicReference<String> NEXT_ID_TOKEN = new AtomicReference<>();

  @BeforeAll
  static void startAProvider() throws Exception {
    signingKey = new RSAKeyGenerator(2048).keyID("k1").generate();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    issuer = "http://127.0.0.1:" + server.getAddress().getPort();

    server.createContext(
        "/.well-known/openid-configuration",
        exchange ->
            answer(
                exchange,
                "{\"issuer\":\""
                    + issuer
                    + "\",\"authorization_endpoint\":\""
                    + issuer
                    + "/authorize\",\"token_endpoint\":\""
                    + issuer
                    + "/token\",\"jwks_uri\":\""
                    + issuer
                    + "/jwks\"}"));
    server.createContext(
        "/jwks",
        exchange -> answer(exchange, new JWKSet(signingKey.toPublicJWK()).toString()));
    server.createContext(
        "/token",
        exchange ->
            answer(
                exchange,
                "{\"access_token\":\"opaque\",\"token_type\":\"Bearer\",\"id_token\":\""
                    + NEXT_ID_TOKEN.get()
                    + "\"}"));
    server.start();
  }

  @AfterAll
  static void stopIt() {
    server.stop(0);
  }

  @Test
  @DisplayName("is believed when it verifies, and carries the claims the core maps")
  void aGoodToken() throws Exception {
    NEXT_ID_TOKEN.set(signed(claims("the-nonce").build(), signingKey));

    JWTClaimsSet verified =
        provider().verifiedIdentity("code", "verifier", "https://home.example/callback", "the-nonce");

    assertThat(verified.getSubject()).isEqualTo(SUBJECT);
    assertThat(verified.getIssuer()).isEqualTo(issuer);
    assertThat(verified.getClaim("email")).isEqualTo("ada@example.org");
    assertThat(verified.getClaim("email_verified")).isEqualTo(Boolean.TRUE);
  }

  @Test
  @DisplayName("is refused when the nonce is not the one this sign-in minted")
  void aTokenForAnotherSignIn() throws Exception {
    // The replay this check exists for: a token obtained through one flow,
    // presented to finish another. Everything else about it is valid.
    NEXT_ID_TOKEN.set(signed(claims("somebody-elses-nonce").build(), signingKey));

    assertThatThrownBy(
            () ->
                provider()
                    .verifiedIdentity("code", "verifier", "https://home.example/callback", "the-nonce"))
        .isInstanceOf(Provider.VerificationException.class)
        .hasMessageContaining("nonce");
  }

  @Test
  @DisplayName("is refused when somebody else signed it")
  void aTokenSignedByAnotherKey() throws Exception {
    RSAKey attacker = new RSAKeyGenerator(2048).keyID("k1").generate();
    // The same key id as the provider's, so the only thing that can refuse it is
    // the signature itself.
    NEXT_ID_TOKEN.set(signed(claims("the-nonce").build(), attacker));

    assertThatThrownBy(
            () ->
                provider()
                    .verifiedIdentity("code", "verifier", "https://home.example/callback", "the-nonce"))
        .isInstanceOf(Provider.VerificationException.class);
  }

  @Test
  @DisplayName("is refused when it was issued for another client")
  void aTokenForAnotherAudience() throws Exception {
    NEXT_ID_TOKEN.set(
        signed(claims("the-nonce").audience("somebody-elses-client").build(), signingKey));

    assertThatThrownBy(
            () ->
                provider()
                    .verifiedIdentity("code", "verifier", "https://home.example/callback", "the-nonce"))
        .isInstanceOf(Provider.VerificationException.class);
  }

  @Test
  @DisplayName("is refused when it has expired")
  void anExpiredToken() throws Exception {
    NEXT_ID_TOKEN.set(
        signed(
            claims("the-nonce")
                .expirationTime(Date.from(Instant.now().minus(Duration.ofHours(1))))
                .build(),
            signingKey));

    assertThatThrownBy(
            () ->
                provider()
                    .verifiedIdentity("code", "verifier", "https://home.example/callback", "the-nonce"))
        .isInstanceOf(Provider.VerificationException.class);
  }

  @Test
  @DisplayName("names the state, the nonce and the challenge the core minted, and S256")
  void theAuthorizationUrl() throws Exception {
    String url =
        provider()
            .authorizationUrl(
                "https://home.example/callback", "the-state", "the-nonce", "the-challenge", "ada@example.org");

    assertThat(url).startsWith(issuer + "/authorize?response_type=code");
    assertThat(url).contains("state=the-state").contains("nonce=the-nonce");
    assertThat(url).contains("code_challenge=the-challenge").contains("code_challenge_method=S256");
    assertThat(url).contains("client_id=home-inventory");
    // `openid` is always asked for: without it a provider runs a plain OAuth
    // flow and returns no ID token, which is the only thing this plugin trusts.
    assertThat(url).contains("scope=openid");
    assertThat(url).contains("login_hint=ada%40example.org");
  }

  // -------------------------------------------------------------------------

  private static Provider provider() {
    return new Provider(
        new Configuration(
            issuer,
            CLIENT_ID,
            "",
            "oidc",
            "Single sign-on",
            List.of("openid", "email"),
            null,
            Duration.ofSeconds(5)));
  }

  private static JWTClaimsSet.Builder claims(String nonce) {
    return new JWTClaimsSet.Builder()
        .issuer(issuer)
        .subject(SUBJECT)
        .audience(CLIENT_ID)
        .expirationTime(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
        .issueTime(Date.from(Instant.now()))
        .claim("nonce", nonce)
        .claim("email", "ada@example.org")
        .claim("email_verified", true)
        .claim("name", "Ada Lovelace");
  }

  private static String signed(JWTClaimsSet claims, RSAKey key) throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private static void answer(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
