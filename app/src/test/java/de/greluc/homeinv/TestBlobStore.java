/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import de.greluc.homeinv.media.api.BlobStore;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The blob store the integration tests use.
 *
 * <h2>Why this one is in memory and PostgreSQL is not</h2>
 *
 * <p>The substitute-database rule exists because row-level security, {@code ltree} and generated
 * {@code tsvector} columns either do not exist in a substitute or behave differently there — a
 * green test would say nothing about the property it claims to check.
 *
 * <p>None of that applies to a byte store. {@code store} then {@code open} returns the same bytes
 * or it does not, and there is no behaviour the real one has that this one lacks at this level.
 *
 * <p>What the real one does have is the mTLS hop, the certificate pin and the server-side digest
 * verification — and those are established against the actual Rust service in
 * {@link BlobStoreContractIT}, which ADR-0043 makes the reference implementation. Building and
 * starting that container for every test in the suite would add minutes to each run for properties
 * one test already proves.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestBlobStore {

  /**
   * A map from tenant and content address to bytes.
   *
   * <p>{@code @Primary}, so it wins over the gRPC client — which is still constructed, because its
   * constructor refuses without a pin and an identity and that refusal is itself under test.
   *
   * @return the in-memory store
   */
  @Bean
  @Primary
  public BlobStore inMemoryBlobStore() {
    Map<String, byte[]> stored = new ConcurrentHashMap<>();
    return new BlobStore() {

      @Override
      public boolean store(UUID tenantId, String sha256, InputStream content) throws IOException {
        return stored.putIfAbsent(key(tenantId, sha256), content.readAllBytes()) == null;
      }

      @Override
      public InputStream open(UUID tenantId, String sha256) throws IOException {
        byte[] bytes = stored.get(key(tenantId, sha256));
        if (bytes == null) {
          throw new FileNotFoundException("No such blob");
        }
        return new ByteArrayInputStream(bytes);
      }

      @Override
      public boolean exists(UUID tenantId, String sha256) {
        return stored.containsKey(key(tenantId, sha256));
      }

      @Override
      public void delete(UUID tenantId, String sha256) {
        stored.remove(key(tenantId, sha256));
      }

      // The tenant is part of the key, exactly as it is part of the path in the
      // real store: addressing is per tenant and never across (ADR-0032), and a
      // fixture that keyed on the digest alone would let a test pass that the
      // deployment would fail.
      private String key(UUID tenantId, String sha256) {
        return tenantId + "/" + sha256;
      }
    };
  }
}
