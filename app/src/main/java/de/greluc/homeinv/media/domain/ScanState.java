/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.domain;

/**
 * Where an upload stands with the malware scanner (REQ-MED-013).
 *
 * <p>Only {@code CLEAN} is retrievable. That is the whole point of the state existing rather than a
 * boolean: an upload that has not been judged is not "probably fine", and one whose scan failed is
 * not "clean enough".
 */
public enum ScanState {
  /** Accepted, stored, not yet judged. Not retrievable. */
  PENDING_SCAN,
  /** Judged clean. The only state in which bytes are served. */
  CLEAN,
  /** The scanner found something. The blob is discarded and an audit entry written. */
  INFECTED,
  /**
   * The scanner could not be reached, on every attempt the listener made. Not retrievable, and
   * retryable: the scan-retry queue brings the event back on a fixed delay, and
   * {@code recordVerdict} therefore accepts a transition out of this state — it is the absence of a
   * verdict rather than one (ADR-0054).
   */
  SCAN_FAILED
}
