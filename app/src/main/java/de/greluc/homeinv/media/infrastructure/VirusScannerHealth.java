/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.infrastructure;

import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Says out loud when the mandatory scanner is not there (REQ-SEC-091, ADR-0024).
 *
 * <h2>What this exists to prevent</h2>
 *
 * <p>"No scanner" is not a supported configuration, and the scan is fail-closed — so an unreachable
 * scanner does not let anything through, it makes every upload unretrievable (REQ-SEC-092).
 * That is the right behaviour and a terrible symptom: from the outside it looks like uploads being
 * slow to appear, and the actual cause is a container that did not start. This reports the cause
 * where an operator is already looking, at startup and on every health read.
 *
 * <h2>Why it does not abort the start</h2>
 *
 * <p>{@code REQ-SEC-091} says startup without a reachable scanner is <b>reported</b>, and that is the
 * right verb rather than a softening: the worker also generates derivatives, delivers
 * notifications and runs housekeeping, and taking all of it down because one dependency is late
 * would turn a degraded instance into a stopped one. Uploads stay blocked either way.
 *
 * <h2>Why only in the worker</h2>
 *
 * <p>{@code api} has no route to {@code clamd} and never had (ADR-0054, ADR-0037): the scan runs in
 * the worker and never in the request path. An indicator here in every role would report the API as
 * unhealthy for a connection it is not supposed to be able to make.
 */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
public class VirusScannerHealth implements HealthIndicator, InitializingBean {

  private final ClamAvScanner scanner;

  /**
   * Looks for the scanner once at startup and says what it found.
   *
   * <p>At {@code ERROR} when it is missing, because every upload in the instance is affected and
   * the requirement calls this the reportable moment.
   */
  @Override
  public void afterPropertiesSet() {
    Optional<Instant> signatures = scanner.signatureDate();
    if (signatures.isEmpty()) {
      log.error(
          "The malware scanner is not reachable. Uploads are accepted and stay unretrievable"
              + " until it answers (REQ-SEC-091, REQ-SEC-092). \"No scanner\" is not a supported"
              + " configuration: check that the clamav container is running and on the scanner"
              + " segment.");
      return;
    }
    log.info("The malware scanner answered; its signatures were built {}.", signatures.get());
  }

  /**
   * The health view of the same question.
   *
   * @return {@code UP} with the signature date when the scanner answers, {@code DOWN} when it does
   *     not
   */
  @Override
  public Health health() {
    Optional<Instant> signatures = scanner.signatureDate();
    return signatures
        .map(built -> Health.up().withDetail("signaturesBuiltAt", built.toString()).build())
        .orElseGet(
            () ->
                Health.down()
                    .withDetail("reason", "The malware scanner did not answer")
                    .withDetail("effect", "Uploads stay unretrievable until it does")
                    .build());
  }
}
