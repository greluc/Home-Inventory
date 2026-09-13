/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.infrastructure;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Finds which tenant a token belongs to, before any tenant context exists.
 *
 * <p>The second of the two reads in this application that run without {@code app.tenant_id}, and it
 * exists for the same reason as the first: somebody redeeming an invitation presents a token, the
 * token identifies the invitation, the invitation identifies the tenant. With no context set the
 * policy on {@code tenancy.invitation} yields nothing, so the accept could never find the tenant it
 * is about to establish.
 *
 * <p>It goes through {@code tenancy.invitation_by_token}, the {@code SECURITY DEFINER} function of
 * migration {@code V23} — the way out 07 §7.5 names, rather than {@code BYPASSRLS}, which would
 * apply to every query the process ever makes rather than to this one.
 *
 * <p>The function does <b>not</b> filter on expiry, acceptance or revocation. Those are read
 * afterwards, under the tenant context, and answered identically — a lookup that returned nothing
 * for a used token would make "no such token" and "already used" distinguishable by how far the
 * request got.
 */
@Component
@RequiredArgsConstructor
public class InvitationLookupAdapter {

  private static final String QUERY =
      "select tenant_id, invitation_id from tenancy.invitation_by_token(?)";

  private final JdbcClient jdbc;

  /**
   * Which tenant and which invitation a token hash names.
   *
   * @param tokenHash the SHA-256 of the presented token, in lower-case hexadecimal
   * @return the tenant and the invitation, or empty when no invitation has that hash
   */
  public Optional<Located> locate(String tokenHash) {
    return jdbc
        .sql(QUERY)
        .param(tokenHash)
        .query(
            (rs, rowNum) ->
                new Located(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
        .optional();
  }

  /**
   * Where an invitation lives.
   *
   * @param tenantId the tenant it is for
   * @param invitationId the invitation itself
   */
  public record Located(UUID tenantId, UUID invitationId) {}
}
