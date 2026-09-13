/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.api;

/**
 * The password was right and the account holds a second factor (REQ-AUTH-002).
 *
 * <p>Not a failure: it is the middle of a login. The password has been verified — which is why this
 * is raised at all rather than the login simply being refused — and what is missing is the code.
 * The caller answers it at {@code POST /api/v1/auth/mfa}, against the pending session this leaves
 * behind.
 *
 * <p>Saying this much is deliberate and costs nothing. Whoever sees it has already presented the
 * right password for the address, so "this account has a second factor" tells them nothing they
 * could not find out by trying — and a login that simply hung or failed would send somebody to
 * support instead of to their phone.
 */
public class SecondFactorRequiredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public SecondFactorRequiredException() {
    super("A second factor is required");
  }
}
