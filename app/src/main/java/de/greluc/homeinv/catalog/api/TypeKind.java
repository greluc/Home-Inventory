/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.api;

/**
 * Whether the things of a type exist in the physical world.
 *
 * <h2>Why this is not {@code inventory.api.ItemKind}</h2>
 *
 * <p>It is the same distinction, and the item's kind comes from its type — but the dependency may
 * only run one way. {@code inventory} already reaches into this block for the type version an item
 * references; reaching back for this enum would close a cycle between two building blocks, which
 * Spring Modulith refuses and {@code ModularityTest} fails the build over (REQ-NFR-021).
 *
 * <p>So each block names the fact it owns: a <em>type</em> is physical or digital here, an
 * <em>item</em> is physical or digital there, and an item's value is the one its type declares. The
 * two are kept in step by the application, and the database states it once more as a check
 * constraint on both columns.
 */
public enum TypeKind {
  /** Things you can pick up. They reside in exactly one location (REQ-CORE-003). */
  PHYSICAL,

  /** Licences, files, subscriptions. They have no location and are never scanned (REQ-CORE-004). */
  DIGITAL
}
