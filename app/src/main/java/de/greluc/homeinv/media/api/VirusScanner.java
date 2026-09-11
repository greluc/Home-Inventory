/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.api;

import java.io.InputStream;

/**
 * The malware scan every upload passes through (REQ-MED-013, ADR-0024).
 *
 * <p>Mandatory in every profile and <b>fail-closed</b>: when the scanner cannot be reached the
 * upload is refused, not accepted unscanned. That is the decision ADR-0024 records, and it is worth
 * restating here because the opposite is so tempting during an outage.
 */
public interface VirusScanner {

  /**
   * Scans a stream.
   *
   * @param content the bytes to scan
   * @return the verdict
   * @throws ScannerUnavailableException when the scanner cannot be reached or times out. Not a
   *     clean verdict and not an exception the caller should swallow: the upload is rejected with a
   *     {@code 503} and the client can retry (REQ-MED-013, problem type {@code scan-unavailable})
   */
  Verdict scan(InputStream content);

  /**
   * What the scanner found.
   *
   * @param clean whether nothing was found
   * @param signature the name of what was found, or {@code null} when clean. Recorded in the audit
   *     log, never returned to the uploader: it says what the scanner knows, and an uploader
   *     probing which payloads are detected would learn it one upload at a time
   */
  record Verdict(boolean clean, String signature) {}
}
