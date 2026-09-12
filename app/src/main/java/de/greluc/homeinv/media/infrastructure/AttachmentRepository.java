/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.infrastructure;

import de.greluc.homeinv.media.domain.Attachment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for attachments. */
public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

  /**
   * The first page of live attachments of one target, oldest first.
   *
   * @param tenantId the tenant
   * @param targetKind ITEM or LOCATION
   * @param targetId the target
   * @param page the size of the page; the caller bounds it
   * @return the attachments, ordered by upload time and then by id
   */
  @Query("select a from Attachment a where a.tenantId = :tenantId "
      + "and a.targetKind = :targetKind and a.targetId = :targetId and a.deletedAt is null "
      + "order by a.createdAt asc, a.id asc")
  List<Attachment> findLiveFor(@Param("tenantId") UUID tenantId,
      @Param("targetKind") String targetKind, @Param("targetId") UUID targetId, Pageable page);

  /**
   * The page after a position, oldest first.
   *
   * <p>A keyset predicate and not an offset: an attachment added or detached while a client pages
   * would shift every later row under an offset, so a page would repeat or skip
   * ({@code REQ-SRCH-009}). The id breaks the tie for two uploads in the same instant, which
   * matters because {@code createdAt} alone is not unique.
   *
   * @param tenantId the tenant
   * @param targetKind ITEM or LOCATION
   * @param targetId the target
   * @param afterCreatedAt the upload time of the last row on the previous page
   * @param afterId the id of the last row on the previous page
   * @param page the size of the page
   * @return the next attachments
   */
  @Query("select a from Attachment a where a.tenantId = :tenantId "
      + "and a.targetKind = :targetKind and a.targetId = :targetId and a.deletedAt is null "
      + "and (a.createdAt > :afterCreatedAt "
      + "     or (a.createdAt = :afterCreatedAt and a.id > :afterId)) "
      + "order by a.createdAt asc, a.id asc")
  List<Attachment> findLiveForAfter(@Param("tenantId") UUID tenantId,
      @Param("targetKind") String targetKind, @Param("targetId") UUID targetId,
      @Param("afterCreatedAt") Instant afterCreatedAt, @Param("afterId") UUID afterId,
      Pageable page);

  /**
   * Whether this target already has a live attachment marked primary.
   *
   * @param tenantId the tenant
   * @param targetKind ITEM or LOCATION
   * @param targetId the target
   * @return {@code true} when one exists
   */
  @Query("select count(a) > 0 from Attachment a where a.tenantId = :tenantId "
      + "and a.targetKind = :targetKind and a.targetId = :targetId "
      + "and a.primaryImage = true and a.deletedAt is null")
  boolean hasPrimary(@Param("tenantId") UUID tenantId, @Param("targetKind") String targetKind,
      @Param("targetId") UUID targetId);

  /**
   * One live attachment of a file to a target.
   *
   * @param tenantId the tenant
   * @param mediaObjectId the file
   * @param targetKind ITEM or LOCATION
   * @param targetId the target
   * @return the attachment, or empty
   */
  @Query("select a from Attachment a where a.tenantId = :tenantId "
      + "and a.mediaObjectId = :mediaObjectId and a.targetKind = :targetKind "
      + "and a.targetId = :targetId and a.deletedAt is null")
  Optional<Attachment> findLive(@Param("tenantId") UUID tenantId,
      @Param("mediaObjectId") UUID mediaObjectId, @Param("targetKind") String targetKind,
      @Param("targetId") UUID targetId);
}
