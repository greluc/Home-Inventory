/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.infrastructure;

import de.greluc.homeinv.locations.domain.Location;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for the location aggregate.
 *
 * <p>Writes and single-row reads only. Everything that treats the tree as a tree - the path, the
 * subtree, the contents - is hand-written SQL in {@code LocationTreeQueries}, because those are
 * {@code ltree} operators JPQL cannot express (ADR-0017).
 */
public interface LocationRepository extends JpaRepository<Location, UUID> {

  /**
   * Finds one live location of a tenant.
   *
   * @param tenantId the tenant
   * @param id the location
   * @return the location, or empty when it does not exist, is deleted, or belongs to another tenant
   */
  @Query("select l from Location l where l.tenantId = :tenantId and l.id = :id and l.deletedAt is null")
  Optional<Location> findLive(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

  /**
   * Whether a live sibling already carries this name.
   *
   * <p>The application-side half of the partial unique index {@code location_sibling_name}. The
   * index is the truth and this is what turns its violation into an answer rather than a
   * {@code 500}: lower-cased both sides, matching the index's {@code lower(name)}, and scoped to
   * live rows, matching its {@code WHERE deleted_at IS NULL}. A tombstone must not block re-using
   * the name (07 §7.1, rule 5).
   *
   * <p>{@code parentId} is null for the roots, and the comparison is written so that null matches
   * null — {@code = null} would match nothing and every root would look free.
   *
   * @param tenantId the tenant
   * @param parentId the parent, or {@code null} among the roots
   * @param name the proposed name
   * @param exclude a location to ignore, for a rename that keeps its own name; may be {@code null}
   * @return whether a different live sibling already has that name
   */
  @Query("select count(l) > 0 from Location l where l.tenantId = :tenantId "
      + "and (:parentId is null and l.parentId is null or l.parentId = :parentId) "
      + "and lower(l.name) = lower(:name) and l.deletedAt is null "
      + "and (:exclude is null or l.id <> :exclude)")
  boolean siblingNameTaken(@Param("tenantId") UUID tenantId, @Param("parentId") UUID parentId,
      @Param("name") String name, @Param("exclude") UUID exclude);

  /**
   * The first page of the tenant's live locations, oldest first.
   *
   * @param tenantId the tenant
   * @param page the size of the page; the caller bounds it
   * @return the locations
   */
  @Query("select l from Location l where l.tenantId = :tenantId and l.deletedAt is null "
      + "order by l.createdAt asc, l.id asc")
  List<Location> findLive(@Param("tenantId") UUID tenantId, Pageable page);

  /**
   * The page after a position, oldest first.
   *
   * @param tenantId the tenant
   * @param afterCreatedAt the creation time of the last row on the previous page
   * @param afterId the id of the last row on the previous page
   * @param page the size of the page
   * @return the next locations
   */
  @Query("select l from Location l where l.tenantId = :tenantId and l.deletedAt is null "
      + "and (l.createdAt > :afterCreatedAt "
      + "     or (l.createdAt = :afterCreatedAt and l.id > :afterId)) "
      + "order by l.createdAt asc, l.id asc")
  List<Location> findLiveAfter(@Param("tenantId") UUID tenantId,
      @Param("afterCreatedAt") Instant afterCreatedAt, @Param("afterId") UUID afterId,
      Pageable page);
}
