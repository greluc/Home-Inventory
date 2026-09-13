/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.api;

import java.util.UUID;

/**
 * Whether an account has a second factor (REQ-AUTH-003).
 *
 * <p>A port declared here and implemented by {@code identity}, which owns the credentials. The
 * direction is what keeps the two blocks acyclic: {@code identity} already depends on this block
 * for {@link AccountEntitlements}, so a call the other way would close a cycle. The same
 * arrangement {@code tenancy.AccountRegistry} has, and the same reason.
 *
 * <p>Deliberately one question. This block decides which roles need a factor; whether an account
 * has one is a fact about a credential, and nothing here may see the credential itself.
 */
public interface SecondFactorStatus {

  /**
   * Whether the account has a confirmed second factor.
   *
   * @param userId the account
   * @return true when a confirmed authenticator exists. An enrolment that was never confirmed does
   *     not count: nobody can sign in with it, so treating it as protection would be a lock with no
   *     key in it
   */
  boolean isEnrolled(UUID userId);
}
