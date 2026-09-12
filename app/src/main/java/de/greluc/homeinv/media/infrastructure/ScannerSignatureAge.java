/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How old the malware scanner's signatures are (REQ-SEC-093).
 *
 * <h2>Why this is a metric and not a health check</h2>
 *
 * <p>Stale signatures do not stop anything: uploads are scanned, and they are scanned against what
 * the scanner has. Reporting the instance as unhealthy would take it out of rotation for a condition
 * that makes it slightly less effective rather than wrong — and an operator whose signatures are two
 * days old wants to know, not to lose their application.
 *
 * <p>So: a gauge, and a line at {@code WARN} past the threshold. The gauge is what an alert rule
 * reads; the line is what somebody without one eventually notices.
 *
 * <h2>Why the value is cached</h2>
 *
 * <p>Every read is a TCP round trip to clamd. A Prometheus scrape every fifteen seconds would turn
 * that into a connection every fifteen seconds for a number that changes once a day. The freshness
 * window below is far shorter than the threshold, so a stale reading can never hide a stale
 * signature.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ScannerSignatureAge {

  /** Past this, the signatures are old enough to say so (REQ-SEC-093). */
  private static final Duration THRESHOLD = Duration.ofHours(48);

  /** How long a reading is reused before clamd is asked again. */
  private static final Duration FRESH_FOR = Duration.ofMinutes(15);

  private final ClamAvScanner scanner;
  private final MeterRegistry meters;
  private final Clock clock;

  /** What the signatures are dated, as last read. Never read directly; {@link #age()} does. */
  private volatile Instant signatureDate;

  /** When that reading was taken, so it can be reused for a while. */
  private volatile Instant readAt = Instant.EPOCH;

  /** Registers the gauge. */
  @PostConstruct
  void register() {
    Gauge.builder("homeinv.scanner.signature.age", this, holder -> holder.age().toSeconds())
        .description("Age of the malware scanner's signature database (REQ-SEC-093)")
        .baseUnit("seconds")
        .register(meters);
  }

  /**
   * How old the signatures are.
   *
   * <p>A scanner that cannot be asked reports {@link Duration#ZERO} rather than a very large number:
   * "unreachable" is a different condition with its own answer — uploads are refused with a {@code
   * 503} and the blob stays unretrievable (REQ-SEC-092) — and a gauge that spiked to infinity
   * whenever the scanner restarted would page somebody for the wrong reason.
   *
   * @return the age, or zero when the scanner could not be asked
   */
  public Duration age() {
    Instant now = Instant.now(clock);
    if (Duration.between(readAt, now).compareTo(FRESH_FOR) > 0) {
      refresh(now);
    }

    Instant dated = signatureDate;
    if (dated == null) {
      return Duration.ZERO;
    }
    Duration age = Duration.between(dated, now);
    return age.isNegative() ? Duration.ZERO : age;
  }

  private void refresh(Instant now) {
    readAt = now;
    Optional<Instant> dated = scanner.signatureDate();
    signatureDate = dated.orElse(null);

    dated.ifPresent(
        when -> {
          Duration age = Duration.between(when, now);
          if (age.compareTo(THRESHOLD) > 0) {
            // The update goes out through the egress proxy, which carries the
            // mirror as its one deployment allowlist entry (ADR-0036). Signatures
            // this old usually mean that path is broken, not that the mirror is.
            log.warn(
                "The malware scanner's signatures are {} hours old. Updates reach the mirror "
                    + "through the egress proxy; check that it is running and that its allowlist "
                    + "still carries the ClamAV entry (REQ-SEC-093).",
                age.toHours());
          }
        });
  }

  /**
   * Whether the scanner's signatures are older than the threshold.
   *
   * @return {@code true} when they are, {@code false} when they are not or cannot be read
   */
  public boolean isStale() {
    Duration age = age();
    return !age.isZero() && age.compareTo(THRESHOLD) > 0;
  }

  /**
   * The threshold, so a test does not restate it.
   *
   * @return 48 hours
   */
  static Duration threshold() {
    return THRESHOLD;
  }
}
