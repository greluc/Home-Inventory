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
    // The token is read from the header, not from a request parameter. A parameter
    // can be planted by a form post from another origin; a header cannot without CORS.
    csrfHandler.setCsrfRequestAttributeName(null);

    // Named origin, never reflected, never `*` (REQ-SEC-062). Spring Security
    // applies no CORS rules at all unless it is given a source, and "no rules"
    // is only safe until somebody adds a permissive one to make an integration
    // work; a named one is the thing that has to be edited to get it wrong.
    http.cors(cors -> cors.configurationSource(corsConfigurationSource))
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(csrfHandler)
                    // Login establishes the session the token belongs to;
                    // requiring a token to obtain one is circular. Accepting an
                    // invitation is the same case one step earlier: the caller has
                    // no session and is about to become somebody who can have one.
                    // Its own credential is the invitation token — 256 bits from a
                    // secure source, single-use and time-limited — which is what a
                    // CSRF token would be protecting, and a page that could forge
                    // this request would need that token to begin with.
                    // A request authenticated by a Bearer token carries no cookie,
                    // and CSRF is an attack on a credential the browser attaches by
                    // itself. Nothing a foreign page can do adds an Authorization
                    // header (REQ-AUTH-010).
                    .ignoringRequestMatchers(
                        request -> {
                          String authorization = request.getHeader("Authorization");
                          return authorization != null && authorization.startsWith("Bearer ");
                        })
                    .ignoringRequestMatchers(
                        "/api/v1/auth/login",
                        "/api/v1/invitations/*/accept",
                        // Withdrawing an erasure is the same case again: the
                        // caller has no session because the pending deletion is
                        // what stopped them from having one, and the token they
                        // carry is what a CSRF token would be protecting.
                        "/api/v1/tenant-revocations/*"))
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    // The container's error dispatch. It is not a request a client
                    // made — the request it belongs to was already refused, and
                    // the decision that refused it has already run. Demanding
                    // authentication here would answer an anonymous 404 with a
                    // 401 about a path that does not exist.
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    // The probes. `/livez` and `/readyz` are the additional paths
                    // the health groups carry (13 §13.3); they answer on the
                    // management listener, which is bound to `internal` and is
                    // unreachable from here (REQ-SEC-099).
                    .requestMatchers(
                        "/api/v1/auth/login", "/actuator/health/**", "/livez", "/readyz")
                    .permitAll()
                    // The second half of a login (REQ-AUTH-002). The caller has no
                    // session to be authenticated by yet — that is what this call
                    // establishes — and what it does have is the pending login the
                    // password left in the session, which the controller rejects
                    // without. Exactly this path: everything under
                    // /api/v1/auth/mfa/ administers the caller's own credentials
                    // and needs a session like anything else.
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/auth/mfa", "/api/v1/auth/mfa/passkeys/challenge")
                    .permitAll()
                    // Accepting an invitation is how somebody becomes a person on
                    // this instance (REQ-AUTH-004), so it cannot require being one.
                    // Only the accept: the endpoints that ISSUE and withdraw
                    // invitations sit under /api/v1/tenants and need a permission
                    // like everything else.
                    .requestMatchers(HttpMethod.POST, "/api/v1/invitations/*/accept")
                    .permitAll()
                    // Undoing an erasure request has to work for somebody who
                    // cannot sign in, because the request is what stopped them
                    // (REQ-TEN-011). Only this path: asking for the erasure sits
                    // under /api/v1/tenants and needs the OWNER's permission.
                    .requestMatchers(HttpMethod.POST, "/api/v1/tenant-revocations/*")
                    .permitAll()
                    // The generated OpenAPI document. `springdoc.api-docs.enabled`
                    // is false in every deployment, so this path answers 404
                    // there; permitting it grants access to nothing. It is here
                    // because the build reads the document from a context whose
                    // filter chain is the real one — a rule that only applied in
                    // tests would be a rule the tests do not exercise.
                    .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml")
                    .permitAll()
                    // Authorised by the signature in the URL and by nothing else
                    // (REQ-MED-010). A browser following an <img src> to the
                    // media hostname sends no session cookie, and SameSite=Strict
                    // would withhold it even to our own. The controller verifies
                    // the signature and answers 404 when it does not match.
                    .requestMatchers("/media/**")
                    .permitAll()
                    // Everything else, including anything added later. A default of
                    // permitAll would make a forgotten rule a public endpoint.
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            handling ->
                // A 401 carrying RFC 9457 problem details, not a redirect to a login
                // page and not an empty body. A 302 to HTML is what makes a fetch()
                // fail incomprehensibly; an empty body is the one failure in the
                // surface a client cannot parse the way it parses every other one
                // (REQ-API-003).
                handling.authenticationEntryPoint(problemEntryPoint))
        // After the CSRF filter, which is what puts the deferred token in the
        // request; this is what resolves it so the cookie is written even on a
        // request that never asks (see CsrfCookieFilter).
        .addFilterAfter(
            new CsrfCookieFilter(), org.springframework.security.web.csrf.CsrfFilter.class)
        // A machine presents its token on every request and has no session, so the
        // principal has to exist before anything reads one — and before the tenant
        // context filter, which publishes the tenant the principal names
        // (REQ-AUTH-010).
        .addFilterAfter(
            serviceAccountAuthenticationFilter,
            org.springframework.security.web.context.SecurityContextHolderFilter.class)
        .addFilterAfter(
            tenantContextFilter, ServiceAccountAuthenticationFilter.class)
        // Form login and HTTP Basic are off: the only way in is the JSON endpoint,
        // so there is one code path to rate-limit and one to audit.
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable());

    return http.build();
  }
}
