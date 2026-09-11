/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.infrastructure;

import de.greluc.homeinv.media.domain.Attachment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for attachments. */
public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

  /**
   * The live attachments of one target, primary image first.
   *
   * @param tenantId the tenant
   * @param targetKind ITEM or LOCATION
   * @param targetId the target
   * @return the attachments, ordered
   */
  @Query("select a from Attachment a where a.tenantId = :tenantId "
      + "and a.targetKind = :targetKind and a.targetId = :targetId and a.deletedAt is null "
      + "order by a.primaryImage desc, a.displayOrder asc, a.createdAt asc")
  List<Attachment> findLiveFor(@Param("tenantId") UUID tenantId,
      @Param("targetKind") String targetKind, @Param("targetId") UUID targetId);

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
