/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api.port;

import de.greluc.homeinv.plugin.api.CallContext;

/**
 * Asks a service whether a password is already known to attackers (ADR-0067, REQ-SEC-011).
 *
 * <h2>An addition, never a replacement</h2>
 *
 * <p>The core ships a list of breached passwords and checks it <b>always</b>, in every profile,
 * because it has no outbound route and may not call anybody for this (ADR-0026). A plugin
 * implementing this port is consulted <b>after</b> that list and can only refuse more: a plugin
 * saying a password is fine does not make it fine, and an unreachable plugin does not weaken the
 * check.
 *
 * <h2>The password never leaves as a password</h2>
 *
 * <p>What crosses this boundary is the first five hexadecimal characters of the password's SHA-1,
 * and what comes back is the suffixes the service knows for that prefix — the k-anonymity range
 * query that HIBP and its compatible services answer. The core compares the suffixes itself.
 *
 * <p>That shape is the point. A port that took the password would hand every new password of every
 * account to third-party code, and no capability grant could make that reasonable. This one lets a
 * plugin be wrong, slow or hostile without learning a single password.
 *
 * <h2>The call carries no tenant</h2>
 *
 * <p>A password is chosen where there is often no tenant to speak of — at registration, and at a
 * password reset asked for from the login page. The call is therefore an <b>instance</b> call
 * ({@link CallContext#forInstance}) under an instance-level grant (ADR-0066), and {@link
 * CallContext#tenantId()} is {@code null}.
 *
 * <p>Stage 1 (REQ-SEC-011).
 */
public interface PasswordBreachCheck {

  /**
   * Asks which known-breached passwords share a hash prefix.
   *
   * @param context who the call is for. An instance call: there is no tenant, because a password is
   *     often chosen before there is one
   * @param sha1Prefix the first <b>five</b> hexadecimal characters of the password's SHA-1, upper
   *     case. Never the password, and never a full hash
   * @return what the service knows for that prefix
   * @throws de.greluc.homeinv.plugin.api.PluginException when the service could not be reached. The
   *     caller then keeps the verdict its own list gave: an unreachable plugin weakens nothing,
   *     because it was never the floor
   */
  Range suffixesFor(CallContext context, String sha1Prefix);

  /**
   * The breached hashes a service knows for one prefix.
   *
   * @param suffixes the remaining <b>35</b> hexadecimal characters of each known hash with this
   *     prefix, upper case. Empty when the service knows none, which is an answer and not a failure
   */
  record Range(java.util.List<String> suffixes) {

    /**
     * Copies the list, so a plugin cannot change what it answered after the fact.
     *
     * @throws NullPointerException when the list is absent — an empty list is the way to say "none"
     */
    public Range {
      suffixes = java.util.List.copyOf(suffixes);
    }
  }
}
