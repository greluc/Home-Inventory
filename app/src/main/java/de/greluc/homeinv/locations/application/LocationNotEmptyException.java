/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.application;

import java.util.UUID;
import lombok.Getter;

/**
 * The location still contains something, so it cannot be deleted.
 *
 * <p>Carries why, because "it still contains other locations" and "it still contains items" lead a
 * user to two different next actions.
 */
@Getter
public class LocationNotEmptyException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The location concerned. */
  private final transient UUID locationId;

  /**
   * Creates the exception.
   *
   * @param locationId the location
   * @param reason what is still inside, phrased for a person to read
   */
  public LocationNotEmptyException(UUID locationId, String reason) {
    super("Location " + locationId + " cannot be deleted: " + reason);
    this.locationId = locationId;
  }
}
