/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.api;

import java.io.Serializable;
import java.util.UUID;

/**
 * Who is making the request, and for which tenant.
 *
 * <p>This is the only source of a tenant id in the whole application. It is built when a session is
 * established, from the stored credential and the caller's memberships — never from a header, a
 * path segment or a query parameter, all three of which the caller controls. The row-level security
 * policies exist to hold when the application layer is wrong, and a tenant the client could choose
 * would make the second line agree with the first one's mistake.
 *
 * <p>{@link Serializable} because the session lives in Valkey and is shared between instances: the
 * {@code api} role scales horizontally, and a session that only worked on the instance that created
 * it would log the user out at every deployment.
 *
 * <h2>It can be null, and that is a state rather than a fault</h2>
 *
 * <p>{@code tenantId} and {@code role} are null for somebody who belongs to no tenant. Three people
 * are in that position and none of them is broken: an instance operator who administers the
 * instance without being a member of anything (ADR-0057), somebody entitled to create their first
 * tenant, and somebody who was removed from the only one they were in. Refusing them a session
 * would mean the last of those could never be invited back, because accepting an invitation for an
 * existing account requires being signed in as it.
 *
 * <p>Such a session reads nothing. {@code TenantContextFilter} sets no {@code app.tenant_id}, so
 * every row-level-security policy yields zero rows — "a missing context yields zero rows, not
 * foreign data" (ADR-0003) — and the null role holds no permission, so every endpoint that needs
 * one answers {@code 403}. What remains reachable is what is about the person rather than about a
 * tenant: their own memberships, the switch, creating a tenant, and the instance surface.
 *
 * @param userId the person, stable across tenants
 * @param tenantId the tenant this session acts for, or null when they are in none. Stage 1 lets a
 *     user switch without re-authenticating (REQ-TEN-003), which changes this value and nothing else
 * @param email the address the user logged in with, kept for logging and for the UI to show
 * @param locale the interface language, from the account
 * @param role the role held in {@code tenantId}, or null when there is no tenant
 * @param roleDefinitionId the tenant-owned role extending it (REQ-TEN-006), or null when the role
 *     is a plain built-in one
 * @param scopeLocationId the part of the location tree this session is confined to (REQ-TEN-007),
 *     or null for the whole tenant
 */
public record AuthenticatedUser(
    UUID userId,
    UUID tenantId,
    String email,
    String locale,
    String role,
    UUID roleDefinitionId,
    UUID scopeLocationId)
    implements Serializable {

  /**
   * A session over the whole tenant, whose role may be a tenant-owned one.
   *
   * @param userId the person
   * @param tenantId the tenant this session acts for, or null
   * @param email the address they signed in with
   * @param locale their interface language
   * @param role the built-in role, or null
   * @param roleDefinitionId the tenant-owned role extending it, or null
   */
  public AuthenticatedUser(
      UUID userId,
      UUID tenantId,
      String email,
      String locale,
      String role,
      UUID roleDefinitionId) {
    this(userId, tenantId, email, locale, role, roleDefinitionId, null);
  }

  /**
   * A session whose role is a plain built-in one.
   *
   * @param userId the person
   * @param tenantId the tenant this session acts for, or null
   * @param email the address they signed in with
   * @param locale their interface language
   * @param role the built-in role, or null
   */
  public AuthenticatedUser(
      UUID userId, UUID tenantId, String email, String locale, String role) {
    this(userId, tenantId, email, locale, role, null);
  }

  /**
   * The session is serialised into Valkey; a changed shape must not silently deserialise wrong.
   *
   * <p>Raised when {@code locale} was added: a session written by the previous version deserialises
   * into a record without it, and a principal whose language is silently null is worse than a
   * session the user has to establish again. Raised again when {@code roleDefinitionId} arrived
   * with REQ-TEN-006, for the sharper version of the same reason: a principal that silently lost
   * its tenant-owned role would be one quietly demoted to the role's base. And again for
   * {@code scopeLocationId}, where the failure would run the other way — a principal that lost its
   * scope would be one silently let out of the garage.
   */
  private static final long serialVersionUID = 4L;
}
