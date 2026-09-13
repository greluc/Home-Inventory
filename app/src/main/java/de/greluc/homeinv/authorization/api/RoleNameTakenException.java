/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.api;

import lombok.Getter;

/**
 * A live role of that name already exists in this tenant (REQ-TEN-006).
 *
 * <p>Answered as {@code 409} with {@code name-taken}, the same token a location's sibling name
 * collision gets: a client branching on the status has to tell "that name is taken" from "that
 * identifier is taken", and only one of the two is fixed by choosing a different id.
 *
 * <p>Case-insensitive, like every other name in this system. Two roles called "Warehouse" and
 * "warehouse" are a way to grant one and revoke the other by accident.
 */
@Getter
public class RoleNameTakenException extends RuntimeException {

  /** The name that is taken, as the caller spelled it. */
  private final String name;

  /**
   * @param name the name that is taken
   */
  public RoleNameTakenException(String name) {
    super("A role called '" + name + "' already exists in this tenant.");
    this.name = name;
  }
}
