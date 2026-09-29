/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * How requests are authenticated and what a session looks like.
 *
 * <p>No authorization rules live here. The filter chain decides whether a caller is <em>known</em>;
 * whether they may do a particular thing is decided in the application layer, every time, because
 * an access adapter that decided would be a second place to keep the rules right (REQ-SEC-022).
 *
 * <p>In the access layer rather than in {@code platform}, for the same reason as
 * {@link TenantContextFilter}: it wires that filter, and the shared kernel must depend on no block.
 * The password encoder stayed behind in {@code platform} because it depends on nothing.
 */
@Configuration(proxyBeanMethods = false)
public class WebSecurityConfiguration {

  /**
   * The filter chain.
   *
   * <p>Session-based rather than token-based, because the client is a browser: a bearer token in a
   * browser has to be stored somewhere JavaScript can read, and anything JavaScript can read, an
   * XSS can take. A {@code __Host-} cookie cannot be read by script at all.
   *
   * @param http the builder
   * @param tenantContextFilter the filter that publishes the session's tenant to
   *     {@code TenantContext}
   * @param corsConfigurationSource the one named origin cross-origin requests may come from
   * @param problemEntryPoint what an unauthenticated request is answered with
   * @return the configured chain
   * @throws Exception when the builder rejects the configuration, which fails startup rather than
   *     leaving the application running with a security configuration that did not apply
   */
  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http,
      TenantContextFilter tenantContextFilter,
      ServiceAccountAuthenticationFilter serviceAccountAuthenticationFilter,
      CorsConfigurationSource corsConfigurationSource,
      ProblemEntryPoint problemEntryPoint)
      throws Exception {
    CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
    csrfHandler.setCsrfRequestAttributeName(null);

    http.cors(cors -> cors.configurationSource(corsConfigurationSource))
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(csrfHandler)
                    .ignoringRequestMatchers(
                        request -> {
                          String authorization = request.getHeader("Authorization");
                          return authorization != null && authorization.startsWith("Bearer ");
                        })
                    .ignoringRequestMatchers(
                        "/api/v1/auth/login",
                        "/api/v1/auth/federated/begin",
                        "/api/v1/invitations/*/accept",
                        "/api/v1/tenant-revocations/*"))
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(
                        "/api/v1/auth/login", "/actuator/health/**", "/livez", "/readyz")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/auth/federated")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/federated/begin")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/auth/federated/callback")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/auth/mfa", "/api/v1/auth/mfa/passkeys/challenge")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/invitations/*/accept")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/tenant-revocations/*")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/auth/password-reset",
                        "/api/v1/auth/password-reset/complete")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/version", "/api/v1/version/notices")
                    .permitAll()
                    .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml")
                    .permitAll()
                    .requestMatchers("/media/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            handling ->
                handling.authenticationEntryPoint(problemEntryPoint))
        .addFilterAfter(
            new CsrfCookieFilter(), org.springframework.security.web.csrf.CsrfFilter.class)
        .addFilterAfter(
            serviceAccountAuthenticationFilter,
            org.springframework.security.web.context.SecurityContextHolderFilter.class)
        .addFilterAfter(
            tenantContextFilter, ServiceAccountAuthenticationFilter.class)
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable());

    return http.build();
  }
}
