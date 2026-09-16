/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * The published surface of the {@code notification} block.
 *
 * <p>The {@code @NamedInterface} is what makes this package one other blocks may depend on, and its
 * absence is what keeps {@code application} and {@code infrastructure} invisible to them
 * (REQ-NFR-023).
 *
 * <p>It was missing until 2026-09-16 and nothing had noticed, because until then no block called
 * this one: the queue existed and had no producer. The first caller — {@code identity}, raising the
 * security notification of a password reset — failed the modularity check immediately, which is the
 * check working rather than a problem with it.
 */
@org.springframework.modulith.NamedInterface("api")
package de.greluc.homeinv.notification.api;
