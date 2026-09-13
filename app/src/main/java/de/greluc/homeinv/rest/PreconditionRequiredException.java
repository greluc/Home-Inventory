/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

/**
 * A write on a single resource arrived without {@code If-Match} (REQ-API-004).
 *
 * <p>{@code 428 Precondition Required}, which exists for exactly this: the server is refusing a
 * request it could otherwise perform, because performing it would risk the lost update the header
 * is there to prevent. There is no blind overwrite.
 *
 * <p>In {@code rest} and not in {@code platform}, because it is about HTTP. What the version means
 * is a domain question; whether a request carried one is not.
 */
public class PreconditionRequiredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public PreconditionRequiredException() {
    super(
        "This request changes one thing and has to say which version of it you were looking at. "
            + "Send If-Match with the ETag from your last read of it.");
  }
}
