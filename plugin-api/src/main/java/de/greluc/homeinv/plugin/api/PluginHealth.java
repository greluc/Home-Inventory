/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api;

import java.util.List;

/**
 * What a plugin says about itself and about the target it talks to.
 *
 * <p>Implemented by <b>every</b> plugin; the SDK's scaffolding provides it and a plugin that opens
 * no outbound connection answers {@link HealthState#OK} with an empty check list. The core asks once
 * a minute (13 §13.7) and shows the answer in the operator's plugin list (13 §13.11).
 *
 * <h2>Why the checks are a list rather than a boolean</h2>
 *
 * <p>REQ-PLG-015 requires an outbound plugin to verify its target <b>completely</b> before it
 * serves, and to refuse service on a partial state: the folder exists <i>and</i> is writable, the
 * bucket exists <i>and</i> the agreed prefix is reachable, the mailbox authenticates <i>and</i>
 * accepts the envelope sender. A single flag cannot say which half is missing, and "it is broken"
 * is the diagnosis an operator can do least with.
 *
 * <p>A plugin whose checks do not all pass reports {@link HealthState#PROVISIONING_INCOMPLETE} and
 * <b>accepts nothing</b>. It does not serve the part that works. The failure that prevents is the
 * expensive one: writes land in the half that works, and the half that does not is discovered when
 * somebody tries to read them back.
 */
public interface PluginHealth {

  /**
   * Checks the plugin and its target.
   *
   * <p>Called on a schedule and not per request, so it may cost a round trip to the target. It must
   * not cost several: a check that takes ten seconds is a check that is still running when the next
   * one starts.
   *
   * <p>This method does not throw. An unreachable target is a state
   * ({@link HealthState#DESTINATION_UNREACHABLE}), not an exception — a health check that can fail
   * is one whose failure needs interpreting, and it would be interpreted as "unknown" exactly when
   * it mattered.
   *
   * @param context which tenant's configuration to check. A plugin configured once for the instance
   *     ignores this and answers the same for everyone
   * @return the state, and what was checked to arrive at it
   */
  Health health(CallContext context);

  /**
   * What a plugin reports about itself.
   *
   * @param state the state, from the closed set
   * @param detail one short English sentence for the operator, naming what is wrong and where.
   *     <b>Never a credential</b>, and never a tenant's data: this text goes into the operator UI
   *     and into logs
   * @param checks what was verified to arrive at the state. Empty for a plugin with no outbound
   *     target. For one that has, every check that REQ-PLG-015 requires is in here, passing or not,
   *     so that an operator sees which half is missing rather than that something is
   */
  record Health(HealthState state, String detail, List<Check> checks) {}

  /**
   * One thing that was verified.
   *
   * @param name what was checked, as a short stable key: {@code folder-exists}, {@code
   *     folder-writable}, {@code prefix-reachable}, {@code envelope-sender-accepted}
   * @param passed whether it held
   * @param detail what was found when it did not, in English. Empty when it passed
   */
  record Check(String name, boolean passed, String detail) {}
}
