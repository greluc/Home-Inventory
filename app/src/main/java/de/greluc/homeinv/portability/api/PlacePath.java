/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.api;

import java.util.UUID;

/**
 * Turning a path of names into a place (REQ-PORT-001).
 *
 * <p>A CSV from another system says where something is as text — {@code Garage / Shelf / Box} —
 * because that is what a person typed there. This resolves such a path against the tenant's tree
 * and <b>creates what is missing</b>, which is the only behaviour that makes a file of five hundred
 * rows importable: the alternative refuses every row whose place does not exist yet, which is all
 * of them on the first import.
 *
 * <p>Implemented by {@code locations}, so {@code portability} does not point at it: that block
 * already points here in order to export and import itself, and a call the other way would close a
 * cycle (ADR-0002).
 */
public interface PlacePath {

  /**
   * The place at this path, creating any part of it that is not there.
   *
   * <p>Matching is by name among siblings, case-insensitively, which is what the tenant's own
   * uniqueness rule already uses. An empty or blank path is no place at all.
   *
   * @param path names separated by {@code /}, with any spacing around the separator
   * @param actor who is importing, recorded as the creator of anything new
   * @return the deepest place on the path, or null when the path is blank
   */
  UUID resolveOrCreate(String path, UUID actor);
}
