/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.media.api.BlobStore;
import de.greluc.homeinv.media.application.OrphanedBlobSweep;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A blob goes only when nothing points at it, and not at once (REQ-MED-011).
 *
 * <p>13 §13.8 has listed this weekly run since the chapter was written, and {@code MediaErasure}
 * said in a comment that it "already removes what no reference points at". It did not exist at all,
 * so {@code ref_count} fell to zero and the bytes stayed for ever. These tests are what stops that
 * being true again.
 *
 * <p>The property that matters most is the <b>grace period</b>: moving a photograph from one item
 * to another is a detach and an attach, and between them nothing points at the blob. A sweep
 * without a grace period deletes the picture somebody is in the middle of moving.
 */
@DisplayName("The orphaned blob sweep")
class OrphanedBlobSweepIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private OrphanedBlobSweep sweep;
  @Autowired private BlobStore blobs;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;

  @Test
  @DisplayName("leaves a blob alone while its grace period is running")
  void theGracePeriodProtectsAMoveInFlight() {
    Tenant tenant = newTenant("orphan-grace@example.org");
    UUID object = anUnreferencedBlob(tenant, "a".repeat(64), Instant.now());

    // Unreferenced as of now, so the week has not elapsed. Detaching a photograph
    // and reattaching it is two operations, and this is the gap between them.
    assertThat(sweep.sweep()).isZero();
    assertThat(stillThere(tenant, object)).isTrue();
  }

  @Test
  @DisplayName("removes a blob whose grace period has elapsed, bytes first")
  void anOldOrphanGoes() throws Exception {
    Tenant tenant = newTenant("orphan-old@example.org");
    String sha = "b".repeat(64);
    inOwn(
        tenant,
        () -> {
          try {
            blobs.store(
                tenant.tenantId(), sha, new java.io.ByteArrayInputStream("bytes".getBytes()));
          } catch (java.io.IOException failed) {
            throw new IllegalStateException(failed);
          }
          return null;
        });
    UUID object =
        anUnreferencedBlob(tenant, sha, Instant.now().minus(java.time.Duration.ofDays(30)));

    assertThat(sweep.sweep()).isEqualTo(1);

    // Both halves: the row and the bytes. A row without its blob is a photograph
    // that 404s for ever, so the order matters and the outcome is that neither
    // is left behind.
    assertThat(stillThere(tenant, object)).isFalse();
    assertThat(inOwn(tenant, () -> blobs.exists(tenant.tenantId(), sha))).isFalse();
  }

  @Test
  @DisplayName("never touches a blob something still points at")
  void areferencedBlobIsSafe() {
    Tenant tenant = newTenant("orphan-referenced@example.org");
    UUID object = anUnreferencedBlob(tenant, "c".repeat(64), Instant.now().minusSeconds(86_400 * 30));

    // A reference arriving puts the blob back out of reach, however long it had
    // been unreferenced -- the column and the count are written together, and
    // the check constraint refuses a row where they disagree.
    inOwn(
        tenant,
        () ->
            jdbc
                .sql(
                    """
                    update media.media_object
                       set ref_count = 1, unreferenced_since = null
                     where tenant_id = ? and id = ?
                    """)
                .param(tenant.tenantId())
                .param(object)
                .update());

    assertThat(sweep.sweep()).isZero();
    assertThat(stillThere(tenant, object)).isTrue();
  }

  @Test
  @DisplayName("never crosses a tenant, because the lookup returns ids and the sweep sets a context")
  void oneTenantsSweepLeavesAnothersAlone() {
    Tenant mine = newTenant("orphan-mine@example.org");
    Tenant theirs = newTenant("orphan-theirs@example.org");
    UUID ours = anUnreferencedBlob(mine, "d".repeat(64), Instant.now().minusSeconds(86_400 * 30));
    UUID fresh = anUnreferencedBlob(theirs, "e".repeat(64), Instant.now());

    sweep.sweep();

    assertThat(stillThere(mine, ours)).isFalse();
    // Another tenant's blob is judged by its own grace period and not by ours.
    assertThat(stillThere(theirs, fresh)).isTrue();
  }

  // -------------------------------------------------------------------------

  private boolean stillThere(Tenant tenant, UUID object) {
    return inOwn(
        tenant,
        () ->
            jdbc
                    .sql("select count(*) from media.media_object where tenant_id = ? and id = ?")
                    .param(tenant.tenantId())
                    .param(object)
                    .query(Long.class)
                    .single()
                > 0);
  }

  /**
   * A media object with nothing pointing at it since a given moment.
   *
   * <p>Written directly, because getting there through the API would mean uploading, attaching and
   * detaching — three operations to arrange a state one column describes.
   */
  private UUID anUnreferencedBlob(Tenant tenant, String sha256, Instant since) {
    return inOwn(
        tenant,
        () ->
            jdbc
                .sql(
                    """
                    insert into media.media_object
                        (tenant_id, sha256, media_type, byte_size,
                         scan_state, ref_count, unreferenced_since, created_by, updated_by)
                    values (?, ?, 'image/png', 5, 'CLEAN', 0, ?, ?, ?)
                    returning id
                    """)
                .param(tenant.tenantId())
                .param(sha256)
                .param(java.sql.Timestamp.from(since))
                .param(tenant.userId())
                .param(tenant.userId())
                .query(UUID.class)
                .single());
  }

  private <T> T inOwn(Tenant tenant, Supplier<T> body) {
    return TenantContext.callAs(tenant.tenantId(), () -> transactions.execute(status -> body.get()));
  }

  private Tenant newTenant(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Owner", "en", passwordEncoder.encode(PASSWORD), Instant.now())));
    enrolSecondFactor(userId);
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  private record Tenant(UUID userId, UUID tenantId) {}
}
