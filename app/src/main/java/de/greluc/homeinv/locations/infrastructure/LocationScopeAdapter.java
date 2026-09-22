/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.infrastructure;

import de.greluc.homeinv.inventory.api.PlaceScope;
import de.greluc.homeinv.inventory.api.PlaceTree;
import de.greluc.homeinv.locations.api.LocationScope;
import de.greluc.homeinv.platform.TenantContext;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers the subtree question with the {@code ltree} path (REQ-TEN-007, 12 §12.5).
 *
 * <p>One containment test on the GiST index over {@code path}, whatever the depth between the two.
 * The alternative — walking {@code parent_id} upwards — is a query per level and gets slower the
 * deeper somebody's scope sits, which is the opposite of what a check on every read should do.
 */
@Component
@RequiredArgsConstructor
public class LocationScopeAdapter implements LocationScope, PlaceScope, PlaceTree {

  private static final String CONTAINS =
      """
      select exists (
        select 1
        from locations.location scope
        join locations.location target
          on target.tenant_id = scope.tenant_id
         and target.path <@ scope.path
        where scope.tenant_id = ?
          and scope.id = ?
          and target.id = ?
          and target.deleted_at is null
          and scope.deleted_at is null
      )
      """;

  private final JdbcClient jdbc;
  private final LocationTreeQueries tree;

  @Override
  @Transactional(readOnly = true)
  public boolean contains(UUID scopeRootId, UUID locationId) {
    if (scopeRootId == null) {
      // No scope is the whole tenant, which is what a membership without one has.
      return true;
    }
    if (locationId == null) {
      // A thing with no place is in nobody's garage. Saying `true` here would make
      // every digital item visible to every scoped role, which is the opposite of
      // what confining somebody to a place means.
      return false;
    }
    return Boolean.TRUE.equals(
        jdbc.sql(CONTAINS)
            .params(TenantContext.require(), scopeRootId, locationId)
            .query(Boolean.class)
            .single());
  }

  /**
   * {@inheritDoc}
   *
   * <p>The same question {@link #contains(UUID, UUID)} answers, under the name {@code inventory}
   * declares for it. Two ports and one implementation, because {@code locations} already depends on
   * {@code inventory} and a call the other way would close a cycle — so each block declares what it
   * needs and this class satisfies both.
   */
  @Override
  public boolean allows(UUID scopeRootId, UUID locationId) {
    return contains(scopeRootId, locationId);
  }

  @Override
  @Transactional(readOnly = true)
  public List<UUID> idsWithin(UUID scopeRootId) {
    return scopeRootId == null
        ? List.of()
        : tree.subtreeIds(TenantContext.require(), scopeRootId);
  }

  @Override
  public java.util.List<java.util.UUID> ancestorsOf(java.util.UUID locationId) {
    return tree.ancestorIds(de.greluc.homeinv.platform.TenantContext.require(), locationId);
  }

  @Override
  public java.util.List<java.util.UUID> subtreeOf(java.util.UUID locationId) {
    return tree.subtreeIds(de.greluc.homeinv.platform.TenantContext.require(), locationId);
  }

  @Override
  public String labelOf(java.util.UUID locationId) {
    // Read straight from the table rather than through `LocationService.get`,
    // which would be this block calling its own service through a port declared
    // by another -- and which throws where a report wants a null.
    return jdbc
        .sql("select name from locations.location where tenant_id = ? and id = ?")
        .param(de.greluc.homeinv.platform.TenantContext.require())
        .param(locationId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

}
