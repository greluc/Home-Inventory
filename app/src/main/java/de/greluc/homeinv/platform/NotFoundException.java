/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.util.UUID;
import lombok.Getter;

/**
 * The requested thing does not exist for the current tenant.
 *
 * <p>Deliberately one exception for three situations: the id is unknown, the row is a tombstone, or
 * it belongs to another tenant. Distinguishing them in the response would let a caller confirm that
 * a foreign id exists, which is the enumeration this project answers identically either way
 * (REQ-SEC-025). The REST layer maps this to a 404.
 */
@Getter
public class NotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** What kind of thing was looked for, used to build the problem type URI. */
  private final transient String resource;

  /** The identifier that was looked for. */
  private final transient String id;

  /**
   * Creates the exception.
   *
   * @param resource the kind of thing, lowercase and singular, for example {@code item}
   * @param id the identifier that was not found
   */
  public NotFoundException(String resource, UUID id) {
    this(resource, String.valueOf(id));
  }

  /**
   * Creates the exception for something whose identifier is not a UUID.
   *
   * <p>Most things here are found by a UUIDv7 (ADR-0016), and a few are not: a field is found by
   * its key, a session by the handle that stands in for its id (REQ-AUTH-009). The identifier
   * reaches the message and the log and never the answer, which says only what kind of thing was
   * not visible — so what type it is matters to a reader of the log and to nobody else.
   *
   * @param resource the kind of thing, lowercase and singular
   * @param id the identifier that was not found
   */
  public NotFoundException(String resource, String id) {
    super(resource + " " + id + " not found");
    this.resource = resource;
    this.id = id;
  }
}
