/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.crypto.infrastructure;

import de.greluc.homeinv.platform.TenantContext;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, wraps and unwraps the data key of each tenant (ADR-0019).
 *
 * <h2>The wrapping</h2>
 *
 * <p>AES-256-GCM with {@code AAD = tenantId(16) ‖ kek_version(1)}. A wrapped key therefore cannot
 * be moved to another tenant, and cannot be replayed as if a different master key version had
 * produced it — both would be a tag that does not verify.
 *
 * <h2>The cache</h2>
 *
 * <p>Unwrapped keys are held in memory, keyed by tenant and version. Not an optimisation for its
 * own sake: without it every sealed field would cost a query and an unwrap, and a page showing ten
 * licence keys would do ten of each. The map never leaves the process, and it holds only what this
 * process has already been trusted with.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TenantDataKeys {

  /** AES-256. */
  private static final int KEY_BYTES = 32;

  /** The nonce length GCM is specified for. */
  private static final int IV_BYTES = 12;

  /** The tag length in bits, and the longest GCM offers. */
  private static final int TAG_BITS = 128;

  private final JdbcClient jdbc;
  private final MasterKeys masters;
  private final SecureRandom random = new SecureRandom();

  /** Unwrapped keys, per tenant and version. */
  private final Map<String, SecretKeySpec> unwrapped = new ConcurrentHashMap<>();

  /**
   * One tenant's key and which version it is.
   *
   * @param dekId the version, one byte, as it appears in a ciphertext header
   * @param key the unwrapped key
   */
  public record DataKey(int dekId, SecretKeySpec key) {}

  /**
   * The key that encrypts from now on, issuing one if the tenant has none.
   *
   * <p>Joins the caller's transaction, so a tenant's first sealed value and the key that sealed it
   * are committed together. A key issued by a transaction that then rolled back would be a row
   * nothing references and a gap in the tenant's version sequence.
   *
   * <p>It is also where a <b>master key rotation finishes itself</b>. When the key that comes back
   * is wrapped under a version that is no longer active, it is re-wrapped here and now — one row,
   * inside the transaction that was writing anyway. A rotation therefore needs no sweep across
   * tenants, which is what an instance-wide one would need and what row-level security makes
   * impossible to do honestly: the operator mounts the new key, bumps the version, and each tenant
   * catches up the next time it writes. What tells the operator when the old key may be unmounted
   * is a count of the rows still naming it (06 §6.11.1).
   *
   * @return the active key
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public DataKey active() {
    UUID tenantId = TenantContext.require();
    Optional<Integer> current =
        jdbc.sql(
                """
                select dek_id from crypto.tenant_data_key
                where tenant_id = ? and retired_at is null
                order by dek_id desc
                limit 1
                """)
            .param(tenantId)
            .query(Integer.class)
            .optional();

    if (current.isEmpty()) {
      int issued = issue(tenantId);
      return new DataKey(issued, keyOf(tenantId, issued));
    }
    int dekId = current.get();
    rewrapWithActiveMaster();
    return new DataKey(dekId, keyOf(tenantId, dekId));
  }

  /**
   * One tenant's key of a given version, retired or not.
   *
   * <p>A retired key still decrypts. That is what makes a rotation cheap: existing values keep the
   * {@code dek_id} they were written with, and nothing has to be rewritten for them to stay
   * readable.
   *
   * @param dekId the version from the ciphertext header
   * @return the key
   * @throws IllegalStateException when this tenant has no such version
   */
  @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
  public SecretKeySpec of(int dekId) {
    return keyOf(TenantContext.require(), dekId);
  }

  /**
   * Issues the next data key for a tenant.
   *
   * <p>Public because a rotation is an operation and not a side effect: the caller that decides a
   * key has encrypted enough is the one that asks for the next one. Issuing does <b>not</b> retire
   * the previous key — see {@link #retire}.
   *
   * @return the new version
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public int issueNext() {
    return issue(TenantContext.require());
  }

  /**
   * Retires a version, so it decrypts but no longer encrypts.
   *
   * @param dekId the version to retire
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void retire(int dekId) {
    jdbc.sql(
            """
            update crypto.tenant_data_key
            set retired_at = now()
            where tenant_id = ? and dek_id = ? and retired_at is null
            """)
        .params(TenantContext.require(), dekId)
        .update();
  }

  /**
   * Re-wraps every one of this tenant's keys with the active master key version.
   *
   * <p>The KEK rotation of {@code REQ-SEC-049}: it reads each wrapped key, unwraps it with the
   * version it records, wraps it again with the active one, and writes it back. <b>No ciphertext is
   * touched</b> — that is the property the whole envelope exists for.
   *
   * @return how many keys were re-wrapped
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public int rewrapWithActiveMaster() {
    UUID tenantId = TenantContext.require();
    int active = masters.activeVersion();
    var rows =
        jdbc.sql(
                """
                select dek_id, wrapped_dek, kek_version
                from crypto.tenant_data_key
                where tenant_id = ? and kek_version <> ?
                """)
            .params(tenantId, active)
            .query(
                (rs, rowNum) ->
                    new Wrapped(
                        rs.getInt("dek_id"), rs.getBytes("wrapped_dek"), rs.getInt("kek_version")))
            .list();

    for (Wrapped row : rows) {
      byte[] material = unwrap(tenantId, row.wrapped(), row.kekVersion());
      byte[] rewrapped = wrap(tenantId, material, active);
      // Not `update`, because the application holds UPDATE on `retired_at` alone
      // and deliberately: a row here is issued, retired and replaced, never
      // edited in place. Delete and insert keeps that true while the key's
      // identity -- the tenant and the version -- stays exactly what it was.
      jdbc.sql("delete from crypto.tenant_data_key where tenant_id = ? and dek_id = ?")
          .params(tenantId, row.dekId())
          .update();
      jdbc.sql(
              """
              insert into crypto.tenant_data_key
                  (tenant_id, dek_id, wrapped_dek, kek_version)
              values (?, ?, ?, ?)
              """)
          .params(tenantId, row.dekId(), rewrapped, active)
          .update();
      unwrapped.put(cacheKey(tenantId, row.dekId()), new SecretKeySpec(material, "AES"));
    }
    if (!rows.isEmpty()) {
      log.info(
          "Re-wrapped {} data key(s) of tenant {} with master key version {}.",
          rows.size(),
          tenantId,
          active);
    }
    return rows.size();
  }

  // -------------------------------------------------------------------------

  /**
   * One row as the re-wrap reads it.
   *
   * @param dekId the version
   * @param wrapped the key under the master key
   * @param kekVersion which master key wrapped it
   */
  private record Wrapped(int dekId, byte[] wrapped, int kekVersion) {}

  /**
   * Generates and stores the next key for a tenant.
   *
   * @param tenantId the tenant
   * @return the new version
   */
  private int issue(UUID tenantId) {
    Integer highest =
        jdbc.sql("select max(dek_id) from crypto.tenant_data_key where tenant_id = ?")
            .param(tenantId)
            .query(Integer.class)
            .optional()
            .orElse(null);
    int dekId = highest == null ? 1 : highest + 1;
    if (dekId > 255) {
      throw new IllegalStateException(
          "Tenant " + tenantId + " has used all 255 data key versions. The header carries one "
              + "byte, so a 256th needs a new ciphertext format version, not a wider column.");
    }

    byte[] material = new byte[KEY_BYTES];
    random.nextBytes(material);
    int kekVersion = masters.activeVersion();

    jdbc.sql(
            """
            insert into crypto.tenant_data_key (tenant_id, dek_id, wrapped_dek, kek_version)
            values (?, ?, ?, ?)
            """)
        .params(tenantId, dekId, wrap(tenantId, material, kekVersion), kekVersion)
        .update();
    unwrapped.put(cacheKey(tenantId, dekId), new SecretKeySpec(material, "AES"));
    log.info("Issued data key version {} for tenant {}.", dekId, tenantId);
    return dekId;
  }

  /**
   * The unwrapped key, from the cache or from the row.
   *
   * @param tenantId the tenant
   * @param dekId the version
   * @return the key
   */
  private SecretKeySpec keyOf(UUID tenantId, int dekId) {
    SecretKeySpec cached = unwrapped.get(cacheKey(tenantId, dekId));
    if (cached != null) {
      return cached;
    }
    Wrapped row =
        jdbc.sql(
                """
                select dek_id, wrapped_dek, kek_version
                from crypto.tenant_data_key
                where tenant_id = ? and dek_id = ?
                """)
            .params(tenantId, dekId)
            .query(
                (rs, rowNum) ->
                    new Wrapped(
                        rs.getInt("dek_id"), rs.getBytes("wrapped_dek"), rs.getInt("kek_version")))
            .optional()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "A value claims data key version "
                            + dekId
                            + ", which this tenant does not have. Either it belongs to another "
                            + "tenant or the key row was removed while data still referenced it."));

    SecretKeySpec key = new SecretKeySpec(unwrap(tenantId, row.wrapped(), row.kekVersion()), "AES");
    unwrapped.put(cacheKey(tenantId, dekId), key);
    return key;
  }

  /**
   * Wraps a data key under a master key version.
   *
   * @param tenantId the tenant, bound into the wrapping
   * @param material the raw data key
   * @param kekVersion which master key to use
   * @return nonce followed by ciphertext and tag
   */
  private byte[] wrap(UUID tenantId, byte[] material, int kekVersion) {
    byte[] iv = new byte[IV_BYTES];
    random.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, masters.of(kekVersion), new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(wrappingAad(tenantId, kekVersion));
      byte[] sealed = cipher.doFinal(material);
      return ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
    } catch (GeneralSecurityException failed) {
      throw new IllegalStateException("A tenant data key could not be wrapped.", failed);
    }
  }

  /**
   * Unwraps a data key.
   *
   * @param tenantId the tenant, which the tag verifies
   * @param wrapped nonce followed by ciphertext and tag
   * @param kekVersion which master key wrapped it
   * @return the raw data key
   */
  private byte[] unwrap(UUID tenantId, byte[] wrapped, int kekVersion) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          masters.of(kekVersion),
          new GCMParameterSpec(TAG_BITS, wrapped, 0, IV_BYTES));
      cipher.updateAAD(wrappingAad(tenantId, kekVersion));
      return cipher.doFinal(wrapped, IV_BYTES, wrapped.length - IV_BYTES);
    } catch (GeneralSecurityException failed) {
      throw new IllegalStateException(
          "A tenant data key could not be unwrapped. Either the master key is not the one that "
              + "wrapped it, or the row was altered.",
          failed);
    }
  }

  /**
   * {@code tenantId(16) ‖ kek_version(1)}, which is what binds a wrapped key to one tenant.
   *
   * @param tenantId the tenant
   * @param kekVersion the master key version
   * @return the additional authenticated data
   */
  private static byte[] wrappingAad(UUID tenantId, int kekVersion) {
    return ByteBuffer.allocate(17)
        .putLong(tenantId.getMostSignificantBits())
        .putLong(tenantId.getLeastSignificantBits())
        .put((byte) kekVersion)
        .array();
  }

  private static String cacheKey(UUID tenantId, int dekId) {
    return tenantId + ":" + dekId;
  }
}
