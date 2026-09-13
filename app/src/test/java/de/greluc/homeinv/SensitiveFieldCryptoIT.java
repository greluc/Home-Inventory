/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.crypto.api.SensitiveValues;
import de.greluc.homeinv.crypto.infrastructure.TenantDataKeys;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the envelope does what ADR-0019 says it does (REQ-SEC-046…049).
 *
 * <p>Four properties, and three of them are about what a ciphertext refuses rather than what it
 * yields. A sealed value opens for the record, field and tenant it was written for — and for no
 * other, because the additional authenticated data binds all three. Without that, somebody with
 * write access could copy another person's licence key into a record of their own and have it
 * displayed back, which is the attack the scheme exists to stop rather than a corner case.
 *
 * <p>The fourth is rotation, both ways: the master key can be rotated without touching a
 * ciphertext, and a new data key leaves everything written under the old one readable.
 */
@DisplayName("A sealed field")
class SensitiveFieldCryptoIT extends AbstractIntegrationTest {

  @Autowired private SensitiveValues sealed;
  @Autowired private TenantDataKeys keys;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("opens for the record, the field and the tenant it was written for, and no other")
  void theAadBindsAllThree() {
    UUID tenant = newTenant("crypto-aad@example.org");
    UUID other = newTenant("crypto-aad-other@example.org");
    UUID item = UUID.randomUUID();
    UUID otherItem = UUID.randomUUID();

    String value = inTenant(tenant, () -> sealed.seal(item, "licenceKey", "not a real licence key"));

    assertThat(inTenant(tenant, () -> sealed.open(item, "licenceKey", value)))
        .isEqualTo("not a real licence key");

    // Another field of the same item.
    assertThatThrownBy(() -> inTenant(tenant, () -> sealed.open(item, "serialNumber", value)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("did not verify");

    // Another item of the same tenant. This is the copy-into-my-own-record case.
    assertThatThrownBy(() -> inTenant(tenant, () -> sealed.open(otherItem, "licenceKey", value)))
        .isInstanceOf(IllegalStateException.class);

    // Another tenant entirely, which fails twice over: its data key is not this
    // one, and the tenant id is in the authenticated data.
    assertThatThrownBy(() -> inTenant(other, () -> sealed.open(item, "licenceKey", value)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("carries the format version and the key version in its first two bytes")
  void theHeaderIsWhatTheAdrSays() {
    UUID tenant = newTenant("crypto-header@example.org");
    UUID item = UUID.randomUUID();

    String value = inTenant(tenant, () -> sealed.seal(item, "licenceKey", "x"));
    byte[] raw = Base64.getUrlDecoder().decode(value);

    assertThat(raw[0]).as("format version").isEqualTo((byte) 0x01);
    assertThat(Byte.toUnsignedInt(raw[1])).as("the tenant's first data key").isEqualTo(1);
    // Two header bytes, a 12-byte nonce, at least a 16-byte tag.
    assertThat(raw.length).isGreaterThanOrEqualTo(2 + 12 + 16);
    assertThat(sealed.isSealed(value)).isTrue();
    assertThat(sealed.isSealed("not a real licence key")).isFalse();

    // Flipping the key version is refused rather than read under another key:
    // the header is authenticated, not merely present.
    raw[1] = 2;
    String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    assertThatThrownBy(() -> inTenant(tenant, () -> sealed.open(item, "licenceKey", tampered)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("stays readable when the master key is rotated, and no ciphertext is touched")
  void aKekRotationRewrapsOnly() throws Exception {
    UUID tenant = newTenant("crypto-kek@example.org");
    UUID item = UUID.randomUUID();

    // The test profile runs with master key version 2 active and version 1
    // mounted beside it, which is the state a deployment is in during a rotation.
    String value = inTenant(tenant, () -> sealed.seal(item, "licenceKey", "before the rotation"));
    assertThat(kekVersionOf(tenant, 1)).isEqualTo(2);

    // Put the tenant's data key back under version 1, as it was before the new
    // master was mounted. Wrapped by the TEST, from the spec: a rotation that
    // only ever re-wrapped what this code wrote would prove the code agrees with
    // itself.
    byte[] material = unwrapInTest(wrappedDekOf(tenant, 1), tenant, 2);
    storeWrapped(tenant, 1, wrapInTest(material, tenant, 1), 1);
    assertThat(kekVersionOf(tenant, 1)).isEqualTo(1);

    int rewrapped = inTenant(tenant, keys::rewrapWithActiveMaster);

    assertThat(rewrapped).as("one key was under the old master").isEqualTo(1);
    assertThat(kekVersionOf(tenant, 1)).as("and is now under the new one").isEqualTo(2);

    // The whole point: the ciphertext is the same string it was -- nothing
    // rewrote a single value -- and it still opens.
    assertThat(inTenant(tenant, () -> sealed.open(item, "licenceKey", value)))
        .isEqualTo("before the rotation");
  }

  @Test
  @DisplayName("cannot be relabelled as if another master key had wrapped it")
  void aWrappedKeyCannotBeReplayed() throws Exception {
    // A tenant this process has never unwrapped a key for, so nothing is cached
    // and the row is what answers.
    UUID tenant = newTenant("crypto-relabel@example.org");
    byte[] material = new byte[32];
    new java.security.SecureRandom().nextBytes(material);

    // Wrapped under version 1 and filed as if version 2 had produced it. The
    // version is one byte of the wrapping's authenticated data, so this is a tag
    // that does not verify rather than a key read under the wrong master.
    storeWrapped(tenant, 1, wrapInTest(material, tenant, 1), 2);

    assertThatThrownBy(
            () -> inTenant(tenant, () -> sealed.seal(UUID.randomUUID(), "licenceKey", "x")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not be unwrapped");
  }

  @Test
  @DisplayName("written under a retired data key still opens under that key")
  void aDekRotationLeavesOldValuesReadable() {
    UUID tenant = newTenant("crypto-dek@example.org");
    UUID item = UUID.randomUUID();

    String underFirst = inTenant(tenant, () -> sealed.seal(item, "licenceKey", "first key"));
    assertThat(Byte.toUnsignedInt(Base64.getUrlDecoder().decode(underFirst)[1])).isEqualTo(1);

    // A rotation: issue the next version and retire the one before it. Nothing
    // rewrites the value already written -- that is what the header byte is for.
    int next =
        inTenant(
            tenant,
            () -> {
              int issued = keys.issueNext();
              keys.retire(issued - 1);
              return issued;
            });
    assertThat(next).isEqualTo(2);

    String underSecond = inTenant(tenant, () -> sealed.seal(item, "licenceKey", "second key"));
    assertThat(Byte.toUnsignedInt(Base64.getUrlDecoder().decode(underSecond)[1])).isEqualTo(2);

    // Both open, each under its own key.
    assertThat(inTenant(tenant, () -> sealed.open(item, "licenceKey", underFirst)))
        .isEqualTo("first key");
    assertThat(inTenant(tenant, () -> sealed.open(item, "licenceKey", underSecond)))
        .isEqualTo("second key");
  }

  @Test
  @DisplayName("is gone with the tenant, which leaves even an old backup holding ciphertext")
  void erasureTakesTheKeys() {
    UUID tenant = newTenant("crypto-erasure@example.org");
    UUID item = UUID.randomUUID();
    inTenant(tenant, () -> sealed.seal(item, "licenceKey", "worth money"));

    assertThat(dataKeysOf(tenant)).isEqualTo(1);
    inTenant(
        tenant,
        () -> {
          jdbc.sql("delete from crypto.tenant_data_key").update();
          return null;
        });
    assertThat(dataKeysOf(tenant)).isZero();
  }

  // -------------------------------------------------------------------------

  /**
   * The master key of one version, as the test profile mounts it.
   *
   * @param version 1 for the previous key, 2 for the active one
   * @return the key
   * @throws Exception when the file cannot be read
   */
  private static javax.crypto.spec.SecretKeySpec masterKey(int version) throws Exception {
    String file =
        version == 2
            ? "build/resources/test/db/test-data-encryption.key"
            : "build/resources/test/db/test-data-encryption-previous.key";
    byte[] material =
        Base64.getMimeDecoder()
            .decode(java.nio.file.Files.readString(java.nio.file.Path.of(file)).trim());
    return new javax.crypto.spec.SecretKeySpec(material, 0, 32, "AES");
  }

  /**
   * {@code tenantId(16) ‖ kek_version(1)}, spelled out here rather than borrowed from the code it
   * checks.
   *
   * @param tenant the tenant
   * @param version the master key version
   * @return the additional authenticated data
   */
  private static byte[] wrappingAad(UUID tenant, int version) {
    return java.nio.ByteBuffer.allocate(17)
        .putLong(tenant.getMostSignificantBits())
        .putLong(tenant.getLeastSignificantBits())
        .put((byte) version)
        .array();
  }

  private static byte[] wrapInTest(byte[] material, UUID tenant, int version) throws Exception {
    byte[] iv = new byte[12];
    new java.security.SecureRandom().nextBytes(iv);
    javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        javax.crypto.Cipher.ENCRYPT_MODE,
        masterKey(version),
        new javax.crypto.spec.GCMParameterSpec(128, iv));
    cipher.updateAAD(wrappingAad(tenant, version));
    byte[] wrapped = cipher.doFinal(material);
    return java.nio.ByteBuffer.allocate(iv.length + wrapped.length).put(iv).put(wrapped).array();
  }

  private static byte[] unwrapInTest(byte[] wrapped, UUID tenant, int version) throws Exception {
    javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        javax.crypto.Cipher.DECRYPT_MODE,
        masterKey(version),
        new javax.crypto.spec.GCMParameterSpec(128, wrapped, 0, 12));
    cipher.updateAAD(wrappingAad(tenant, version));
    return cipher.doFinal(wrapped, 12, wrapped.length - 12);
  }

  private byte[] wrappedDekOf(UUID tenant, int dekId) {
    return inTenant(
        tenant,
        () ->
            jdbc.sql("select wrapped_dek from crypto.tenant_data_key where dek_id = ?")
                .param(dekId)
                .query(byte[].class)
                .single());
  }

  private int kekVersionOf(UUID tenant, int dekId) {
    return inTenant(
        tenant,
        () ->
            jdbc.sql("select kek_version from crypto.tenant_data_key where dek_id = ?")
                .param(dekId)
                .query(Integer.class)
                .single());
  }

  private void storeWrapped(UUID tenant, int dekId, byte[] wrapped, int kekVersion) {
    inTenant(
        tenant,
        () -> {
          jdbc.sql("delete from crypto.tenant_data_key where dek_id = ?").param(dekId).update();
          return jdbc
              .sql(
                  """
                  insert into crypto.tenant_data_key (tenant_id, dek_id, wrapped_dek, kek_version)
                  values (?, ?, ?, ?)
                  """)
              .params(tenant, dekId, wrapped, kekVersion)
              .update();
        });
  }

  private int dataKeysOf(UUID tenant) {
    return inTenant(
        tenant,
        () ->
            jdbc.sql("select count(*) from crypto.tenant_data_key")
                .query(Integer.class)
                .single());
  }

  private <T> T inTenant(UUID tenantId, Supplier<T> body) {
    return TenantContext.callAs(tenantId, () -> transactions.execute(status -> body.get()));
  }

  private UUID newTenant(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    email,
                    "Sealer",
                    "en",
                    passwordEncoder.encode("irrelevant"),
                    Instant.now())));
    return provisioning.provision("Tenant of " + email, userId);
  }
}
