/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * How requests are authenticated and what a session looks like.
 *
 * <p>No authorization rules live here. The filter chain decides whether a caller is <em>known</em>;
 * whether they may do a particular thing is decided in the application layer, every time, because
 * an access adapter that decided would be a second place to keep the rules right (REQ-SEC-022).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PasswordHashingProperties.class)
public class SecurityConfiguration {

  /**
   * The password encoder, Argon2id.
   *
   * <p>{@code REQ-SEC-010} sets the floor — at least 19 MiB of memory, two iterations, one lane —
   * and says it must be raisable by configuration, which is why the values come from
   * {@link PasswordHashingProperties} rather than being constants here. The properties class
   * refuses anything below the floor at startup, so a deployment cannot weaken it by editing a
   * value that looks like a performance knob.
   *
   * <p>The encoder writes PHC strings, so each hash carries the parameters it was made with. That
   * is what lets a login notice a hash made at a lower cost and rehash it — the only path by which
   * a raised cost ever reaches an existing account.
   *
   * @param properties the configured cost, validated against the requirement's floor
   * @return the encoder used for every password in the system
   */
  @Bean
  public PasswordEncoder passwordEncoder(PasswordHashingProperties properties) {
    return new Argon2PasswordEncoder(
        properties.saltLength(),
        properties.hashLength(),
        properties.parallelism(),
        properties.memoryKib(),
        properties.iterations());
  }

  /**
   * The filter chain.
   *
   * <p>Session-based rather than token-based, because the client is a browser: a bearer token in a
   * browser has to be stored somewhere JavaScript can read, and anything JavaScript can read, an
   * XSS can take. A {@code __Host-} cookie cannot be read by script at all.
   *
   * @param http the builder
   * @param tenantContextFilter the filter that publishes the session's tenant to
   *     {@link TenantContext}
   * @return the configured chain
   * @throws Exception when the builder rejects the configuration, which fails startup rather than
   *     leaving the application running with a security configuration that did not apply
   */
  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http, TenantContextFilter tenantContextFilter)
      throws Exception {
    CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
    // The token is read from the header, not from a request parameter. A parameter
    // can be planted by a form post from another origin; a header cannot without CORS.
    csrfHandler.setCsrfRequestAttributeName(null);

    http.csrf(
            csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(csrfHandler)
                    // The login endpoint establishes the session that the token belongs to;
                    // requiring a token to obtain one is circular.
                    .ignoringRequestMatchers("/api/v1/auth/login"))
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers("/api/v1/auth/login", "/actuator/health/**")
                    .permitAll()
                    // Everything else, including anything added later. A default of
                    // permitAll would make a forgotten rule a public endpoint.
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            handling ->
                // 401 with an empty body, not a redirect to a login page: this is an
                // API, and a 302 to HTML is what makes a fetch() fail incomprehensibly.
                handling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .addFilterAfter(
            tenantContextFilter,
            org.springframework.security.web.context.SecurityContextHolderFilter.class)
        // Form login and HTTP Basic are off: the only way in is the JSON endpoint,
        // so there is one code path to rate-limit and one to audit.
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable());

    return http.build();
  }
}
