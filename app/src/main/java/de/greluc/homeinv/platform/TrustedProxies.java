/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The hops whose {@code X-Forwarded-*} headers are believed (REQ-SEC-063, REQ-SEC-103).
 *
 * <h2>Why a list and not a boolean</h2>
 *
 * <p>There are two hops in front of {@code api}: the operator's reverse proxy, which sets
 * {@code X-Forwarded-For}, and {@code web}, which appends its own step (06 §6.7). Both must be in
 * this list, and getting it wrong is quiet in both directions:
 *
 * <ul>
 *   <li><b>Too narrow</b> — {@code web} is not trusted, so every request appears to come from
 *       {@code web}. Per-IP rate limiting then throttles all tenants as one client, and every audit
 *       entry records the same source address.
 *   <li><b>Too wide</b> — a header a client supplied is believed, and both the rate limiting and
 *       the audit trail become attacker-controlled.
 * </ul>
 *
 * <h2>The startup check</h2>
 *
 * <p>06 §6.7 asks for one, "because a deployment where it is not produces no error — only wrong
 * numbers". This resolves the ingress hostname and fails startup when its address is outside the
 * list. When the name does not resolve — a test, a laptop, a deployment that calls it something
 * else — the check says so and continues, because refusing to start over a name lookup would be a
 * worse failure than the one it guards against.
 *
 * <p>{@code 0.0.0.0/0} is refused outright. It is the configuration that makes the whole mechanism
 * decorative, and it is what somebody reaches for when the narrow case bites.
 */
@Slf4j
@Component
public final class TrustedProxies {

  private final List<Cidr> ranges;

  /**
   * Parses and validates the list.
   *
   * @param configured the comma-separated CIDR list from {@code HOMEINV_TRUSTED_PROXIES}
   * @param ingressHost the hostname of the ingress on the frontend segment, resolved and checked
   * @throws IllegalStateException when the list is empty, unparsable, wide open, or does not
   *     contain the ingress
   */
  public TrustedProxies(
      @Value("${homeinv.trusted-proxies:}") String configured,
      @Value("${homeinv.ingress-host:web}") String ingressHost) {

    if (configured == null || configured.isBlank()) {
      throw new IllegalStateException(
          "HOMEINV_TRUSTED_PROXIES is not set. Every request reaches `api` through `web`, so "
              + "without it no forwarded header is believed and every client appears to be `web` "
              + "(REQ-SEC-063, 06 §6.7).");
    }

    this.ranges = new ArrayList<>();
    for (String entry : configured.split(",")) {
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      if ("0.0.0.0/0".equals(trimmed) || "::/0".equals(trimmed)) {
        throw new IllegalStateException(
            "HOMEINV_TRUSTED_PROXIES contains " + trimmed + ", which trusts every client's "
                + "X-Forwarded-For header. Per-IP rate limiting and the audit trail would then be "
                + "attacker-controlled (REQ-SEC-063).");
      }
      ranges.add(Cidr.parse(trimmed));
    }

    if (ranges.isEmpty()) {
      throw new IllegalStateException("HOMEINV_TRUSTED_PROXIES contains no usable entry.");
    }
    verifyIngressIsTrusted(ingressHost);
  }

  /**
   * Whether a peer's headers are believed.
   *
   * @param address the address the connection actually came from
   * @return true when it falls in a configured range
   */
  public boolean trusts(String address) {
    byte[] parsed = parse(address);
    return parsed != null && ranges.stream().anyMatch(range -> range.contains(parsed));
  }

  private void verifyIngressIsTrusted(String ingressHost) {
    InetAddress[] resolved;
    try {
      resolved = InetAddress.getAllByName(ingressHost);
    } catch (UnknownHostException unresolvable) {
      log.info(
          "The ingress host '{}' does not resolve here, so it could not be checked against "
              + "HOMEINV_TRUSTED_PROXIES. In a deployment it must be in the list.",
          ingressHost);
      return;
    }
    for (InetAddress address : resolved) {
      if (!trusts(address.getHostAddress())) {
        throw new IllegalStateException(
            ("The ingress '%s' resolves to %s, which is not in HOMEINV_TRUSTED_PROXIES. "
                    + "Every request arrives through it, so none of their forwarded headers would "
                    + "be believed: per-IP rate limiting would throttle all tenants as one client "
                    + "and every audit entry would record the same source address (REQ-SEC-103, "
                    + "06 §6.7).")
                .formatted(ingressHost, address.getHostAddress()));
      }
    }
    log.info("Ingress '{}' is covered by HOMEINV_TRUSTED_PROXIES.", ingressHost);
  }

  private static byte[] parse(String address) {
    try {
      // getByName on a literal address performs no name lookup, which is what is
      // wanted here: a hostname arriving as a peer address would be a bug, and
      // resolving it would hide that behind a DNS round trip.
      return InetAddress.getByName(address).getAddress();
    } catch (UnknownHostException notAnAddress) {
      return null;
    }
  }

  /** One CIDR range, kept as the address bytes plus how many leading bits are significant. */
  private record Cidr(byte[] network, int prefixBits) {

    static Cidr parse(String notation) {
      String[] parts = notation.split("/", 2);
      byte[] address = TrustedProxies.parse(parts[0]);
      if (address == null) {
        throw new IllegalStateException(
            "HOMEINV_TRUSTED_PROXIES contains '" + notation + "', which is not an address.");
      }
      int bits = parts.length == 2 ? Integer.parseInt(parts[1]) : address.length * 8;
      if (bits < 0 || bits > address.length * 8) {
        throw new IllegalStateException(
            "HOMEINV_TRUSTED_PROXIES contains '" + notation + "', whose prefix length is out of "
                + "range for the address family.");
      }
      return new Cidr(address, bits);
    }

    boolean contains(byte[] candidate) {
      if (candidate.length != network.length) {
        // An IPv4 range never contains an IPv6 address, and comparing them by
        // prefix would produce an answer rather than a mismatch.
        return false;
      }
      int wholeBytes = prefixBits / 8;
      if (!Arrays.equals(network, 0, wholeBytes, candidate, 0, wholeBytes)) {
        return false;
      }
      int remainingBits = prefixBits % 8;
      if (remainingBits == 0) {
        return true;
      }
      int mask = 0xFF << (8 - remainingBits);
      return (network[wholeBytes] & mask) == (candidate[wholeBytes] & mask);
    }
  }
}
