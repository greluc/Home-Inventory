/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.api;

/**
 * How an account comes into existence on this instance (REQ-AUTH-004, 12 §12.4).
 *
 * <p>One setting, {@code HOMEINV_REGISTRATION_MODE}, and it is the operator's: no tenant can change
 * it, because a tenant that could open registration would be deciding who may exist on somebody
 * else's instance.
 */
public enum RegistrationMode {

  /**
   * The default: an invitation makes an account (REQ-TEN-004).
   *
   * <p>The invitation is single-use, time-limited and bound to the address, so whoever ends up with
   * an account was named by somebody who may invite. There is no sign-up page.
   */
  INVITE_ONLY,

  /**
   * Anybody may sign themselves up, after confirming the address.
   *
   * <p>The confirmation is the part that needs a mail sender, and the core sends no mail itself
   * (ADR-0026): it needs {@code plugin-smtp}. An instance configured for {@code open} without one
   * <b>refuses to start</b> rather than offering a sign-up that can never be completed — the same
   * treatment a missing secret gets, and for the same reason.
   */
  OPEN,

  /**
   * Nothing creates an account: not a sign-up, not an invitation.
   *
   * <p>For an instance whose accounts come from somewhere else — the operator's own provisioning,
   * or the federation of REQ-AUTH-005 once it exists. An invitation still works for somebody who
   * <em>has</em> an account: it adds them to a tenant, which creates nobody.
   */
  CLOSED
}
