/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.infrastructure;

import de.greluc.homeinv.locations.domain.Location;
import java.util.Optional;
import java.util.UUID;
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
}
