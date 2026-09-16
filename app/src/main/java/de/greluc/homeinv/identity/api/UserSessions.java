/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The sessions an account has open, and ending one of them (REQ-AUTH-009).
 *
 * <p>"The user sees their active sessions and devices and can terminate them individually", and the
 * acceptance is that a remote sign-out takes effect within ten minutes. It takes effect at once
 * here: the session is removed from the store every request reads it from, so the next request the
 * other device makes has none.
 *
 * <h2>Why a handle and not the session id</h2>
 *
 * <p>A session id <em>is</em> the credential — it is what the cookie carries. A list of them is a
 * list of working cookies, and an answer that carried one would put it into a browser's memory, a
 * proxy log and a screenshot. Each session is therefore named by a handle: a keyed hash of the id
 * that is stable for as long as the session is, and that this application can match back to the
 * session without the client ever having seen the id.
 */
public interface UserSessions {

  /**
   * The session attribute the login records the client's own name in.
   *
   * <p>Declared here rather than where it is written, because two packages have to agree on it: the
   * web layer writes it when a session begins, and this block reads it out of the store later. One
   * name in one place is what keeps the two from drifting into a list that is always empty.
   */
  String DEVICE_ATTRIBUTE = "homeinv.device";

  /** The session attribute the login records the truncated origin in, for the same reason. */
  String ORIGIN_ATTRIBUTE = "homeinv.origin";

  /**
   * One open session, as its owner sees it.
   *
   * @param handle what to call it when ending it — never the session id itself
   * @param current whether this is the session making the request, which a client shows differently
   *     and should not offer to end by accident
   * @param signedInAt when the session was established
   * @param lastSeenAt when it last made a request
   * @param device what the client called itself, shortened. A user agent and nothing more: it is
   *     what distinguishes "my phone" from "the machine at work" for somebody deciding which to end
   * @param origin where it signed in from, with the host part of the address removed —
   *     {@code 203.0.113.0/24} rather than the address itself (`REQ-PRIV-006`). Enough to tell a
   *     familiar network from a strange one, not enough to be a location history
   */
  record OpenSession(
      String handle,
      boolean current,
      Instant signedInAt,
      Instant lastSeenAt,
      String device,
      String origin) {}

  /**
   * Every session this account has open.
   *
   * @param userId the account
   * @param currentSessionId the session making the request, so it can be marked
   * @return the sessions, most recently seen first
   */
  List<OpenSession> of(UUID userId, String currentSessionId);

  /**
   * Ends one session.
   *
   * @param userId the account, which is also the scope: a handle that names somebody else's session
   *     is not found rather than refused, because nothing outside this account's own list exists as
   *     far as this call is concerned
   * @param handle which session
   * @throws de.greluc.homeinv.platform.NotFoundException when this account has no such session
   */
  void end(UUID userId, String handle);

  /**
   * Ends every session this account has open, including the one asking.
   *
   * <p>What a password reset does before it is finished (REQ-SEC-018), and what a detected refresh
   * token reuse does (REQ-SEC-016): if somebody else has the account, a new password that left
   * their session open would have changed nothing for them.
   *
   * <p>Ending none is not an error. An account that was not signed in anywhere is already in the
   * state the caller wants, and a reset asked for from a machine that never logged in is the
   * ordinary case rather than a failure.
   *
   * @param userId the account
   * @return how many sessions were ended, for the log line that says so
   */
  int endAll(UUID userId);
}
