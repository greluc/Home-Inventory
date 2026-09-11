/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.application;

import java.util.UUID;
import lombok.Getter;

/**
 * An item with this id already exists for the tenant.
 *
 * <p>Reachable only because the client may choose the id (ADR-0016). Without that, a collision
 * would be impossible; with it, this is an ordinary outcome of a retry that already succeeded, and
 * it deserves a clear answer rather than a constraint violation surfacing as a 500.
 */
@Getter
public class ItemAlreadyExistsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The identifier that is already taken. */
  private final transient UUID id;

  /**
   * Creates the exception.
   *
   * @param id the identifier that is already in use in this tenant
   */
  public ItemAlreadyExistsException(UUID id) {
    super("Item " + id + " already exists");
    this.id = id;
  }
}
