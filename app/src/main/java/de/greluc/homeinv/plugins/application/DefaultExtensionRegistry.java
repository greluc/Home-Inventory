/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.application;

import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.plugin.api.PluginManifest;
import de.greluc.homeinv.plugin.api.PluginManifestReader;
import de.greluc.homeinv.plugins.api.ExtensionRegistry;
import de.greluc.homeinv.plugins.api.PluginRegistry;
import de.greluc.homeinv.plugins.infrastructure.PluginChannels;
import de.greluc.homeinv.plugins.infrastructure.PluginRegistryQueries;
import de.greluc.homeinv.plugins.infrastructure.PortAdapter;
import io.grpc.ManagedChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a port to the plugins that implement it for a tenant (REQ-PLG-001, REQ-PLG-002).
 *
 * <h2>The order of the four questions</h2>
 *
 * <p>Is it installed and enabled · does its manifest say it implements this port · has this tenant
 * consented to it · can it be reached. The first three are answered from the database and cost no
 * network; the fourth builds a channel, which is why it is last and why the first three are not
 * folded into it.
 *
 * <p>A plugin that fails the fourth is <b>skipped, not raised</b>: an unreachable plugin must not
 * take the caller with it when another implementation of the same port is installed and answering.
 * The exception surfaces only when a caller uses what came back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultExtensionRegistry implements ExtensionRegistry {

  /**
   * How many installed plugins to consider.
   *
   * <p>The same ceiling the REST listing has (REQ-NFR-010). An operator with more than two hundred
   * plugin containers has a problem this resolution is not the place to solve.
   */
  private static final int MAX_PLUGINS = 200;

  private final PluginRegistry plugins;
  private final PluginRegistryQueries registry;
  private final PluginChannels channels;
  private final PluginResilience resilience;
  private final PluginRuntimeProperties properties;

  /** One per port, injected by Spring. A port with no adapter resolves to nothing. */
  private final List<PortAdapter<?>> adapters;

  /**
   * Parsed manifests, by their digest.
   *
   * <p>The digest and not the plugin id: a plugin whose manifest changed has a new digest and
   * therefore a new entry, so a stale parse cannot outlive the document it came from.
   */
  private final Map<String, PluginManifest> manifests = new ConcurrentHashMap<>();

  @Override
  @Transactional(readOnly = true)
  public <T> Optional<T> lookup(Class<T> port, UUID tenantId) {
    return lookupAll(port, tenantId).stream().findFirst();
  }

  @Override
  @Transactional(readOnly = true)
  public <T> List<T> lookupAll(Class<T> port, UUID tenantId) {
    requireTheAmbientTenant(tenantId);

    PortAdapter<T> adapter = adapterFor(port);
    if (adapter == null) {
      // Six of the fourteen ports belong to features that ship later and have
      // no adapter yet (ADR-0064). Answering "nothing implements it" is right
      // for them, and would also be right for a port whose adapter was removed.
      return List.of();
    }

    List<Candidate> candidates = new ArrayList<>();
    for (PluginRegistry.Registration installed : plugins.installed(MAX_PLUGINS)) {
      if (installed.disabled()) {
        continue;
      }
      PluginManifest manifest = manifestOf(installed);
      if (manifest == null) {
        continue;
      }
      Optional<PluginManifest.PortBinding> binding =
          manifest.spec().implementsPorts().stream()
              .filter(declared -> port.getSimpleName().equals(declared.port()))
              .findFirst();
      if (binding.isEmpty() || !hasConsented(installed)) {
        continue;
      }
      candidates.add(new Candidate(installed, manifest, binding.get().priority()));
    }

    // Highest priority first; ties by plugin id, which is arbitrary and stable.
    // Arbitrary because the manifests said the same thing, stable because a
    // resolution that changed between two calls would be a bug nobody could
    // reproduce.
    candidates.sort(
        Comparator.comparingInt(Candidate::priority)
            .reversed()
            .thenComparing(candidate -> candidate.registration().pluginId()));

    List<T> resolved = new ArrayList<>();
    for (Candidate candidate : candidates) {
      connect(adapter, port, candidate).ifPresent(resolved::add);
    }
    return List.copyOf(resolved);
  }

  /**
   * Builds the channel and wraps the adapter in the envelope.
   *
   * @param adapter the port's adapter
   * @param port the port
   * @param candidate the plugin that implements it
   * @param <T> the port
   * @return the ready implementation, or empty when the plugin cannot be reached
   */
  private <T> Optional<T> connect(PortAdapter<T> adapter, Class<T> port, Candidate candidate) {
    String pluginId = candidate.registration().pluginId();
    Optional<PluginRegistryQueries.Connection> connection = registry.connection(pluginId);
    if (connection.isEmpty()) {
      return Optional.empty();
    }

    try {
      ManagedChannel channel =
          channels.of(pluginId, connection.get().endpoint(), connection.get().fingerprint());
      int deadline = properties.deadlineFor(requestedTimeout(candidate.manifest()));
      return Optional.of(resilience.decorate(port, adapter.adapt(channel, deadline), pluginId));
    } catch (RuntimeException unreachable) {
      // Skipped rather than raised. Another plugin may implement the same port
      // and be answering, and a caller asking for a notification channel should
      // get the one that works rather than the failure of the one that does not.
      log.warn(
          "Plugin {} implements {} and cannot be called: {}",
          pluginId,
          port.getSimpleName(),
          unreachable.getMessage());
      return Optional.empty();
    }
  }

  /**
   * What the manifest asks the deadline to be, in seconds.
   *
   * <p>{@code spec.resources} is optional — 09 §9.3 calls the whole block guidance for the
   * operator's limits — so a manifest without one asks for nothing and gets the operator's default.
   * Reading it without checking cost this its first four tests.
   *
   * @param manifest the plugin's manifest
   * @return the requested timeout, or zero when the manifest does not say
   */
  private static int requestedTimeout(PluginManifest manifest) {
    PluginManifest.Resources resources = manifest.spec().resources();
    return resources == null ? 0 : resources.timeoutSeconds();
  }

  /**
   * Whether this tenant has agreed to anything this plugin asked for.
   *
   * <p>At least one grant, which is the rule decided with the owner on 2026-09-14. Each individual
   * capability is checked where it is exercised — in the core when the plugin calls back
   * (REQ-SEC-057), in the egress proxy when it opens a connection (ADR-0027) — and requiring all of
   * them here would contradict REQ-PLG-006: a plugin update that asks for more would disable the
   * plugin for every tenant until somebody found time to look.
   *
   * <p>A plugin declaring no capabilities at all can never be used. That is deliberate and follows
   * from REQ-PLG-005: without a grant nothing is possible, and a manifest asking for nothing leaves
   * an administrator nothing to agree to.
   *
   * @param installed the registration
   * @return {@code true} when at least one capability is granted for the current tenant
   */
  private boolean hasConsented(PluginRegistry.Registration installed) {
    return !plugins.grants(installed.pluginId()).isEmpty();
  }

  /**
   * The parsed manifest of a registration.
   *
   * @param installed the registration
   * @return the manifest, or {@code null} when the stored document cannot be read — which is logged
   *     and skips the plugin rather than failing the lookup, for the same reason startup skips it
   *     (REQ-PLG-008)
   */
  private PluginManifest manifestOf(PluginRegistry.Registration installed) {
    PluginManifest cached = manifests.get(installed.manifestDigest());
    if (cached != null) {
      return cached;
    }
    Optional<String> document = registry.manifest(installed.pluginId());
    if (document.isEmpty()) {
      return null;
    }
    try {
      PluginManifest parsed =
          PluginManifestReader.read(document.get().getBytes(StandardCharsets.UTF_8));
      manifests.put(installed.manifestDigest(), parsed);
      return parsed;
    } catch (RuntimeException unreadable) {
      log.warn(
          "The stored manifest of {} cannot be read and the plugin is not resolved: {}",
          installed.pluginId(),
          unreadable.getMessage());
      return null;
    }
  }

  /**
   * The adapter for a port, or {@code null}.
   *
   * @param port the port
   * @param <T> the port
   * @return the adapter
   */
  @SuppressWarnings("unchecked")
  private <T> PortAdapter<T> adapterFor(Class<T> port) {
    for (PortAdapter<?> adapter : adapters) {
      if (adapter.port().equals(port)) {
        return (PortAdapter<T>) adapter;
      }
    }
    return null;
  }

  /**
   * Refuses a lookup for a tenant other than the one the transaction is running as.
   *
   * <p>Grants are read under row-level security, which answers to {@code app.tenant_id} and not to
   * an argument. A lookup for tenant B inside a transaction opened as tenant A would silently read
   * A's grants and hand back A's plugins under B's name — a trap that would surface as a leak
   * rather than as an error. The parameter stays because 04 §4.4 specifies it and because saying
   * which tenant a resolution is for belongs in the call; this is what keeps it honest.
   *
   * @param tenantId the tenant the caller asked for
   * @throws IllegalStateException when it is not the tenant the context is running as
   */
  private static void requireTheAmbientTenant(UUID tenantId) {
    UUID current = TenantContext.require();
    if (!current.equals(tenantId)) {
      throw new IllegalStateException(
          "A plugin lookup was made for tenant "
              + tenantId
              + " inside a context running as "
              + current
              + ". Grants are read under row-level security, so this would have returned the"
              + " context's plugins under another tenant's name. Wrap the lookup in"
              + " TenantContext.callAs instead.");
    }
  }

  /**
   * One plugin that implements the port being looked up.
   *
   * @param registration what is installed
   * @param manifest its manifest, parsed
   * @param priority what the manifest says about being preferred
   */
  private record Candidate(
      PluginRegistry.Registration registration, PluginManifest manifest, int priority) {}
}
