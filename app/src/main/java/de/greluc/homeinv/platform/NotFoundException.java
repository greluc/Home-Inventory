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
 * (REQ-SEC-016). The REST layer maps this to a 404.
 */
@Getter
public class NotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** What kind of thing was looked for, used to build the problem type URI. */
  private final transient String resource;

  /** The identifier that was looked for. */
  private final transient UUID id;

  /**
   * Creates the exception.
   *
   * @param resource the kind of thing, lowercase and singular, for example {@code item}
   * @param id the identifier that was not found
   */
  public NotFoundException(String resource, UUID id) {
    super(resource + " " + id + " not found");
    this.resource = resource;
    this.id = id;
  }
}
