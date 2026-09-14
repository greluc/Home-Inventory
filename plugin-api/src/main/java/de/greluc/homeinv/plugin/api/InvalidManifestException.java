/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api;

import java.io.Serial;

/**
 * A manifest this core cannot act on (REQ-PLG-004).
 *
 * <p>Its message is written for the person who wrote the manifest rather than for the person
 * reading a stack trace: it names the key, what was there and what was expected. A plugin author
 * sees it from the SDK before shipping, and an operator sees it in the log when a registration is
 * refused — the same sentence in both places, because it is the same mistake.
 */
public class InvalidManifestException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Says what is wrong with the manifest.
   *
   * @param message what an author has to change
   */
  public InvalidManifestException(String message) {
    super(message);
  }
}
