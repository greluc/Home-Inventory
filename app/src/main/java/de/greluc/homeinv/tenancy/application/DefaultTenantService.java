/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.application;

import de.greluc.homeinv.authorization.api.AccountEntitlements;
import de.greluc.homeinv.tenancy.api.MembershipLookup;
import de.greluc.homeinv.tenancy.api.TenantLimitReachedException;
import de.greluc.homeinv.tenancy.api.TenantProvisioning;
import de.greluc.homeinv.tenancy.api.TenantService;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Creates a tenant for a signed-in person, within the bound their account carries (REQ-TEN-002).
 *
 * <h2>What is counted</h2>
 *
 * <p>Memberships, not ownerships. Somebody who was invited into four tenants and owns none has
 * four; the limit is on how many tenants a person is <em>in</em>, because that is what costs the
 * instance something. Counting only what they created would let one person hold an instance's
 * worth of data by being made a member of it instead.
 *
 * <h2>Why the count comes from the definer function</h2>
 *
 * <p>{@code tenancy.membership} is tenant-scoped, so counting a person's memberships from inside
 * any one tenant's context returns at most one. The same circularity the login has, and the same
 * way out: {@code tenancy.tenants_of_user}, which returns one user's own memberships and nothing
 * about anybody else's (07 §7.5).
 */
@Service
@Slf4j
public class DefaultTenantService implements TenantService {

  /**
   * The ceiling on any tenant limit, per-account or instance-wide.
   *
   * <p>REQ-NFR-010's page size. A person in more tenants than fit in one page would have a switcher
   * that silently omits some of them, so the limit is capped where the page is.
   */
  private static final int MAX_LIMIT = 200;

  private final TenantProvisioning provisioning;
  private final MembershipLookup memberships;
  private final AccountEntitlements entitlements;

  /**
   * How many tenants one account may be in, unless its own limit says otherwise.
   *
   * <p>A number and never "unlimited": an unbounded quota is not a quota, and ADR-0003 put this one
   * beside the entitlement precisely because open tenant creation without a bound is not abuse
   * protection. Zero is legal and means "none for now" — a way to stop somebody creating more
   * without taking the entitlement away and losing the record that they had it.
   */
  private final int defaultLimit;

  /**
   * @param provisioning the mechanism that writes the tenant
   * @param memberships the lookup that works without a tenant context
   * @param entitlements the account's own limit, when it has one
   * @param defaultLimit the instance-wide default, from {@code HOMEINV_TENANTS_PER_USER}
   */
  public DefaultTenantService(
      TenantProvisioning provisioning,
      MembershipLookup memberships,
      AccountEntitlements entitlements,
      @Value("${HOMEINV_TENANTS_PER_USER:10}") int defaultLimit) {
    if (defaultLimit < 0 || defaultLimit > MAX_LIMIT) {
      // Refused at startup rather than quietly clamped. A clamp would mean an
      // operator sets 500, reads back nothing to the contrary, and finds out
      // which number actually applied only when somebody hits it.
      throw new IllegalArgumentException(
          "HOMEINV_TENANTS_PER_USER is between 0 and "
              + MAX_LIMIT
              + " (REQ-NFR-010's page size), and is "
              + defaultLimit);
    }
    this.provisioning = provisioning;
    this.memberships = memberships;
    this.entitlements = entitlements;
    this.defaultLimit = defaultLimit;
  }

  @Override
  public UUID create(String name, UUID ownerUserId) {
    int permitted = entitlements.tenantLimit(ownerUserId).orElse(defaultLimit);
    long current = memberships.membershipsOf(ownerUserId).size();

    if (current >= permitted) {
      // Logged as well as refused: a person repeatedly hitting a quota is either
      // somebody who needs it raised or somebody the operator wants to know about,
      // and the two look the same from one line.
      log.info(
          "Refused a tenant for account {}: {} of {} in use.", ownerUserId, current, permitted);
      throw new TenantLimitReachedException(current, permitted);
    }

    UUID tenantId = provisioning.provision(name, ownerUserId);
    log.info("Account {} created tenant {} ({} of {} now in use).",
        ownerUserId, tenantId, current + 1, permitted);
    return tenantId;
  }
}
