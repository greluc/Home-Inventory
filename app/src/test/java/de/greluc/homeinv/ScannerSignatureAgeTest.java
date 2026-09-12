/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.media.infrastructure.ClamAvScanner;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scanner's signature date is read from what clamd actually says (REQ-SEC-093).
 *
 * <p>Parsing, not connecting. What can go wrong here is the format — clamd prints the weekday and
 * the month in the C locale whatever the host's is, so a German host parsing with the default locale
 * fails on {@code Thu}, and the day is space-padded rather than zero-padded on the first nine days
 * of a month. Both of those are silent: the metric reads zero, the warning never fires, and the
 * signatures quietly go stale.
 */
@DisplayName("The scanner's version reply")
class ScannerSignatureAgeTest {

  @Test
  @DisplayName("yields the date the signature database was built")
  void theDateIsRead() {
    Optional<Instant> dated =
        ClamAvScanner.parseSignatureDate("ClamAV 1.4.3/27512/Fri Sep 11 09:23:41 2026");

    assertThat(dated).isPresent();
    assertThat(dated.orElseThrow().atZone(ZoneOffset.UTC).toString())
        .isEqualTo("2026-09-11T09:23:41Z");
  }

  @Test
  @DisplayName("copes with the space-padded day clamd prints before the tenth")
  void aPaddedDayIsRead() {
    // "Sep  1" with two spaces. A `dd` pattern refuses it and a `d` pattern
    // refuses the two spaces, which is why the format string uses `ppd`.
    Optional<Instant> dated =
        ClamAvScanner.parseSignatureDate("ClamAV 1.4.3/27400/Tue Sep  1 04:15:07 2026");

    assertThat(dated).isPresent();
    assertThat(dated.orElseThrow().atZone(ZoneOffset.UTC).toString())
        .isEqualTo("2026-09-01T04:15:07Z");
  }

  @Test
  @DisplayName("is empty rather than a guess when the reply is something else")
  void anUnreadableReplyYieldsNothing() {
    assertThat(ClamAvScanner.parseSignatureDate("ERROR")).isEmpty();
    assertThat(ClamAvScanner.parseSignatureDate("ClamAV 1.4.3/27512")).isEmpty();
    assertThat(ClamAvScanner.parseSignatureDate("ClamAV 1.4.3/27512/whenever")).isEmpty();
  }
}
