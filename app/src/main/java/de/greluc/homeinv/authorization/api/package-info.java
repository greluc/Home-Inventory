/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * What other blocks may use of {@code authorization}.
 *
 * <p>The permission catalogue, the built-in roles, the two annotations every endpoint must carry
 * one of, and {@code AccessControl} — the one place in the system where "may" is answered
 * (04 §4.3, ADR-0010).
 */
@org.springframework.modulith.NamedInterface("api")
package de.greluc.homeinv.authorization.api;
