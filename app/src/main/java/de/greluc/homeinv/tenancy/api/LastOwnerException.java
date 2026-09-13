/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.api;

/**
 * The change would leave the tenant without an owner.
 *
 * <p>Answered as {@code 409}. A tenant with no owner is not a degraded tenant but a stranded one:
 * nobody can invite into it, nobody can promote anybody, and nobody can delete it — the instance
 * operator could not either, because operating the instance grants nothing inside a tenant
 * (ADR-0057). The way to leave as the last owner is to make somebody else one first.
 */
public class LastOwnerException extends RuntimeException {

  /** Creates the refusal. */
  public LastOwnerException() {
    super(
        "A tenant keeps at least one owner. Make somebody else an owner before stepping down or "
            + "removing this one.");
  }
}
