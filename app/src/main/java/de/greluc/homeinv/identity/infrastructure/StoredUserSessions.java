/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.infrastructure;

import de.greluc.homeinv.identity.api.UserSessions;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.UrlSigningKey;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.session.Session;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.stereotype.Component;

/**
 * The open sessions of one account, read from the store they live in (REQ-AUTH-009).
 *
 * <h2>Where the list comes from</h2>
 *
 * <p>Spring Session keeps an index of sessions per principal, and this reads it. The index is keyed
 * on the <b>account id</b> rather than on the principal's own {@code toString} — see
 * {@link SessionIndex} — so the key in Valkey names nobody: an operator reading the store sees
 * opaque ids and not a list of who is signed in with which address.
 *
 * <h2>The handle</h2>
 *
 * <p>An HMAC of the session id under the instance's URL signing key, truncated. It is stable while
 * the session is, unguessable without the key, and matched by recomputing it over the account's own
 * sessions — so ending one is a lookup in a list the caller already has rather than a session id
 * travelling back and forth. The signing key is the one that signs cursors and media URLs
 * (`REQ-SEC-106`): all three are "prove this value came from here", and none of them is a
 * credential.
 */
@Component
@Slf4j
public class StoredUserSessions implements UserSessions {

  /** Long enough that two handles cannot collide in a list of a person's devices. */
  private static final int HANDLE_BYTES = 16;

  private final RedisIndexedSessionRepository sessions;
  private final UrlSigningKey signingKey;

  /**
   * @param sessions the session store, by its concrete type. The interface
   *     {@link org.springframework.session.FindByIndexNameSessionRepository} would say what is
   *     actually needed, and Spring will not resolve it: the bean implements it for its own session
   *     type, and the injection point's wildcard is not matched against that. The concrete type is
   *     also honest — {@code spring.session.redis.repository-type} is pinned to {@code indexed}
   *     precisely so this exists
   * @param signingKey the key the handle is computed under
   */
  public StoredUserSessions(RedisIndexedSessionRepository sessions, UrlSigningKey signingKey) {
    this.sessions = sessions;
    this.signingKey = signingKey;
  }

  @Override
  public List<OpenSession> of(UUID userId, String currentSessionId) {
    return sessions.findByPrincipalName(userId.toString()).values().stream()
        .map(session -> describe(session, currentSessionId))
        .sorted(Comparator.comparing(OpenSession::lastSeenAt).reversed())
        .toList();
  }

  @Override
  public void end(UUID userId, String handle) {
    Map<String, ? extends Session> open = sessions.findByPrincipalName(userId.toString());
    String id =
        open.keySet().stream()
            .filter(candidate -> handleOf(candidate).equals(handle))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("session", handle));
    sessions.deleteById(id);
    log.info("Account {} ended one of its sessions remotely.", userId);
  }

  /**
   * What one stored session looks like to its owner.
   *
   * @param session the stored session
   * @param currentSessionId the session making the request
   * @return the view
   */
  private OpenSession describe(Session session, String currentSessionId) {
    return new OpenSession(
        handleOf(session.getId()),
        session.getId().equals(currentSessionId),
        session.getCreationTime(),
        session.getLastAccessedTime(),
        session.getAttribute(UserSessions.DEVICE_ATTRIBUTE),
        session.getAttribute(UserSessions.ORIGIN_ATTRIBUTE));
  }

  /**
   * The handle for a session id.
   *
   * @param sessionId the id, which never leaves this class
   * @return a stable, unguessable name for it
   */
  private String handleOf(String sessionId) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(signingKey.material(), "HmacSHA256"));
      byte[] full = mac.doFinal(sessionId.getBytes(StandardCharsets.UTF_8));
      byte[] truncated = new byte[HANDLE_BYTES];
      System.arraycopy(full, 0, truncated, 0, HANDLE_BYTES);
      return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(truncated);
    } catch (java.security.GeneralSecurityException impossible) {
      // HmacSHA256 is required of every JVM. Without it no session could be named
      // at all, and the honest answer is to fail rather than to hand out an id.
      throw new IllegalStateException("HMAC-SHA-256 is unavailable in this runtime.", impossible);
    }
  }

}
