/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.api;

import java.util.List;

/**
 * What a tenant may use, checked before it uses it (REQ-TEN-009).
 *
 * <p>04 §4.3 puts this port here and says why: "called <b>before</b> every creating operation …
 * that way abuse limitation is not something to bolt on later". ADR-0003 says the same from the
 * other side — with tenant creation open to anybody entitled, a quota is abuse protection, and
 * abuse protection does not work as a retrofit.
 *
 * <p>Every method acts on the tenant the session is acting for. There is no tenant parameter, and
 * there must not be: the tenant comes from the authenticated principal (REQ-SEC-004), and a guard
 * that took one would be the place that rule was broken.
 */
public interface QuotaGuard {

  /** The four things a tenant is bounded in (REQ-TEN-009). */
  enum Quota {
    /**
     * How many items the tenant holds, trashed ones included.
     *
     * <p>Trashed counts, because a trashed item is still a row and still carries its attachments;
     * it stops counting when it is purged, which is the operation that actually gives the space
     * back (REQ-CORE-009).
     */
    ITEM_COUNT,

    /** How many bytes of media the tenant stores, counted on the stored object (ADR-0032). */
    STORAGE_BYTES,

    /**
     * How many plugins the tenant has enabled.
     *
     * <p>Declared here and enforced where plugins are registered, which does not exist yet: the
     * plugin runtime is `REQ-PLG-002` and later in this stage. It is named now because the four
     * quotas are one requirement and a list that arrives one entry at a time is one nobody reviews
     * as a whole.
     */
    PLUGIN_COUNT,

    /**
     * How many API calls the tenant makes in a calendar month.
     *
     * <p>The one quota that is not a stock, and the one not held in PostgreSQL: a write per request
     * would be a write per request. It is counted in Valkey, and a restart that lost the counter
     * costs an over-generous month rather than anything durable.
     *
     * <p>Distinct from the rate limiting of `REQ-SEC-064`, which answers `429` with a
     * `Retry-After`. "Too fast, try in a moment" and "your allowance for this month is spent" are
     * two different things to tell somebody, and only one of them is worth waiting out.
     */
    API_CALLS
  }

  /**
   * One quota and where the tenant stands in it.
   *
   * @param quota which bound
   * @param used how much is in use now
   * @param permitted how much is allowed, whether from the tenant's own limit or the instance-wide
   *     default
   */
  record QuotaView(Quota quota, long used, long permitted) {}

  /**
   * Claims part of a quota, or refuses the operation.
   *
   * <p>Claims rather than checks: the usage is incremented and the new total compared, in the
   * caller's own transaction. A check followed by a write would be a race — two requests both
   * finding room and both taking it — and rolling the transaction back is what returns the claim
   * when the operation fails for any other reason.
   *
   * @param quota which bound
   * @param amount how much is being taken; one item, or a file's bytes
   * @throws QuotaExceededException when the claim would exceed what is permitted, carrying both
   *     numbers
   */
  void require(Quota quota, long amount);

  /**
   * Gives part of a quota back.
   *
   * <p>Never fails and never refuses. Clamped at zero: a counter that could go negative would hide
   * the drift that produced it, and drift is what the reconciliation of `REQ-NFR-073` exists to
   * correct.
   *
   * @param quota which bound
   * @param amount how much is being returned
   */
  void release(Quota quota, long amount);

  /**
   * Where this tenant stands in every quota.
   *
   * <p>For the administration view, and for a client that wants to warn before a limit is reached
   * rather than after. Unpaginated: there are four.
   *
   * @return one entry per quota, in the enum's order
   */
  List<QuotaView> usage();
}
