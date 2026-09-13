/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.application;

import de.greluc.homeinv.identity.api.AccountAdministration;
import de.greluc.homeinv.identity.api.OperatorDirectory;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.CursorCodec;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The paged listing of instance operators.
 *
 * <p>Separate from {@link DefaultAccountAdministration} because it signs cursors and that one must
 * not: the one-shot {@code bootstrap} service depends on that bean and is given the database
 * credentials alone, so a signing key anywhere in its chain stops a fresh deployment from creating
 * its first owner. That is not hypothetical — it happened on 2026-09-13, and
 * {@code BootstrapIsolationTest} now fails the build instead of the smoke suite failing the
 * deployment.
 */
@Service
@RequiredArgsConstructor
public class DefaultOperatorDirectory implements OperatorDirectory {

  /** The cap REQ-NFR-010 puts on every page of every collection. */
  private static final int MAX_PAGE = 200;

  /** What an operator cursor is bound to, so it cannot be replayed against another listing. */
  private static final String CURSOR = "instance-operators";

  private final AppUserRepository users;
  private final CursorCodec cursors;

  @Override
  @Transactional(readOnly = true)
  public OperatorPage operators(String cursor, int limit) {
    int size = Math.clamp(limit, 1, MAX_PAGE);
    List<AppUser> rows;
    if (cursor == null || cursor.isBlank()) {
      rows = users.findInstanceOperators(Limit.of(size));
    } else {
      CursorCodec.Position from = cursors.decode(cursor, CURSOR);
      rows = users.findInstanceOperatorsAfter(from.createdAt(), from.id(), Limit.of(size));
    }

    String next = null;
    if (rows.size() == size) {
      AppUser last = rows.getLast();
      next = cursors.encode(new CursorCodec.Position(last.getCreatedAt(), last.getId()), CURSOR);
    }
    return new OperatorPage(rows.stream().map(DefaultOperatorDirectory::viewOf).toList(), next);
  }

  /**
   * The operator's view of an account.
   *
   * @param user the entity
   * @return the view, without the credential
   */
  private static AccountAdministration.AccountView viewOf(AppUser user) {
    return new AccountAdministration.AccountView(
        user.getId(),
        user.getEmail(),
        user.getDisplayName(),
        user.isInstanceOperator(),
        user.mayCreateTenants(),
        user.getTenantLimit(),
        !user.canAuthenticate());
  }
}
