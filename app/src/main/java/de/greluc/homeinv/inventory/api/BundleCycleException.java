/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

/**
 * The membership would make a bundle contain itself (REQ-CORE-007).
 *
 * <p>Directly — a bag in its own bag — or through any chain of bundles, which is the case worth
 * having an exception for: with several bundles per item the loop can be long and is not visible
 * from either end of the request that would close it.
 *
 * <p>A conflict rather than a malformed request. Nothing about the call is wrong, and the same call
 * against a different bundle works; what refuses it is the state of the graph.
 */
public class BundleCycleException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param reason what is wrong, in a sentence meant for a person
   */
  public BundleCycleException(String reason) {
    super(reason);
  }
}
