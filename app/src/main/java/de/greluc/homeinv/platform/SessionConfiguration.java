/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * The session cookie (REQ-SEC-017).
 *
 * <p>Every attribute below is required rather than advisable, and each closes a specific hole:
 *
 * <ul>
 *   <li><b>{@code __Host-} prefix</b> — a browser refuses the cookie unless it is {@code Secure},
 *       has {@code Path=/} and carries no {@code Domain}. That last part is what makes it
 *       unwritable by a sibling subdomain: without the prefix, anything under the parent domain can
 *       set a cookie for it and fix a session.
 *   <li><b>{@code HttpOnly}</b> — script cannot read it, so an XSS cannot take the session.
 *   <li><b>{@code SameSite=Strict}</b> — the cookie is not sent on cross-site requests at all,
 *       which is the structural half of CSRF defence; the token is the other half.
 *   <li><b>{@code Secure}</b> — implied by the prefix, stated anyway so it survives a rename.
 * </ul>
 *
 * <p>A consequence worth knowing before it is discovered: {@code Secure} makes the application
 * unusable over plain HTTP, local development included. That is not an oversight — TLS terminates
 * at a reverse proxy in every deployment profile, and camera access in the browser needs HTTPS
 * anyway.
 */
@Configuration(proxyBeanMethods = false)
public class SessionConfiguration {

  /**
   * Serialises the session cookie with the attributes REQ-SEC-017 requires.
   *
   * @return the cookie serialiser Spring Session uses
   */
  @Bean
  public CookieSerializer cookieSerializer() {
    DefaultCookieSerializer serializer = new DefaultCookieSerializer();
    serializer.setCookieName("__Host-homeinv-session");
    serializer.setCookiePath("/");
    serializer.setUseHttpOnlyCookie(true);
    serializer.setUseSecureCookie(true);
    serializer.setSameSite("Strict");
    // No domain, ever: setting one is what the __Host- prefix exists to forbid,
    // and a browser would reject the cookie outright.
    serializer.setDomainNamePattern(null);
    return serializer;
  }
}
