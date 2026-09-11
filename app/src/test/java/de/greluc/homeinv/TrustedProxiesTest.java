/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.platform.TrustedProxies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What may claim to speak for a client (REQ-SEC-063, REQ-SEC-103).
 *
 * <p>No container and no context: this is address arithmetic, and the failure it guards against —
 * an audit trail and a rate limiter that believe a header — needs no database to be wrong.
 *
 * <p>{@code web.invalid} throughout: {@code .invalid} is reserved by RFC 2606 and never resolves,
 * which is what makes the "ingress unknown here" path deterministic rather than dependent on
 * whatever the runner's resolver does with an unknown name.
 */
class TrustedProxiesTest {

  @Test
  @DisplayName("an unset list fails startup rather than trusting nobody quietly")
  void unsetListFailsStartup() {
    // Trusting nobody is the safe direction and still wrong: every request would
    // appear to come from `web`, the rate limiter would throttle all tenants as
    // one client, and nothing would say so.
    assertThatThrownBy(() -> new TrustedProxies("", "web.invalid"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HOMEINV_TRUSTED_PROXIES is not set");
  }

  @Test
  @DisplayName("0.0.0.0/0 is refused: it makes the whole mechanism decorative")
  void wideOpenIsRefused() {
    assertThatThrownBy(() -> new TrustedProxies("10.0.0.0/8, 0.0.0.0/0", "web.invalid"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("trusts every client");

    assertThatThrownBy(() -> new TrustedProxies("::/0", "web.invalid"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("trusts every client");
  }

  @Test
  @DisplayName("an entry that is not an address fails startup, it is not skipped")
  void unparsableEntryFailsStartup() {
    assertThatThrownBy(() -> new TrustedProxies("10.0.0.0/8, not-an-address", "web.invalid"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not an address");
  }

  @Test
  @DisplayName("a prefix wider than the address family fails startup")
  void impossiblePrefixFailsStartup() {
    assertThatThrownBy(() -> new TrustedProxies("10.0.0.0/33", "web.invalid"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("prefix length is out of range");
  }

  @Test
  @DisplayName("membership is decided on the prefix, including one that is not a whole byte")
  void membershipRespectsThePrefix() {
    TrustedProxies trusted = new TrustedProxies("10.1.2.0/24, 192.168.4.0/22", "web.invalid");

    assertThat(trusted.trusts("10.1.2.1")).isTrue();
    assertThat(trusted.trusts("10.1.2.255")).isTrue();
    assertThat(trusted.trusts("10.1.3.1")).isFalse();

    // /22 covers 192.168.4.0 through 192.168.7.255. The boundary is inside a
    // byte, which is the case a whole-byte comparison gets wrong.
    assertThat(trusted.trusts("192.168.7.255")).isTrue();
    assertThat(trusted.trusts("192.168.8.0")).isFalse();
  }

  @Test
  @DisplayName("an IPv4 range never covers an IPv6 address")
  void familiesDoNotMix() {
    TrustedProxies trusted = new TrustedProxies("10.0.0.0/8", "web.invalid");

    // Comparing four bytes against sixteen by prefix would produce an answer
    // rather than a mismatch, and the answer would be "trusted".
    assertThat(trusted.trusts("::1")).isFalse();
    assertThat(trusted.trusts("2001:db8::1")).isFalse();
  }

  @Test
  @DisplayName("a single address without a prefix means exactly that address")
  void bareAddressIsASingleHost() {
    TrustedProxies trusted = new TrustedProxies("172.20.0.5", "web.invalid");

    assertThat(trusted.trusts("172.20.0.5")).isTrue();
    assertThat(trusted.trusts("172.20.0.6")).isFalse();
  }

  @Test
  @DisplayName("an ingress that resolves outside the list fails startup")
  void ingressOutsideTheListFailsStartup() {
    // localhost resolves everywhere, and 10.0.0.0/8 does not contain it. This is
    // the misconfiguration 06 §6.7 says produces no error and only wrong numbers,
    // so the check has to be the error.
    assertThatThrownBy(() -> new TrustedProxies("10.0.0.0/8", "localhost"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("is not in HOMEINV_TRUSTED_PROXIES");
  }

  @Test
  @DisplayName("an ingress that resolves inside the list starts")
  void ingressInsideTheListStarts() {
    TrustedProxies trusted = new TrustedProxies("127.0.0.0/8, ::1/128", "localhost");
    assertThat(trusted.trusts("127.0.0.1")).isTrue();
  }

  @Test
  @DisplayName("an ingress that does not resolve is reported, not fatal")
  void unresolvableIngressIsNotFatal() {
    // A laptop and a CI runner have no `web`. Refusing to start over a name
    // lookup would be a worse failure than the one the check guards against.
    TrustedProxies trusted = new TrustedProxies("10.0.0.0/8", "web.invalid");
    assertThat(trusted.trusts("10.9.9.9")).isTrue();
  }
}
