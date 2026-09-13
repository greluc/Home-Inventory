/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.infrastructure;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The queries that treat the location tree as a tree.
 *
 * <p>Hand-written SQL, as ADR-0017 prescribes for tree operations: these use {@code ltree}
 * operators — {@code <@} for "is a descendant of", {@code nlevel} for depth — that JPQL has no way
 * to express. Expressing them in JPQL would mean loading rows and walking them in Java, which is
 * exactly the recursion the materialised path exists to avoid ({@code REQ-CORE-044},
 * {@code REQ-CORE-049}).
 *
 * <p>Every statement is parameterised and every one filters by tenant. Row-level security filters
 * too, and that redundancy is deliberate: these queries bypass JPA, so they also bypass the
 * repository conventions that would otherwise carry the filter.
 */
@Component
@RequiredArgsConstructor
public class LocationTreeQueries {

  private final JdbcClient jdbc;

  /**
   * The names from the root down to and including a location.
   *
   * <p>One index lookup, no recursion. The path of the target row contains the label of every
   * ancestor, so the ancestors are exactly the rows whose own path is a prefix of it — which is
   * what {@code @>} asks. Ordering by depth puts them root-first.
   *
   * @param tenantId the tenant
   * @param locationId the location whose path is wanted
   * @return the names, root first, ending with the location's own; empty when it does not exist
   */
  public List<String> ancestorNames(UUID tenantId, UUID locationId) {
    return jdbc
        .sql(
            """
            select ancestor.name
            from locations.location target
            join locations.location ancestor
              on ancestor.tenant_id = target.tenant_id
             and ancestor.path @> target.path
            where target.tenant_id = ?
              and target.id = ?
              and ancestor.deleted_at is null
            order by ancestor.depth
            """)
        .params(tenantId, locationId)
        .query(String.class)
        .list();
  }

  /**
   * The ids of a location and everything below it.
   *
   * <p>Used to list a subtree's contents. The subtree is one index range on the GiST index over
   * {@code path}, whatever its depth.
   *
   * @param tenantId the tenant
   * @param rootId the location at the top of the subtree
   * @return the ids, including {@code rootId} itself
   */
  public List<UUID> subtreeIds(UUID tenantId, UUID rootId) {
    return jdbc
        .sql(
            """
            select descendant.id
            from locations.location root
            join locations.location descendant
              on descendant.tenant_id = root.tenant_id
             and descendant.path <@ root.path
            where root.tenant_id = ?
              and root.id = ?
              and descendant.deleted_at is null
            """)
        .params(tenantId, rootId)
        .query(UUID.class)
        .list();
  }

  /**
   * How deep the deepest place under this one sits, counted from the root of the tree.
   *
   * <p>A move is judged on the deepest descendant and not on the location itself: moving a box two
   * levels down moves everything in it two levels down with it, and the ceiling is about the tree
   * rather than about the thing being moved (REQ-CORE-040).
   *
   * @param tenantId the tenant
   * @param rootId the location at the top of the subtree
   * @return the greatest depth in the subtree, which is the location's own when nothing is below it
   */
  public int deepestBelow(UUID tenantId, UUID rootId) {
    Integer depth =
        jdbc.sql(
                """
                select max(descendant.depth)
                from locations.location root
                join locations.location descendant
                  on descendant.tenant_id = root.tenant_id
                 and descendant.path <@ root.path
                where root.tenant_id = ?
                  and root.id = ?
                  and descendant.deleted_at is null
                """)
            .params(tenantId, rootId)
            .query(Integer.class)
            .optional()
            .orElse(0);
    return depth == null ? 0 : depth;
  }

  /**
   * Rewrites the paths and depths of a subtree that has moved.
   *
   * <p>One statement, and that is the point of a materialised path: the location's own label stays
   * what it was — the label is its id — so every descendant's new path is the new prefix plus
   * whatever sat below the old one. A loop over the subtree would be the same writes and a hundred
   * round trips, and would leave the tree inconsistent if it stopped half way.
   *
   * @param tenantId the tenant
   * @param rootId the location that moved
   * @param oldPath its path before the move
   * @param newPath its path after it
   * @param now the moment of the move
   * @param actor who moved it
   * @return how many places were rewritten, the moved location included
   */
  public int rewriteSubtree(
      UUID tenantId,
      UUID rootId,
      String oldPath,
      String newPath,
      java.time.Instant now,
      UUID actor) {
    return jdbc
        .sql(
            """
            update locations.location
               set path = text2ltree(? || case
                            when path = text2ltree(?) then ''
                            else '.' || ltree2text(subpath(path, nlevel(text2ltree(?))))
                          end),
                   depth = depth + (nlevel(text2ltree(?)) - nlevel(text2ltree(?))),
                   updated_at = ?,
                   updated_by = ?,
                   version = version + 1
             where tenant_id = ?
               and path <@ text2ltree(?)
               and deleted_at is null
            """)
        .params(
            newPath,
            oldPath,
            oldPath,
            newPath,
            oldPath,
            java.sql.Timestamp.from(now),
            actor,
            tenantId,
            oldPath)
        .update();
  }

  /**
   * Whether a location has any live descendant other than itself.
   *
   * <p>Asked before a deletion: removing a location that still contains places would orphan them,
   * and the tree has no way to represent an orphan.
   *
   * @param tenantId the tenant
   * @param locationId the location
   * @return {@code true} when at least one live location sits below it
   */
  public boolean hasChildren(UUID tenantId, UUID locationId) {
    Long count =
        jdbc.sql(
                """
                select count(*)
                from locations.location child
                join locations.location parent
                  on parent.tenant_id = child.tenant_id
                 and child.path <@ parent.path
                where parent.tenant_id = ?
                  and parent.id = ?
                  and child.id <> parent.id
                  and child.deleted_at is null
                """)
            .params(tenantId, locationId)
            .query(Long.class)
            .single();
    return count != null && count > 0;
  }

}
