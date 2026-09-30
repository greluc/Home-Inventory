/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.infrastructure;

import de.greluc.homeinv.media.domain.UploadSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for uploads in flight (REQ-MED-008).
 *
 * <h2>Why both reads carry {@code @Transactional}</h2>
 *
 * <p>Spring Data makes its own CRUD methods transactional and leaves a {@code @Query} method alone,
 * so one runs on a connection outside any transaction — and this application sets
 * {@code app.tenant_id} when a transaction <b>begins</b> ({@code TenantAwareTransactionManager}).
 * A query without one therefore runs with no tenant set, and row-level security answers it with no
 * rows at all.
 *
 * <p>*Found by a sweep that removed nothing while the row it was looking for was plainly there. The
 * same defect was in the read the upload path uses, where an integration test had hidden it: the
 * test wrapped every call in a transaction of its own, which production does not.*
 */
public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {

  /**
   * One upload of this tenant.
   *
   * <p>Scoped by tenant in the query as well as by row-level security. Both, deliberately: the
   * policy is what makes it true and the predicate is what makes it obvious, and the two lines of
   * defence of ADR-0003 are two lines precisely because either can be got wrong.
   *
   * @param tenantId the tenant
   * @param id the upload
   * @return the session, or empty
   */
  @Transactional(readOnly = true)
  @Query("select u from UploadSession u where u.tenantId = :tenantId and u.id = :id")
  Optional<UploadSession> findOwned(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

  /**
   * The unfinished uploads of the tenant in context whose time is up, oldest first.
   *
   * @param before the moment an upload must have expired before
   * @param page how many at most, so one pass stays bounded
   * @return what the sweep should remove
   */
  @Transactional(readOnly = true)
  @Query("select u from UploadSession u where u.mediaObjectId is null and u.expiresAt <= :before "
      + "order by u.expiresAt asc")
  List<UploadSession> findExpired(@Param("before") Instant before, Pageable page);
}
