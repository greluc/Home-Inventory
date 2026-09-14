/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether a plugin's contract range covers the version a core serves (REQ-PLG-008).
 *
 * <p>The answer decides whether a plugin is registered at all, and the consequence of getting it
 * wrong in the permissive direction is a plugin talking to a contract it was never built against.
 * So the interesting assertions are the refusals: a range this cannot read is refused rather than
 * assumed to match.
 */
@DisplayName("A contract range")
class ContractVersionTest {

  @Test
  @DisplayName("covers the served version, or does not")
  void theOrdinaryCases() {
    // 09 §9.3's own example, against the version this core serves.
    assertThat(ContractVersion.covers(">=1.0.0 <2.0.0", "1.0.0")).isTrue();
    assertThat(ContractVersion.covers(">=1.0.0 <2.0.0", "1.7.3")).isTrue();
    assertThat(ContractVersion.covers(">=1.0.0 <2.0.0", "2.0.0")).isFalse();
    assertThat(ContractVersion.covers(">=1.2.0 <2.0.0", "1.1.9")).isFalse();

    // The core's own version is covered by the example, which is the case that
    // matters: a plugin written against the published example works here.
    assertThat(ContractVersion.covers(">=1.0.0 <2.0.0")).isTrue();
  }

  @Test
  @DisplayName("reads every comparison the grammar has")
  void theOperators() {
    assertThat(ContractVersion.covers("=1.4.2", "1.4.2")).isTrue();
    assertThat(ContractVersion.covers("1.4.2", "1.4.2")).isTrue();
    assertThat(ContractVersion.covers(">1.4.2", "1.4.3")).isTrue();
    assertThat(ContractVersion.covers(">1.4.2", "1.4.2")).isFalse();
    assertThat(ContractVersion.covers("<=1.4.2", "1.4.2")).isTrue();
    assertThat(ContractVersion.covers("<1.4.2", "1.4.2")).isFalse();
  }

  @Test
  @DisplayName("orders by each part and not by the string")
  void ordering() {
    // "1.10.0" sorts before "1.9.0" as text and after it as a version, which is
    // the mistake a string comparison makes and this one does not.
    assertThat(ContractVersion.covers(">=1.9.0", "1.10.0")).isTrue();
    assertThat(ContractVersion.covers("<1.9.0", "1.10.0")).isFalse();
  }

  @Test
  @DisplayName("refuses a range it cannot read, rather than assuming it matches")
  void whatIsRefused() {
    // Assuming a match is how a plugin runs against a contract it was never
    // built for. The failure of this decision is a plugin that does not start.
    assertThatThrownBy(() -> ContractVersion.covers("^1.0.0", "1.0.0"))
        .isInstanceOf(InvalidManifestException.class);
    assertThatThrownBy(() -> ContractVersion.covers("1.x", "1.0.0"))
        .isInstanceOf(InvalidManifestException.class);
    assertThatThrownBy(() -> ContractVersion.covers(">=1.0", "1.0.0"))
        .isInstanceOf(InvalidManifestException.class)
        .hasMessageContaining("major.minor.patch");
    assertThatThrownBy(() -> ContractVersion.covers("", "1.0.0"))
        .isInstanceOf(InvalidManifestException.class);
  }
}
