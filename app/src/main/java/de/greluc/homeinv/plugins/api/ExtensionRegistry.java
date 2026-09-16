/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Finds the plugin that implements a port for a tenant, ready to call (04 §4.4, REQ-PLG-001).
 *
 * <h2>What comes back</h2>
 *
 * <p>An instance of the <b>{@code plugin-api} port interface</b> — {@code
 * de.greluc.homeinv.plugin.api.port.NotificationChannel}, not a channel and not a stub. A block
 * that calls a plugin therefore knows one kind of object, and the runtime decides what is behind
 * it: a gRPC stub for an out-of-process plugin, a loaded class for an in-process one (ADR-0064,
 * ADR-0065). Without that, every block would carry a second code path for the rare case, and the
 * rare case is the one nobody tests.
 *
 * <p>What comes back is already wrapped in the envelope REQ-PLG-007 requires: a deadline, a payload
 * limit, a bounded pool of its own and a circuit breaker. A caller cannot forget to apply it,
 * because there is no unwrapped instance to get hold of.
 *
 * <h2>When a plugin is available to a tenant</h2>
 *
 * <p>Registered, not disabled, and <b>this tenant has granted at least one of the capabilities the
 * manifest declares</b>. Each individual capability is then checked where it is exercised: in the
 * core when the plugin calls back (REQ-SEC-057), in the egress proxy when it opens a connection
 * (ADR-0027).
 *
 * <p>That is the rule the owner decided on 2026-09-14, and it follows from REQ-PLG-006: a manifest
 * that asks for more leaves what was granted granted, and the plugin carries on with what it has.
 * Requiring every capability instead would mean a plugin's own update disabling it for every tenant
 * until somebody had time to look.
 */
public interface ExtensionRegistry {

  /**
   * The plugin that implements this port for this tenant, or none.
   *
   * <p>Where several implement it, the one whose manifest declares the highest priority. Ties go to
   * the plugin whose id sorts first, which is arbitrary and stable — arbitrary because the
   * manifests said the same thing, stable because a resolution that changed between two calls would
   * be a bug nobody could reproduce.
   *
   * @param port the port interface from {@code de.greluc.homeinv.plugin.api.port}
   * @param tenantId whose plugins to consider
   * @param <T> the port
   * @return an implementation, or empty when no installed plugin implements this port for this
   *     tenant. Empty is the ordinary answer on an installation with no plugins, which is a
   *     complete installation and not a broken one
   */
  <T> Optional<T> lookup(Class<T> port, UUID tenantId);

  /**
   * Every plugin that implements this port for this tenant, highest priority first.
   *
   * <p>For the ports that are a chain rather than a choice: a scanned code goes to each {@code
   * CodeFormat} in turn until one claims it (REQ-PLG-010), and a notification may go out over
   * several channels.
   *
   * @param port the port interface
   * @param tenantId whose plugins to consider
   * @param <T> the port
   * @return the implementations, possibly empty
   */
  <T> List<T> lookupAll(Class<T> port, UUID tenantId);

  /**
   * The plugin that implements this port <b>for the instance itself</b>, or none (ADR-0066).
   *
   * <p>Resolved without a tenant and without reading any tenant's grants: what makes a plugin
   * available here is an <b>instance-level</b> capability grant, which only the instance operator
   * can make (ADR-0057). It exists for the obligations the deployment owes an account rather than a
   * tenant — today the security notifications of {@code REQ-NOTI-004}, which must reach somebody who
   * is a member of no tenant at all.
   *
   * <p>What comes back is wrapped in the same envelope as every other resolution, and the call it
   * makes carries {@link de.greluc.homeinv.plugin.api.CallContext.Scope#INSTANCE} and no tenant. A
   * plugin calling back into the core under it therefore reaches nothing that belongs to a tenant:
   * there is no tenant context, so every row-level policy yields zero rows (REQ-SEC-057).
   *
   * <p><b>Use it nowhere else.</b> Every other caller has a tenant and must resolve with it —
   * {@code ArchitectureRulesTest.onlyAccountNotificationsResolveAtInstanceLevel} holds that line,
   * so the second level cannot quietly become a way around the first.
   *
   * @param port the port interface from {@code de.greluc.homeinv.plugin.api.port}
   * @param <T> the port
   * @return an implementation, or empty when no installed plugin implements this port under an
   *     instance-level grant — which is the ordinary answer on an installation whose operator has
   *     granted none
   */
  <T> Optional<T> lookupForInstance(Class<T> port);
}
