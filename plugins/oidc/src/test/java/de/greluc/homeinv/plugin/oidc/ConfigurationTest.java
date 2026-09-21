/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugin.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What an operator has to set, and what this plugin refuses to guess.
 *
 * <p>The list is not cosmetic: it is what the health check answers with, because REQ-PLG-015 asks
 * an outbound plugin to refuse service on a partial configuration and "it is broken" is the
 * diagnosis an operator can do least with.
 */
@DisplayName("The configuration")
class ConfigurationTest {

  @Test
  @DisplayName("names every missing piece at once, rather than the first one")
  void everythingMissing() {
    List<String> missing = configuration(null, null, null).missing();

    assertThat(missing).hasSize(3);
    assertThat(missing).anyMatch(line -> line.contains("HOMEINV_OIDC_ISSUER"));
    assertThat(missing).anyMatch(line -> line.contains("HOMEINV_OIDC_CLIENT_ID"));
    assertThat(missing).anyMatch(line -> line.contains("HOMEINV_EGRESS_PROXY"));
  }

  @Test
  @DisplayName("refuses an issuer that is not https, because everything else follows it")
  void plaintextIssuer() {
    // Discovery, the token endpoint and the key set are all reached from this
    // value. An `http://` issuer would send a client secret and an
    // authorization code over a connection nothing protects.
    assertThat(configuration("http://provider.example", "client", "egress-proxy:8118").missing())
        .anyMatch(line -> line.contains("not an https:// URL"));
    assertThat(configuration("https://provider.example", "client", "egress-proxy:8118").missing())
        .isEmpty();
  }

  @Test
  @DisplayName("asks for `openid` whether or not the operator remembered it")
  void theScopeThatMakesItOidc() {
    // Without it a provider runs a plain OAuth flow and returns no ID token,
    // which is the only thing this plugin trusts. Added rather than refused: an
    // operator who left it out meant to sign somebody in.
    Configuration without =
        new Configuration(
            "https://provider.example",
            "client",
            "",
            "oidc",
            "Single sign-on",
            List.of("email", "profile"),
            "egress-proxy:8118",
            Duration.ofSeconds(20));
    assertThat(without.scopeParameter()).isEqualTo("openid email profile");
  }

  @Test
  @DisplayName("is a public client when no secret was mounted")
  void aPublicClient() {
    // A supported configuration rather than an oversight: PKCE is what binds the
    // authorization code to this deployment, and a provider that issues no
    // secret is one an operator may still use.
    assertThat(configuration("https://provider.example", "client", "egress-proxy:8118")
            .isConfidential())
        .isFalse();
    assertThat(
            new Configuration(
                    "https://provider.example",
                    "client",
                    "a-secret",
                    "oidc",
                    "Single sign-on",
                    List.of("openid"),
                    "egress-proxy:8118",
                    Duration.ofSeconds(20))
                .isConfidential())
        .isTrue();
  }

  private static Configuration configuration(String issuer, String clientId, String proxy) {
    return new Configuration(
        issuer,
        clientId,
        "",
        "oidc",
        "Single sign-on",
        List.of("openid", "email"),
        proxy,
        Duration.ofSeconds(20));
  }
}
