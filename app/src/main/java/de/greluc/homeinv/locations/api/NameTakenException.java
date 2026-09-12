/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.api;

import java.util.UUID;
import lombok.Getter;

/**
 * A sibling of this location already carries this name.
 *
 * <p>Names are unique among siblings, case-insensitively, so that the tree a person reads matches
 * the tree the database holds — two boxes called "Winter" in the same cupboard are not a tree
 * anybody can navigate. The database enforces it with the partial unique index
 * {@code location_sibling_name}, partial because a deleted location leaves a tombstone (07 §7.1,
 * rule 5) and a tombstone must not block re-using the name.
 *
 * <p>This type exists because the constraint was enforced and never answered for: a duplicate name
 * surfaced as a {@code DataIntegrityViolationException} and the API returned {@code 500}, which is
 * the one answer that tells a caller nothing and suggests the fault is ours. Found on 2026-09-12 by
 * running {@code deploy/smoke/journey.sh} twice against the same deployment (REQ-CORE-064).
 */
@Getter
public class NameTakenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The name that is already in use. Echoed back, because the caller sent it. */
  private final transient String name;

  /** The parent whose children share the name, or {@code null} among the roots. */
  private final transient UUID parentId;

  /**
   * Creates the exception.
   *
   * @param name the name a sibling already has
   * @param parentId the parent the siblings hang under, or {@code null} for a root
   */
  public NameTakenException(String name, UUID parentId) {
    super("A sibling is already called " + name);
    this.name = name;
    this.parentId = parentId;
  }
}
