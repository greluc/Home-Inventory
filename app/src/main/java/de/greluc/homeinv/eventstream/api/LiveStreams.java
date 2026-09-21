/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.eventstream.api;

import java.util.UUID;

/**
 * The open streams this replica is holding (REQ-API-011).
 *
 * <p>What the access layer talks to. The endpoint owns the HTTP connection and this block owns
 * <b>which connections exist</b> and what reaches them — so an adapter registers a stream, forgets
 * it when the browser goes away, and is told nothing about where a nudge came from.
 *
 * <h2>The block is a nudge, not a feed</h2>
 *
 * <p>What travels is a kind and a moment. No id, no name, no field: a view re-reads what it is
 * showing through the ordinary API, which applies the ordinary permissions. Sending the id would
 * be faster and would tell a member with a location-scoped role that something they may not see
 * just changed.
 */
public interface LiveStreams {

  /**
   * Registers a stream for a tenant.
   *
   * @param tenantId whose changes it wants
   * @param stream where to send them
   * @return {@code false} when this replica already holds as many as it will for that tenant,
   *     which a caller answers by ending the stream rather than by failing the request
   */
  boolean register(UUID tenantId, Stream stream);

  /**
   * Forgets a stream that has closed.
   *
   * <p>Idempotent: a stream ends by timeout, by error or because the browser went away, and more
   * than one of those can be observed for the same connection.
   *
   * @param tenantId whose
   * @param stream which
   */
  void forget(UUID tenantId, Stream stream);

  /**
   * How many streams this replica holds for a tenant.
   *
   * @param tenantId whose
   * @return the count
   */
  int openStreams(UUID tenantId);

  /** One open response, as this block talks to it. */
  interface Stream {

    /**
     * Sends one nudge.
     *
     * @param kind what changed — {@code item}, {@code location}, {@code tag}, {@code type}, or
     *     {@code heartbeat} for the one that only keeps the connection open
     * @param at when, ISO-8601
     */
    void send(String kind, String at);
  }
}
