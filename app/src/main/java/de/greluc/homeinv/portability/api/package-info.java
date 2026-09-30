/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * The published surface of the {@code portability} block.
 *
 * <p>Two ports pointing in opposite directions, which is the whole shape of this block.
 * {@link de.greluc.homeinv.portability.api.ExportService} is what a tenant asks; {@link
 * de.greluc.homeinv.portability.api.ExportSource} is what every other block implements so that this
 * one never has to read their tables (REQ-NFR-021).
 *
 * <p>The {@code @NamedInterface} is what makes this package one other blocks may depend on, and its
 * absence is what keeps {@code application} and {@code infrastructure} invisible to them
 * (REQ-NFR-023).
 */
@org.springframework.modulith.NamedInterface("api")
package de.greluc.homeinv.portability.api;
