/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * The published surface of the {@code eventstream} block.
 *
 * <p>One interface: the streams this replica holds. The access layer owns the HTTP connection and
 * this block owns which connections exist and what reaches them, so the surface between them is
 * "register", "forget" and nothing else.
 *
 * <p>The {@code @NamedInterface} is what makes this package other blocks may depend on, and its
 * absence is what keeps {@code application} and {@code infrastructure} invisible to them
 * (REQ-NFR-023).
 */
@org.springframework.modulith.NamedInterface("api")
package de.greluc.homeinv.eventstream.api;
