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
