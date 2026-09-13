/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
/**
 * What {@code tagging} publishes: the service, the views and the four events.
 *
 * <p>Everything else in this block is internal. A caller attaches a tag through {@link
 * de.greluc.homeinv.tagging.api.TagService} and reads
 * {@link de.greluc.homeinv.tagging.api.TagView}; the rows behind them stay here (REQ-NFR-020).
 *
 * <p>The {@code @NamedInterface} is what makes this package one other blocks may depend on. Without
 * it Spring Modulith exposes the module's base package instead, and this project keeps every type in
 * a sub-package — so the rule "only {@code api} is published" would be documentation rather than a
 * check (REQ-NFR-023).
 */
@org.springframework.modulith.NamedInterface("api")
package de.greluc.homeinv.tagging.api;
