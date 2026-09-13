/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.infrastructure;

import de.greluc.homeinv.authorization.api.RoleHolders;
import de.greluc.homeinv.authorization.api.RoleRef;
import de.greluc.homeinv.platform.TenantContext;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who holds a role here (REQ-AUTH-003).
 *
 * <p>The port is declared in {@code authorization} and implemented here, which is the direction
 * that already exists: this block asks that one what a role may grant, and a call the other way
 * would close a cycle.
 *
 * <p>A tenant-owned role is matched on the definition and a plain built-in role on the absence of
 * one. Somebody holding a definition that extends {@code MEMBER} does not hold {@code MEMBER}: they
 * hold something with more in it, and a grant made to the base is not a grant made to them.
 */
@Component
@RequiredArgsConstructor
public class RoleHoldersAdapter implements RoleHolders {

  private static final String BY_DEFINITION =
      "select user_id from tenancy.membership"
          + " where role_definition_id = ? and deleted_at is null";

  private static final String BY_BUILT_IN =
      "select user_id from tenancy.membership"
          + " where role = ? and role_definition_id is null and deleted_at is null";

  private final JdbcClient jdbc;

  @Override
  @Transactional(readOnly = true)
  public List<UUID> holdersOf(RoleRef role) {
    // No tenant predicate: the context is established and the policy scopes the
    // read to this tenant's memberships, which is the only scope this question
    // has an answer in.
    TenantContext.require();
    if (role.definitionId() != null) {
      return jdbc.sql(BY_DEFINITION)
          .param(role.definitionId())
          .query((rs, rowNum) -> rs.getObject("user_id", UUID.class))
          .list();
    }
    return jdbc.sql(BY_BUILT_IN)
        .param(role.builtIn())
        .query((rs, rowNum) -> rs.getObject("user_id", UUID.class))
        .list();
  }
}
