/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.api;

/**
 * The one place in the system where "may this caller do this" is answered (04 §4.3, ADR-0010).
 *
 * <p>REST, GraphQL and gRPC are adapters and decide nothing. They declare what an endpoint needs
 * with {@link RequiresPermission}; the decision happens here, once, for every surface — because two
 * surfaces deciding separately is two sets of rules to keep right, and the second one is always the
 * one that is wrong (REQ-SEC-022).
 */
public interface AccessControl {

  /**
   * Requires a permission, without naming a particular resource.
   *
   * <p>For operations that create something or act across the tenant — there is no object to check
   * against yet, so the role is the whole decision.
   *
   * @param permission what the operation needs
   * @throws AccessDeniedException when the caller's role does not hold it
   */
  void require(Permission permission);

  /**
   * Requires a permission on a resource that has already been loaded.
   *
   * <p>The resource is passed, not its id. 04 §4.3: this block never decides on data it loads
   * itself. Between a fetch made here and the caller's own fetch the row can change, and a decision
   * made about a different version of a row than the one acted on is the time-of-check/time-of-use
   * gap (REQ-SEC-024).
   *
   * <p>A resource belonging to another tenant is <b>not</b> an {@link AccessDeniedException}. It is
   * a {@code NotFoundException}, because a 403 confirms the resource exists and that is exactly the
   * fact a caller from another tenant must not learn (REQ-SEC-025). In practice row-level security
   * means such a row is never loaded in the first place; this check is the second line, and it
   * exists because both lines were designed to be independent (ADR-0003).
   *
   * @param permission what the operation needs
   * @param resource the loaded object being acted on
   * @throws AccessDeniedException when the role does not hold the permission
   * @throws de.greluc.homeinv.platform.NotFoundException when the resource belongs to another
   *     tenant
   */
  void require(Permission permission, TenantOwned resource);

  /**
   * Whether the caller holds a permission, without throwing.
   *
   * <p>For deciding what to offer rather than what to allow — a UI that shows a delete button
   * nobody may use is a UI that teaches people to ignore errors. It is never a substitute for
   * {@link #require}: a check that can be forgotten is one that will be.
   *
   * @param permission the permission in question
   * @return true when the caller's role holds it
   */
  boolean holds(Permission permission);
}
