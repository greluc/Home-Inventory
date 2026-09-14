/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.api;

import java.time.Instant;

/**
 * A plugin failed often enough that the core stopped calling it (04 §4.4, REQ-PLG-007).
 *
 * <p>Raised when a breaker opens, not on every failed call: one event per outage rather than one
 * per request is what an operator can act on, and it is what keeps a failing plugin from filling
 * the event log with the evidence of its own failure.
 *
 * <p>Not externalised. It is the core's own signal, for the metric and the operator view; a plugin
 * has no business being told that the core gave up on it, and a queue that survived a restart would
 * replay an outage that is over.
 *
 * @param pluginId which plugin
 * @param port which port it was being called through, so that an operator reading the event knows
 *     what stopped working rather than only which container
 * @param failurePercent the percentage of calls in the window that failed, as the breaker
 *     measured it, rounded to a whole percent. Whole because it is read by a person and
 *     because a {@code float} field is forbidden here (ADR-0025)
 * @param openedAt when it opened
 */
public record PluginCircuitOpened(
    String pluginId, String port, int failurePercent, Instant openedAt) {}
