/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import java.util.UUID;

/**
 * Whether a place lies in the part of the tree this session is confined to (REQ-TEN-007).
 *
 * <p>Declared <b>here</b> and implemented by {@code locations}, which owns the tree. The direction
 * is forced: {@code locations} already depends on this block — it asks {@code ItemLocationUsage}
 * whether a place still holds anything before letting it be deleted — so a call the other way would
 * close a cycle, and Spring Modulith fails the build on one. It is the same arrangement
 * {@code catalog.AttributeUsage} has with this block, read the other way round.
 *
 * <p>What it is for is an <em>answer</em>. The row-level security policy of ADR-0059 already refuses
 * an item placed outside the scope; without this the refusal arrives as a policy violation and
 * reaches a client as a {@code 500}. With it the caller is told the place is not there, which is
 * what a place they may not see looks like to them (REQ-SEC-025).
 */
public interface PlaceScope {

  /**
   * Whether a place lies within a scope.
   *
   * @param scopeRootId the place the membership is confined to, or null for the whole tenant
   * @param locationId the place being named, or null
   * @return {@code true} when the scope is null, or when the place is the scope or sits below it.
   *     {@code false} for a place elsewhere, a place that does not exist, and for null — a thing
   *     with no place is in nobody's garage
   */
  boolean allows(UUID scopeRootId, UUID locationId);
}
