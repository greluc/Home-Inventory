/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.api;

import de.greluc.homeinv.platform.Page;
import java.util.List;

/**
 * Listing the accounts that administer the instance (ADR-0057).
 *
 * <p>Its own port rather than a method on {@link AccountAdministration}, and the reason is a
 * dependency rather than a taxonomy: paging a collection means signing a cursor, signing means the
 * URL signing key, and the one-shot {@code bootstrap} service — which depends on
 * {@code AccountAdministration} — runs with the database credentials and nothing else. Keeping the
 * listing here is what lets the bootstrap need only what it is given.
 */
public interface OperatorDirectory {


  /**
   * One page of the accounts that administer the instance, oldest first.
   *
   * <p>The answer to "who can do this to us", which an operator asks before granting and an auditor
   * asks afterwards. Paged like every other collection this application answers with
   * (REQ-NFR-010): an instance is unlikely to have two pages of operators, and "unlikely" is not a
   * bound.
   *
   * @param cursor an opaque cursor from a previous page, or null for the first
   * @param limit how many at most, capped at 200
   * @return the page, with a cursor when there is more
   */
  Page<AccountAdministration.AccountView> operators(String cursor, int limit);
}
