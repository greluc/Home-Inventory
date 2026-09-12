/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where media is served from, and whether that host can work (REQ-MED-010, REQ-SEC-061).
 *
 * <h2>Why a dedicated hostname at all</h2>
 *
 * <p>Media is arbitrary bytes a tenant uploaded. Serving it from the application's own origin means
 * that anything a browser can be persuaded to execute from a response runs with the application's
 * origin — its cookies, its storage, its CSP. A separate host makes the answer to "what if a
 * response is interpreted as something else" be "nothing of ours".
 *
 * <h2>Why the relationship between the two hosts is checked</h2>
 *
 * <p>Media responses carry {@code Cross-Origin-Resource-Policy: same-site}, which stops a foreign
 * site embedding a tenant's photos. From a <em>different registrable domain</em> that same header
 * blocks our own pages, and every image silently fails to load — a failure that looks like a broken
 * upload and is a header doing exactly what it says (06 §6.11, 12 §12.9).
 *
 * <p>So a media host that is not same-site with the application host is a <b>warning and not a
 * refusal</b>: an operator may deliberately run one, and would then have to send
 * {@code cross-origin} instead. Startup says so loudly rather than deciding for them. A media host
 * that <em>equals</em> the application host is a different matter and is refused — that is not a
 * dedicated hostname at all, and the isolation REQ-MED-010 asks for would not exist.
 */
@Slf4j
@Component
public final class MediaHostCheck {

  /** Where signed media URLs point. */
  @Getter private final String mediaBaseUrl;

  /**
   * Parses both hosts and compares them.
   *
   * @param publicBaseUrl the application's own base URL
   * @param mediaBaseUrl the media host's base URL
   * @throws IllegalStateException when either cannot be parsed, or when the two are the same host
   */
  public MediaHostCheck(
      @Value("${homeinv.public-base-url}") String publicBaseUrl,
      @Value("${homeinv.media-base-url}") String mediaBaseUrl) {

    this.mediaBaseUrl = trimTrailingSlash(mediaBaseUrl);

    String applicationHost = hostOf(publicBaseUrl, "HOMEINV_PUBLIC_BASE_URL");
    String mediaHost = hostOf(mediaBaseUrl, "HOMEINV_MEDIA_BASE_URL");

    if (applicationHost.equals(mediaHost)) {
      throw new IllegalStateException(
          ("HOMEINV_MEDIA_BASE_URL and HOMEINV_PUBLIC_BASE_URL name the same host (%s). Media is "
                  + "arbitrary bytes a tenant uploaded; serving it from the application's own "
                  + "origin gives it the application's cookies, storage and CSP (REQ-MED-010).")
              .formatted(applicationHost));
    }

    if (!sameSite(applicationHost, mediaHost)) {
      log.warn(
          "The media host {} is not same-site with the application host {}. Media responses send "
              + "Cross-Origin-Resource-Policy: same-site, which from a different registrable "
              + "domain blocks our own pages: every image would silently fail to load. Send "
              + "cross-origin instead, or move media to a subdomain (06 §6.11, REQ-SEC-061).",
          mediaHost,
          applicationHost);
    } else {
      log.info("Media is served from {}, same-site with {}.", mediaHost, applicationHost);
    }
  }

  /**
   * Whether two hosts share a registrable domain, to the extent this can be decided without a
   * public-suffix list.
   *
   * <p>Both arguments are already normalised by {@link #hostOf}. The comparison is "one is a
   * subdomain of the other, or both share their last two labels".
   * That is an approximation: it treats {@code a.co.uk} and {@code b.co.uk} as same-site when they
   * are not. The consequence of being wrong here is a <em>missing warning</em>, never a wrong
   * decision — nothing is enforced on the strength of it — which is why a public-suffix list and
   * the update problem it brings are not worth carrying for it.
   *
   * @param first one host
   * @param second the other
   * @return true when they are plausibly same-site
   */
  private static boolean sameSite(String first, String second) {
    if (first.endsWith("." + second) || second.endsWith("." + first)) {
      return true;
    }
    return registrable(first).equals(registrable(second));
  }

  private static String registrable(String host) {
    String[] labels = host.split("\\.");
    if (labels.length < 2) {
      return host;
    }
    return labels[labels.length - 2] + "." + labels[labels.length - 1];
  }

  /**
   * The host a URL names, normalised the way a hostname is normalised.
   *
   * <p>{@link IDN#toASCII} first, then lowercase. That order matters and is not
   * ceremony: a hostname is ASCII on the wire, an internationalised one is punycode, and
   * lowercasing before the conversion would fold characters the conversion has its own rules for.
   * After it, everything is ASCII and the case change is exact.
   *
   * @param url the configured URL
   * @param variable the variable it came from, for the message
   * @return the normalised host
   * @throws IllegalStateException when the URL is malformed or names no host
   */
  private static String hostOf(String url, String variable) {
    try {
      String host = new URI(url).getHost();
      if (host == null) {
        throw new IllegalStateException(
            variable + " is '" + url + "', which names no host. It must be an absolute URL.");
      }
      return IDN.toASCII(host).toLowerCase(Locale.ROOT);
    } catch (URISyntaxException | IllegalArgumentException malformed) {
      throw new IllegalStateException(variable + " is not a valid URL: " + url, malformed);
    }
  }

  private static String trimTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
