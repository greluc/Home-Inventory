/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.infrastructure;

import de.greluc.homeinv.media.domain.MediaObject;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for blobs and their verdicts. */
public interface MediaObjectRepository extends JpaRepository<MediaObject, UUID> {

  /**
   * Finds a tenant's blob by content address.
   *
   * <p>How deduplication happens: the same bytes uploaded twice find the first record and cost no
   * second copy. Within the tenant only - the unique index says the same thing (ADR-0032).
   *
   * @param tenantId the tenant
   * @param sha256 the content address
   * @return the record, or empty
   */
  @Query("select m from MediaObject m where m.tenantId = :tenantId and m.sha256 = :sha256")
  Optional<MediaObject> findByHash(@Param("tenantId") UUID tenantId, @Param("sha256") String sha256);

  /**
   * Finds one of a tenant's blobs.
   *
   * @param tenantId the tenant
   * @param id the blob
   * @return the record, or empty
   */
  @Query("select m from MediaObject m where m.tenantId = :tenantId and m.id = :id "
      + "and m.deletedAt is null")
  Optional<MediaObject> findLive(@Param("tenantId") UUID tenantId, @Param("id") UUID id);
}
