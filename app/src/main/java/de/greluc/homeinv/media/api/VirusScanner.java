/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.api;

import java.io.InputStream;

/**
 * The malware scan every upload passes through (REQ-MED-013, ADR-0024).
 *
 * <p>Mandatory in every profile and <b>fail-closed</b>, which since ADR-0054 means something
 * narrower than it used to and no weaker: an upload with no verdict is never <em>retrievable</em>.
 * It is accepted and stored — the scan runs in the {@code worker}, after the request has been
 * answered — and it stays unretrievable until this port says it is clean. What must never happen is
 * the tempting thing during an outage: turning an absent verdict into a favourable one.
 */
public interface VirusScanner {

  /**
   * Scans a stream.
   *
   * @param content the bytes to scan
   * @return the verdict
   * @throws ScannerUnavailableException when the scanner cannot be reached or times out. Not a
   *     clean verdict and not an exception the caller should swallow: the file keeps no verdict, so
   *     it stays unretrievable and asking for it is answered {@code 503} (REQ-MED-013, problem type
   *     {@code scan-unavailable})
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
