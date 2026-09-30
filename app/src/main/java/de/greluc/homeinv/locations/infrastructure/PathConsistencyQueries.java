/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.infrastructure;

import de.greluc.homeinv.platform.TenantContext;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether the materialised path still agrees with the parent it was built from (REQ-NFR-073).
 *
 * <h2>Why this one is SQL and the attribute index is not</h2>
 *
 * <p>Because the rule is expressible: a path is the parent's path plus this row's own label, and a
 * label is the id's hex digits ({@code Location.labelOf}). Nothing about it depends on a Java type,
 * so a statement can check every row in one join — and a check that costs one query is a check that
 * can run over a whole tenant nightly.
 *
 * <p>The database already refuses the two cheapest inconsistencies with constraints:
 * {@code depth_matches_path} and {@code root_has_no_parent} (07 §7.4). What no constraint can
 * express is <i>whose</i> path a row extends, because that needs the parent's row — which is
 * exactly the drift a move introduces if it updates a subtree incompletely.
 */
@Component
@RequiredArgsConstructor
public class PathConsistencyQueries {

  private final JdbcClient jdbc;

  /**
   * Every tenant that owns a location.
   *
   * <p>Instance-wide and therefore outside any tenant context; the run sets one per tenant.
   *
   * @return the tenant ids
   */
  @Transactional(readOnly = true)
  public List<UUID> tenantsWithLocations() {
    return jdbc.sql(
            """
            select distinct tenant_id
            from locations.location
            where deleted_at is null
            order by tenant_id
            """)
        .query(UUID.class)
        .list();
  }

  /**
   * How many locations carry a path that does not follow from their parent.
   *
   * <p>Three ways a row can be wrong, and all three are one statement:
   *
   * <ul>
   *   <li>a root whose path is not its own label alone;
   *   <li>a child whose path is not its parent's path plus its own label — which includes a child
   *       whose parent is gone;
   *   <li>a row whose depth does not match the number of labels, which the constraint already
   *       refuses and which is checked anyway, because a constraint that was added later does not
   *       prove anything about rows written before it.
   * </ul>
   *
   * @return the deviation count for the tenant the context names
   */
  @Transactional(readOnly = true)
  public long deviations() {
    Long count =
        jdbc.sql(
                """
                select count(*)
                from locations.location child
                left join locations.location parent
                       on parent.tenant_id = child.tenant_id
                      and parent.id = child.parent_id
                      and parent.deleted_at is null
                where child.tenant_id = ?
                  and child.deleted_at is null
                  and (
                        (child.parent_id is null
                          and child.path::text <> replace(child.id::text, '-', ''))
                     or (child.parent_id is not null
                          and (parent.id is null
                               or child.path::text <>
                                  parent.path::text || '.' || replace(child.id::text, '-', '')))
                     or nlevel(child.path) <> child.depth + 1
                  )
                """)
            .param(TenantContext.require())
            .query(Long.class)
            .single();
    return count == null ? 0 : count;
  }

  /**
   * Which locations they are, for the log line.
   *
   * <p>Bounded: a tenant whose whole tree drifted would otherwise produce one log line per place,
   * and the first ten are enough to find the cause.
   *
   * @param limit how many at most
   * @return the ids
   */
  @Transactional(readOnly = true)
  public List<UUID> deviating(int limit) {
    return jdbc.sql(
            """
            select child.id
            from locations.location child
            left join locations.location parent
                   on parent.tenant_id = child.tenant_id
                  and parent.id = child.parent_id
                  and parent.deleted_at is null
            where child.tenant_id = ?
              and child.deleted_at is null
              and (
                    (child.parent_id is null
                      and child.path::text <> replace(child.id::text, '-', ''))
                 or (child.parent_id is not null
                      and (parent.id is null
                           or child.path::text <>
                              parent.path::text || '.' || replace(child.id::text, '-', '')))
                 or nlevel(child.path) <> child.depth + 1
              )
            order by child.id
            limit ?
            """)
        .params(TenantContext.require(), limit)
        .query(UUID.class)
        .list();
  }
}
