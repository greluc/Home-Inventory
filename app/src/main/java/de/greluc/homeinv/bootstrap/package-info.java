/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * The one-shot that gives a fresh instance its first owner (ADR-0053).
 *
 * <p>A block of its own with nothing published, because it is orchestration and not a domain: it
 * asks {@code identity} for an account and {@code tenancy} for a tenant, and nothing asks it for
 * anything. It could not live in either of those two — {@code identity} already depends on {@code
 * tenancy.api} for the membership lookup during login, so a dependency the other way would be a
 * cycle, and Spring Modulith fails the build on one (REQ-NFR-020).
 *
 * <p>Everything in here is {@code @Profile("bootstrap")} and exists in no other role. {@code api}
 * and {@code worker} never construct it.
 */
package de.greluc.homeinv.bootstrap;
