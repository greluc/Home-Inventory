/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * The published surface of the {@code identity} block.
 *
 * <p>The {@code @NamedInterface} is what makes this package other blocks may depend on, and its
 * absence is what makes {@code domain}, {@code application} and {@code infrastructure} invisible to
 * them. Spring Modulith exposes a module's base package by default; this project keeps every type
 * in a sub-package, so without this annotation the convention "only {@code api} is published" would
 * be documentation rather than a rule - and the modularity test reported exactly that on its first
 * run (REQ-NFR-023).
 */
@org.springframework.modulith.NamedInterface("api")
package de.greluc.homeinv.identity.api;
