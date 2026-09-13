/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.infrastructure;

import de.greluc.homeinv.identity.api.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SingleIndexResolver;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.stereotype.Component;

/**
 * What the session store indexes a session by (REQ-AUTH-009).
 *
 * <h2>Why not the default</h2>
 *
 * <p>Spring Session indexes by "principal name", which it takes from the authentication's
 * {@code getName()}. This application's principal is {@link AuthenticatedUser}, a record, so that
 * name would be its {@code toString} — the account id, the tenant, <b>and the e-mail address</b> —
 * and an index value is part of a key in Valkey. An operator with access to the store would be
 * reading a list of who is signed in, by address. The account id says the same thing to this
 * application and nothing to anybody else.
 *
 * <p>It is also what makes {@link StoredUserSessions} possible at all: the session overview asks for
 * one account's sessions, and an account is an id.
 *
 * <h2>Why it is installed in a constructor</h2>
 *
 * <p>Spring Session takes a {@code SessionRepositoryCustomizer}, and that is the idiomatic hook —
 * but its generic parameter is not matched here, so the customiser is never applied and the index
 * silently stays the default one: an overview that is always empty and no error anywhere. Setting
 * it directly on the repository is one line and cannot fail quietly.
 */
@Component
public class SessionIndex {

  /** Where Spring Security keeps the context inside a session. */
  private static final String SECURITY_CONTEXT = "SPRING_SECURITY_CONTEXT";

  /**
   * Tells the repository to index by the account id.
   *
   * @param repository the session store, which is the indexed one because
   *     {@code spring.session.data.redis.repository-type} says so
   */
  public SessionIndex(RedisIndexedSessionRepository repository) {
    repository.setIndexResolver(
        new SingleIndexResolver<Session>(
            FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME) {
          @Override
          public String resolveIndexValueFor(Session session) {
            Object context = session.getAttribute(SECURITY_CONTEXT);
            if (!(context instanceof SecurityContext security)) {
              return null;
            }
            Authentication authentication = security.getAuthentication();
            return authentication != null
                    && authentication.getPrincipal() instanceof AuthenticatedUser user
                ? user.userId().toString()
                : null;
          }
        });
  }
}
