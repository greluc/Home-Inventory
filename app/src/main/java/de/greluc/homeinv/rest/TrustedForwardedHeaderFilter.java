/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.platform.TrustedProxies;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Applies {@code X-Forwarded-*} only when the peer is a trusted hop (REQ-SEC-063).
 *
 * <p>Spring's own {@link ForwardedHeaderFilter} applies the headers whoever sends them. Behind a
 * proxy that is exactly right and in front of one it is a client telling the application what its
 * own address is — which per-IP rate limiting (REQ-SEC-064) and the audit trail (REQ-SEC-068) both
 * believe. The peer address is the one thing in a request a client cannot forge, so it is what
 * decides whether the rest is believed.
 *
 * <p>When an untrusted peer does send forwarded headers, that is logged <b>once</b>. It is the only
 * visible symptom of the "too narrow" misconfiguration 06 §6.7 describes: with {@code web} missing
 * from the list every request looks like it came from {@code web}, which produces no error at all —
 * only wrong numbers. Once, because the alternative is a line per request for as long as the
 * deployment is wrong.
 */
@Slf4j
public final class TrustedForwardedHeaderFilter extends ForwardedHeaderFilter {

  private final TrustedProxies trusted;
  private final AtomicBoolean warned = new AtomicBoolean();

  /**
   * @param trusted the configured hops
   */
  public TrustedForwardedHeaderFilter(TrustedProxies trusted) {
    this.trusted = trusted;
    // The headers are consumed, not passed on: everything downstream sees the
    // request as the client sent it, and nothing can read them a second time and
    // reach a different conclusion.
    setRemoveOnly(false);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String peer = request.getRemoteAddr();
    if (trusted.trusts(peer)) {
      return false;
    }
    if (request.getHeader("X-Forwarded-For") != null && warned.compareAndSet(false, true)) {
      log.warn(
          "A request from {} carried X-Forwarded-For and that address is not in "
              + "HOMEINV_TRUSTED_PROXIES, so the header was ignored. If {} is the ingress, "
              + "add it: otherwise every client appears to be the ingress, per-IP rate limiting "
              + "throttles all tenants as one, and every audit entry records the same source "
              + "(REQ-SEC-103, 06 §6.7). This is logged once.",
          peer,
          peer);
    }
    return true;
  }
}
