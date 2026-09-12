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
 * @param userId the person, stable across tenants
 * @param tenantId the tenant this session is acting for. Stage 0 has one; stage 1 lets a user
 *     switch without re-authenticating (REQ-TEN-003), which changes this value and nothing else
 * @param email the address the user logged in with, kept for logging and for the UI to show
 */
public record AuthenticatedUser(
    UUID userId, UUID tenantId, String email, String locale, String role)
    implements Serializable {

  /**
   * The session is serialised into Valkey; a changed shape must not silently deserialise wrong.
   *
   * <p>Raised when {@code locale} was added: a session written by the previous version deserialises
   * into a record without it, and a principal whose language is silently null is worse than a
   * session the user has to establish again.
   */
  private static final long serialVersionUID = 2L;
}
