/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
/**
 * What {@code crypto} publishes.
 *
 * <p>One port, {@link de.greluc.homeinv.crypto.api.SensitiveValues}: seal a value for storage, open
 * it for a caller who may see it. Cross-cutting infrastructure with a schema of its own, like
 * {@code outbox} and {@code idempotency} — several blocks encrypt, so it belongs to none of them,
 * and it cannot live in {@code platform}, which has no schema and no database access at all
 * (ADR-0019, 07 §7.8).
 *
 * <p>The {@code @NamedInterface} is what makes this package one other blocks may depend on; without
 * it Spring Modulith would expose the module's base package instead, and this project keeps every
 * type in a sub-package (REQ-NFR-023).
 */
@org.springframework.modulith.NamedInterface("api")
package de.greluc.homeinv.crypto.api;
