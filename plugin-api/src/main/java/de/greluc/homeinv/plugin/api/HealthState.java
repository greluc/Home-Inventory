/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api;

/**
 * What is wrong with an integration, in the operator's terms.
 *
 * <p>The set is fixed by {@code docs/reference/plugin-health-states.yaml}, which is its source of
 * truth; {@code HealthStateRegistryTest} compares the two on every build, because a state that
 * exists in one and not the other is a state an operator UI cannot render.
 *
 * <p><b>Not a {@code degradedReason}.</b> That one is for the client and says an answer is poorer
 * than usual (ADR-0039); these are for the operator and say what to go and fix. Several of these
 * collapse to the same client-visible symptom, which is the point: the client needs to know an
 * upload is queued, the operator needs to know whether a password was revoked or an allowlist is
 * wrong, and those have nothing in common except how they look from outside.
 */
public enum HealthState {

  /** Reachable, authenticated, and the last call in the observation window succeeded. */
  OK(false),

  /**
   * Installed, with no target or no credentials yet. It has never worked, as opposed to having
   * stopped working.
   *
   * <p>Not a fault: a {@code minimal} installation runs without {@code plugin-smtp} by design
   * (ADR-0028), so this is a steady state there and must not raise an alert.
   */
  NOT_CONFIGURED(false),

  /**
   * The remote target exists only in part — the folder is there but cannot be written to, the
   * bucket exists but not the agreed prefix, the mailbox authenticates but refuses the envelope
   * sender.
   *
   * <p><b>The plugin refuses service in this state rather than working partially</b> (REQ-PLG-015).
   * The failure this prevents is the expensive one: writes are accepted by the half that works, and
   * the half that does not is discovered when somebody tries to read them back.
   */
  PROVISIONING_INCOMPLETE(true),

  /**
   * Authentication failed — the app password was revoked, the key rotated out of band, the token
   * expired.
   *
   * <p>Distinct from {@link #INSUFFICIENT_PRIVILEGES} on purpose: here the remote does not know who
   * we are, there it knows and says no.
   */
  CREDENTIALS_REJECTED(true),

  /** Authenticated, and the operation was refused. A right was taken away at the remote. */
  INSUFFICIENT_PRIVILEGES(true),

  /** The host did not answer: DNS, routing, a closed port, or a TLS chain the plugin does not trust. */
  DESTINATION_UNREACHABLE(true),

  /**
   * The egress proxy refused the destination: the host is not on the manifest's allowlist, or the
   * capability was never granted for this tenant.
   *
   * <p><b>The state most likely to be misdiagnosed.</b> It presents as a connection failure and is
   * an authorisation decision — the fix is in our own configuration, not at the remote and not in
   * the network (ADR-0027).
   */
  EGRESS_DENIED(true),

  /** A name at the destination is taken by something that is not ours. The plugin never overwrites it. */
  TARGET_CONFLICT(true),

  /** The remote accepted the request and has no room. A different limit from our own per-tenant quota. */
  QUOTA_EXHAUSTED(true),

  /** The plugin speaks a major version of the contract this core does not serve (REQ-PLG-008). */
  CONTRACT_MISMATCH(true);

  /** Whether this state is something an operator has to act on. */
  private final boolean fault;

  /**
   * @param fault whether an operator has to act on it
   */
  HealthState(boolean fault) {
    this.fault = fault;
  }

  /**
   * Whether this state is something an operator has to act on.
   *
   * <p>{@link #OK} and {@link #NOT_CONFIGURED} are not: the second is a plugin nobody has
   * configured, which is a choice and not a breakage. Everything else is.
   *
   * @return {@code true} when the operator UI shows it as a fault
   */
  public boolean fault() {
    return fault;
  }

  /**
   * Whether a plugin in this state may be called at all.
   *
   * <p>{@link #PROVISIONING_INCOMPLETE} is the one that fails closed by contract: a half-built
   * target must accept nothing, because what it accepts cannot be read back (REQ-PLG-015). The
   * other faults are transient enough to be worth a retry, and the circuit breaker decides when to
   * stop trying.
   *
   * @return {@code true} when a call may be attempted
   */
  public boolean servable() {
    return this != PROVISIONING_INCOMPLETE && this != CONTRACT_MISMATCH;
  }
}
