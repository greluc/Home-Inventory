/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

/**
 * An {@code If-Match} arrived that this API could not have issued (REQ-API-004).
 *
 * <p>A weak tag, an unquoted value, anything that is not a quoted number. Answered {@code 412}
 * rather than {@code 400}: a tag that cannot be one of ours cannot match the resource either, and
 * the caller's next move is the same as for any mismatch — read the resource again. Telling the two
 * apart would ask a client to branch on a difference it cannot act on.
 *
 * <p>The message does <b>not</b> repeat what arrived. A header is untrusted input, and an error
 * that echoes it back is a reflection — into a problem document a browser may render, a log line an
 * operator reads, or a terminal that interprets escapes. The caller knows what it sent; what it
 * needs from here is what a tag is supposed to look like.
 */
public class PreconditionMalformedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public PreconditionMalformedException() {
    super(
        "If-Match did not carry an entity tag this API issues. They look like \"7\", quoted and "
            + "not weak, and come from the ETag of your last read of this resource.");
  }
}
