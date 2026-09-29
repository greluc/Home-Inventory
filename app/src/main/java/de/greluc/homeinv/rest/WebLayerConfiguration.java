/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.platform.TrustedProxies;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The servlet-level pieces that sit in front of everything else.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
@RequiredArgsConstructor
public class WebLayerConfiguration implements WebMvcConfigurer {

  private final AuditTrailInterceptor auditTrailInterceptor;
  private final PermissionInterceptor permissionInterceptor;
  private final ApiCallQuotaInterceptor apiCallQuotaInterceptor;
  private final TenantAccessInterceptor tenantAccessInterceptor;
  private final SecondFactorLockInterceptor secondFactorLockInterceptor;
  private final SecondFactorFreshnessInterceptor secondFactorFreshnessInterceptor;
  private final ApiUsageInterceptor apiUsageInterceptor;
  private final RateLimitInterceptor rateLimitInterceptor;

  /**
   * Puts the permission check in front of every handler.
   *
   * <p>No path exclusions on the permission check. An endpoint that needs none says so with
   * {@code @PublicEndpoint} and a written reason; a list of excluded paths here would be a second
   * place to say it, and the second place is the one that drifts (REQ-SEC-023). The quota
   * interceptor does have exemptions, and they are in the class that applies them.
   *
   * @param registry the registry Spring MVC offers
   */
  @Override
  public void addInterceptors(@NonNull InterceptorRegistry registry) {
    registry.addInterceptor(auditTrailInterceptor);
    registry.addInterceptor(apiUsageInterceptor);
    registry.addInterceptor(rateLimitInterceptor);
    registry.addInterceptor(permissionInterceptor);
    registry.addInterceptor(tenantAccessInterceptor);
    registry.addInterceptor(secondFactorLockInterceptor);
    registry.addInterceptor(secondFactorFreshnessInterceptor);
    registry.addInterceptor(apiCallQuotaInterceptor);
  }

  /**
   * Registers the forwarded-header filter ahead of every other filter.
   *
   * <p>Order matters and is not a preference: the security chain, the rate limiter and the audit
   * log all read the client's address, so the address has to be corrected before any of them runs.
   * A filter that fixed it afterwards would leave each of them with a different answer.
   *
   * @param trusted the hops whose headers are believed
   * @return the registration, at the highest precedence
   */
  @Bean
  public FilterRegistrationBean<TrustedForwardedHeaderFilter> forwardedHeaderFilter(
      TrustedProxies trusted) {
    FilterRegistrationBean<TrustedForwardedHeaderFilter> registration =
        new FilterRegistrationBean<>(new TrustedForwardedHeaderFilter(trusted));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }

  /**
   * Records when each request started, so a page can say how long it took (08 §8.2).
   *
   * <p>Second only to the forwarded-header filter, and for a plain reason: {@code meta.took} is
   * meant to cover everything the server did, so the measurement has to begin before anything else
   * runs. It writes one request attribute and nothing else; {@code PageTiming} reads it on the way
   * out.
   *
   * @return the registration, just after the address correction
   */
  @Bean
  public FilterRegistrationBean<PageTiming.Clock> requestClock() {
    FilterRegistrationBean<PageTiming.Clock> registration =
        new FilterRegistrationBean<>(new PageTiming.Clock());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
    return registration;
  }

  /**
   * Cross-origin rules: one origin, named, never reflected (REQ-SEC-062).
   *
   * <p>In the shipped topology this grants nothing, and that is the intended state: {@code web}
   * proxies {@code /api} on the same origin as the application shell, so no browser request to this
   * API is cross-origin at all. The configuration exists so that the answer to "what happens if one
   * is" is a named origin rather than whatever Spring's default turns out to be — and so that the
   * value can never become {@code *}, which is what somebody reaches for when an integration does
   * not work.
   *
   * <p>{@code allowCredentials} is on and the origin list is a literal: a reflecting configuration
   * with credentials allowed would let any site read a logged-in user's data, and the two settings
   * are only dangerous together.
   *
   * @param publicBaseUrl the application's own origin, from {@code HOMEINV_PUBLIC_BASE_URL}
   * @return the source the security chain consults
   */
  @Bean
  public CorsConfigurationSource corsConfigurationSource(
      @Value("${homeinv.public-base-url}") String publicBaseUrl) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(List.of(publicBaseUrl));
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
    configuration.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "If-Match"));
    configuration.setExposedHeaders(
        List.of("ETag", "Link", "Retry-After", "RateLimit-Policy", "RateLimit", "Deprecation", "Sunset"));
    configuration.setAllowCredentials(true);
    configuration.setMaxAge(600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", configuration);
    return source;
  }
}
