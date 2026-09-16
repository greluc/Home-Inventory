/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.media.infrastructure.ClamAvScanner;
import de.greluc.homeinv.media.infrastructure.VirusScannerHealth;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * Saying so when the mandatory scanner is not there (REQ-SEC-091).
 *
 * <p>"No scanner" is not a supported configuration, and the scan is fail-closed — so an absent one
 * blocks every upload rather than letting anything through (REQ-SEC-092, proved in {@code
 * MediaScanIT}). The failure mode this covers is the other one: that the cause is invisible. An
 * operator sees uploads that never become retrievable and no statement anywhere about why.
 *
 * <p>No container is started here on purpose. What is being tested is the answer when nothing
 * answers, and the cheapest honest way to have nothing answer is a port with nothing behind it.
 */
@DisplayName("The scanner health check")
class VirusScannerHealthTest {

  @Test
  @DisplayName("reports DOWN with the reason and the effect when the scanner does not answer")
  void unreachable() throws IOException {
    VirusScannerHealth health = new VirusScannerHealth(new ClamAvScanner("127.0.0.1", closedPort(), 250));

    Health reported = health.health();

    assertThat(reported.getStatus()).isEqualTo(Status.DOWN);
    // Both halves, because either alone is unhelpful: what is wrong, and what it
    // means for the person whose upload is not appearing.
    assertThat(reported.getDetails())
        .containsEntry("reason", "The malware scanner did not answer")
        .containsEntry("effect", "Uploads stay unretrievable until it does");
  }

  @Test
  @DisplayName("says so at startup without taking the worker down with it")
  void reportedAtStartupAndNotFatal() throws IOException {
    VirusScannerHealth health = new VirusScannerHealth(new ClamAvScanner("127.0.0.1", closedPort(), 250));

    // REQ-SEC-091 says startup without a reachable scanner is REPORTED. Aborting
    // would stop derivative generation, notification delivery and housekeeping
    // too — turning a degraded worker into a stopped one, while uploads stay
    // blocked either way.
    health.afterPropertiesSet();
  }

  /**
   * A port with nothing listening on it.
   *
   * @return a port number that was free a moment ago
   * @throws IOException when no port could be taken even briefly
   */
  private static int closedPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
