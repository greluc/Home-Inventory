/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
/**
 * What {@code audit} publishes.
 *
 * <p>{@link de.greluc.homeinv.audit.api.RevisionLog} is the domain history of REQ-CORE-010: what a
 * thing looked like after each change, so a person can read an earlier state and put it back. The
 * append-only log with its hash chain answers a different question and is published separately.
 *
 * <p>The {@code @NamedInterface} is what makes this package one other blocks may depend on; without
 * it Spring Modulith would expose the module's base package instead, and this project keeps every
 * type in a sub-package (REQ-NFR-023).
 */
@org.springframework.modulith.NamedInterface("api")
package de.greluc.homeinv.audit.api;
