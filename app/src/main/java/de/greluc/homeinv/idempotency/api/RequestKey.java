/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.idempotency.api;

/**
 * One {@code Idempotency-Key} and the request it was spent on (REQ-API-005).
 *
 * <p>The two travel together because neither is enough on its own. The key alone would answer a
 * second, different request with the first one's result; the hash alone would deduplicate two
 * requests that a client deliberately sent twice.
 *
 * @param key the header, verbatim, trimmed and bounded by the adapter that read it
 * @param requestHash the SHA-256 of the request body in its canonical JSON form, lower-case hex
 */
public record RequestKey(String key, String requestHash) {}
