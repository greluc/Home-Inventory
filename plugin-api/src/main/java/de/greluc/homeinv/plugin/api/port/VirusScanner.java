/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api.port;

import de.greluc.homeinv.plugin.api.CallContext;
import java.io.InputStream;

/**
 * Checks an upload before anybody can download it again (09 §9.2).
 *
 * <p>ClamAV is in the core image and mandatory: {@code clamd} is part of the deployment, so the
 * adapter opens no connection outside it. Other scanners are interchangeable; <b>"no scanner" is
 * not a supported configuration</b>.
 *
 * <p>A file whose verdict is not yet in is {@code PENDING_SCAN} and is not served. That is the
 * behaviour a caller has to preserve when this port is unavailable: an unscanned file is withheld,
 * never released on the grounds that the scanner was down.
 *
 * <p>Stage 0 (REQ-SEC-030).
 */
public interface VirusScanner {

  /**
   * Scans one stream.
   *
   * <p>The stream is read once, forwards, and the implementation must not need it twice.
   *
   * @param context who it is for
   * @param content the bytes to check
   * @return what the scanner found
   * @throws de.greluc.homeinv.plugin.api.PluginException when the scanner could not be reached or
   *     refused the file for a reason other than its content — a size limit, for instance. The
   *     caller then leaves the file unscanned and withheld rather than guessing
   */
  Verdict scan(CallContext context, InputStream content);

  /**
   * What a scanner found.
   *
   * @param clean {@code true} when the scanner examined the content and found nothing. Never
   *     {@code true} because a scan could not be performed: that case is an exception, so that "not
   *     scanned" can never be mistaken for "scanned and fine"
   * @param signature what was found, as the scanner names it, for the log and for the person whose
   *     upload was refused. Empty when {@code clean}
   */
  record Verdict(boolean clean, String signature) {}
}
