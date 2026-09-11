/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.api;

import java.util.UUID;

/**
 * A resource that belongs to a tenant, as handed to {@link AccessControl} for a decision.
 *
 * <p>It is the *loaded* object, never an identifier. 04 §4.3 puts it plainly: {@code authorization}
 * never decides on data it loads itself, and resources are handed in already loaded and already
 * tenant-checked. An interface taking an id would have to fetch the row to decide, and between that
 * fetch and the caller's own the row can change — which is the time-of-check/time-of-use gap this
 * shape exists to close (REQ-SEC-024).
 */
public interface TenantOwned {

  /**
   * The tenant this resource belongs to.
   *
   * @return the tenant id
   */
  UUID tenantId();
}
