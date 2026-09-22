/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.infrastructure;

import de.greluc.homeinv.media.api.BlobStore;
import de.greluc.homeinv.media.api.DeploymentBlobStore;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.plugin.api.CallContext;
import de.greluc.homeinv.plugin.api.PluginException;
import de.greluc.homeinv.plugins.api.ExtensionRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Where a tenant's bytes go: its own store when it has one, the deployment's otherwise
 * (REQ-MED-009, ADR-0007, ADR-0026).
 *
 * <h2>Why this exists rather than an injected choice</h2>
 *
 * <p>The store is a <b>per-tenant</b> decision and every caller in {@code media} holds one
 * {@code BlobStore}. Tenant A may keep its photographs in its own S3 bucket while tenant B uses the
 * in-deployment service, and neither the upload pipeline nor the derivative generator should know
 * that — so the choice is made here, per call, from the tenant the call is for.
 *
 * <h2>A tenant that adds a store keeps what it already had</h2>
 *
 * <p>A write goes to the plugin once one is granted. A <b>read</b> asks the plugin first and falls
 * back to the deployment's store when the plugin does not have it, and a delete removes from both.
 * Without that, granting a storage plugin would make every photograph uploaded before it disappear
 * — which is not a migration, it is a loss, and nothing would say so.
 *
 * <p>The fallback cannot return the wrong bytes: the address is the content hash within the tenant
 * (ADR-0032), so a blob found anywhere under that address is that blob.
 *
 * <h2>The in-deployment store is not a plugin</h2>
 *
 * <p>{@code blobstore} opens nothing outward and is reached directly (ADR-0043). Only a store that
 * leaves the deployment — S3, Nextcloud — is a plugin, because that is the line ADR-0026 draws.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantBlobStore implements BlobStore {

  private final DeploymentBlobStore deployment;
  private final ExtensionRegistry extensions;

  @Override
  public boolean store(UUID tenantId, String sha256, InputStream content) throws IOException {
    Optional<de.greluc.homeinv.plugin.api.port.BlobStore> plugin = pluginFor(tenantId);
    if (plugin.isEmpty()) {
      return deployment.store(tenantId, sha256, content);
    }
    return plugin.get().put(CallContext.of(tenantId), sha256, content);
  }

  @Override
  public InputStream open(UUID tenantId, String sha256) throws IOException {
    Optional<de.greluc.homeinv.plugin.api.port.BlobStore> plugin = pluginFor(tenantId);
    if (plugin.isPresent()) {
      try {
        if (plugin.get().head(CallContext.of(tenantId), sha256).isPresent()) {
          return plugin.get().get(CallContext.of(tenantId), sha256);
        }
      } catch (PluginException unreachable) {
        // Logged and then tried locally, because the alternative is a photograph
        // that exists and cannot be shown. A store that is down is a reason to
        // look in the other place, not a reason to answer "gone".
        log.warn("The storage plugin could not be asked for {}; trying the deployment's store",
            sha256, unreachable);
      }
    }
    return deployment.open(tenantId, sha256);
  }

  @Override
  public boolean exists(UUID tenantId, String sha256) {
    Optional<de.greluc.homeinv.plugin.api.port.BlobStore> plugin = pluginFor(tenantId);
    if (plugin.isPresent()) {
      try {
        if (plugin.get().head(CallContext.of(tenantId), sha256).isPresent()) {
          return true;
        }
      } catch (PluginException unreachable) {
        log.warn("The storage plugin could not be asked about {}", sha256, unreachable);
      }
    }
    return deployment.exists(tenantId, sha256);
  }

  @Override
  public void delete(UUID tenantId, String sha256) throws IOException {
    Optional<de.greluc.homeinv.plugin.api.port.BlobStore> plugin = pluginFor(tenantId);
    if (plugin.isPresent()) {
      // Both, and the plugin first. The blob may predate the plugin, and a
      // deletion that removed it from one place would leave the other holding
      // bytes the tenant asked to be rid of — which is the one direction
      // REQ-PRIV-004 does not allow.
      plugin.get().delete(CallContext.of(tenantId), sha256);
    }
    deployment.delete(tenantId, sha256);
  }

  /**
   * The storage plugin this tenant granted, if any.
   *
   * <p>Resolved per call rather than cached: a grant is withdrawn in the administration surface and
   * the next upload should go where the tenant now says, not where it said when this bean was
   * built. The resolution is four database questions and a channel that already exists
   * (ADR-0065).
   *
   * <h2>The call must already be running as this tenant</h2>
   *
   * <p>Grants are read under row-level security, which answers to {@code app.tenant_id} and not to
   * an argument, so the ambient context decides which rows come back. Every caller in {@code media}
   * establishes it; a call that arrives without one, or as somebody else, is refused here with a
   * sentence naming the reason rather than at the registry, whose message is about authentication
   * filters and sends the reader to the wrong place.
   *
   * @param tenantId whose store
   * @return the plugin, or empty when this tenant uses the deployment's
   * @throws IllegalStateException when the ambient tenant is absent or is another one
   */
  private Optional<de.greluc.homeinv.plugin.api.port.BlobStore> pluginFor(UUID tenantId) {
    UUID ambient = TenantContext.current().orElse(null);
    if (!tenantId.equals(ambient)) {
      throw new IllegalStateException(
          "A blob store call for tenant "
              + tenantId
              + " is running as "
              + ambient
              + ". Which store holds a tenant's bytes depends on that tenant's plugin grants, and"
              + " grants are read under row-level security — so the call has to run as the tenant"
              + " whose blob it is.");
    }
    return extensions.lookup(de.greluc.homeinv.plugin.api.port.BlobStore.class, tenantId);
  }
}
