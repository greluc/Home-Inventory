/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
/**
 * What {@code idempotency} publishes.
 *
 * <p>One port, {@link de.greluc.homeinv.idempotency.api.IdempotentRequests}, and the refusal that
 * goes with it. Cross-cutting infrastructure with a schema of its own, like {@code outbox}: it
 * belongs to no building block, because every block writes it inside its own transaction, and it
 * cannot live in {@code platform}, which has no schema and no database access at all (07 §7.8).
 *
 * <p>The {@code @NamedInterface} is what makes this package one other blocks may depend on; without
 * it Spring Modulith would expose the module's base package instead, and this project keeps every
 * type in a sub-package (REQ-NFR-023).
 */
@org.springframework.modulith.NamedInterface("api")
package de.greluc.homeinv.idempotency.api;
