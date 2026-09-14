/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The fourteen extension points a plugin implements (REQ-PLG-001, 09 §9.2).
 *
 * <h2>Why they are here and not in the core</h2>
 *
 * <p>A plugin author compiles against these. Everything in this project is Apache-2.0 and depends
 * on nothing in the core, so compiling against a port does not pull the AGPL into somebody else's
 * plugin — which is what makes "license your plugin however you like" true rather than intended
 * (ADR-0018). The same separation is what lets an in-process plugin's classloader see
 * {@code de.greluc.homeinv.plugin.api} and nothing else (09 §9.7): there is nothing of the core
 * here to leak.
 *
 * <p>The core keeps its <b>own</b> ports in the blocks that need them —
 * {@code media.api.BlobStore}, {@code search.api.SearchIndex} — spelled in the core's own types.
 * An adapter per port joins the two. That is one more class per port and it is the price of the
 * paragraph above.
 *
 * <h2>What every port here has in common</h2>
 *
 * <ul>
 *   <li>Every method takes a {@link de.greluc.homeinv.plugin.api.CallContext} first. A plugin is
 *       granted per tenant, so "which tenant" is the question that decides whether a call is
 *       allowed at all — and it is answered before the call is made, never by the plugin.
 *   <li>Failure is a {@link de.greluc.homeinv.plugin.api.PluginException} with a kind that survives
 *       the process boundary as a gRPC status. A class name does not travel; a kind does.
 *   <li>Nothing here returns a URL for the core to fetch. A plugin holds the network capability and
 *       the egress allowlist, so it fetches and sends the bytes (REQ-SEC-034).
 *   <li>Six of the fourteen belong to stage 2 or 3 features and have no implementation yet. They
 *       exist because the contract is written once (ADR-0028).
 * </ul>
 */
package de.greluc.homeinv.plugin.api.port;
