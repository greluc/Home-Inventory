/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Creating an account.
 *
 * <p>Published for the one-shot {@code bootstrap} service, which is the only caller at stage 0:
 * there is no registration endpoint and no invitation, and an internet-facing instance that let
 * strangers create accounts would be a different product. Stage 1 adds invitations, which call this
 * from the same side of the boundary.
 *
 * <p>{@code identity.app_user} is one of the four instance-wide tables ([07 §7.1]): authentication
 * happens before any tenant is known, because the credential presented is an e-mail address and an
 * e-mail address does not name a tenant. That is what makes the check below answerable without a
 * tenant context, and it is the reason this port can be called before any tenant exists at all.
 */
public interface UserProvisioning {

  /**
   * Creates an account unless one with this address already exists.
   *
   * <p>Idempotent on purpose, and the return value is how a caller tells the two apart. The
   * {@code bootstrap} service runs on every deployment of a stack, not only the first; it must not
   * create a second owner on the second run, and it must not overwrite the password of the first —
   * an operator who changed it would find it reset by a redeploy.
   *
   * @param email the address, which is the credential
   * @param displayName what the interface calls them
   * @param locale the interface language, which the client starts in
   * @param password the plaintext, hashed here and held no longer than this call
   * @return the new user's id, or empty when the address was already taken
   */
  Optional<UUID> createIfAbsent(String email, String displayName, String locale, String password);
}
