/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * What is installed and what each tenant permits it (REQ-PLG-004…006, 09 §9.4).
 *
 * <p>The published surface of the {@code plugins} block: the registry of what an operator has
 * installed, and the per-tenant capability model that decides whether a plugin call may be made at
 * all. Every other block asks {@link de.greluc.homeinv.plugins.api.PluginRegistry#permits} before
 * it calls foreign code, and gets {@code false} until somebody has said yes.
 *
 * <p>The {@code @NamedInterface} is what makes this package other blocks may depend on, and its
 * absence is what makes {@code application} and {@code infrastructure} invisible to them.
 */
@org.springframework.modulith.NamedInterface("api")
package de.greluc.homeinv.plugins.api;
