/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * The published surface of the {@code media} block.
 *
 * <p>Notably it publishes no way to obtain raw bytes: {@code MediaView} carries signed, short-lived
 * URLs and nothing else (REQ-MED-010). A method returning a byte array would be a method that
 * bypasses the signature, the expiry and the dedicated hostname all at once.
 */
@org.springframework.modulith.NamedInterface("api")
package de.greluc.homeinv.media.api;
