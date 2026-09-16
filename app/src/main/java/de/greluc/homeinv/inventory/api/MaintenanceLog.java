/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import de.greluc.homeinv.platform.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What was done to an item, and when (REQ-LIFE-003).
 *
 * <h2>Append-only, which is the requirement</h2>
 *
 * <p>There is no method here that edits an entry, and the database grants no {@code UPDATE} either.
 * A maintenance log is a record of what happened and is worth something only if it cannot be tidied
 * up afterwards — which is what {@code REQ-LIFE-003}'s "not retroactively editable" means.
 *
 * <p>A mistake is corrected by a <b>second entry</b>, the way a service history behaves on paper:
 * both facts stay visible, what was recorded and what it was corrected to. An entry logged against
 * the wrong item can be {@link #remove removed} — that is a deletion rather than an edit, and the
 * audit log records it.
 *
 * <h2>Where the invoice lives</h2>
 *
 * <p>Not here. An attachment is a media object whose target is the entry, exactly as a photograph's
 * target is an item, so reference counting, deduplication, the malware scan and the signed URLs are
 * the same mechanism rather than a second one. Upload it with {@code targetKind =
 * MAINTENANCE_ENTRY} and the entry's id.
 */
public interface MaintenanceLog {

  /**
   * Records a piece of work.
   *
   * @param itemId what was worked on
   * @param entry what was done
   * @param actor who is recording it
   * @return the recorded entry
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such item — which
   *     is also the answer for another tenant's item, because the two are indistinguishable from
   *     outside (REQ-SEC-025)
   */
  MaintenanceEntryView record(UUID itemId, NewMaintenanceEntry entry, UUID actor);

  /**
   * What has been done to one item, most recent work first.
   *
   * <p>Ordered by when the work was <b>done</b> rather than by when it was written down: "when was
   * it last serviced" is the question a service history answers, and somebody entering last year's
   * invoice today has not just serviced the bicycle.
   *
   * <p>Bounded rather than paged, deliberately. A thing accumulates tens of maintenance entries in
   * its life, not thousands, and a cursor over a list that short is machinery nobody needs. The
   * limit is capped at 200 like every other collection (REQ-NFR-010); an item with more than that
   * shows its 200 most recent, which is a limit worth raising the day anybody meets it.
   *
   * @param itemId the item
   * @param limit how many at most; capped at 200
   * @return the entries, most recent work first
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such item
   */
  java.util.List<MaintenanceEntryView> entriesOf(UUID itemId, int limit);

  /**
   * Removes an entry that should not be there.
   *
   * <p>For the entry logged against the wrong bicycle, not for one whose details were wrong: those
   * are corrected by recording the correction. Removing what is not there is not an error — the
   * outcome the caller wants is already true.
   *
   * <p>The <b>item</b> has to exist, though: removing an entry that is not there is harmless, but
   * answering as though it worked for an item this tenant cannot see would act on something that is
   * not there (REQ-SEC-025).
   *
   * @param itemId the item the entry belongs to
   * @param entryId the entry
   * @param actor who is removing it
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such item
   */
  void remove(UUID itemId, UUID entryId, UUID actor);

  /**
   * A piece of work to record.
   *
   * @param performedOn when it was done — a date, because nobody records the minute a chain was
   *     changed, and the entry's own creation time says when it was written down
   * @param kind what kind of work, in the tenant's own words: {@code service}, {@code Inspektion},
   *     {@code new tyres}. Free text rather than a closed set, which would be a migration every
   *     time somebody maintains a thing nobody anticipated
   * @param cost what it cost, or {@code null}. Null rather than zero for work under warranty:
   *     "nothing was paid" and "it cost 0.00" are different claims
   * @param note anything else worth knowing, or {@code null}
   */
  record NewMaintenanceEntry(LocalDate performedOn, String kind, Money cost, String note) {}

  /**
   * One recorded piece of work.
   *
   * @param id the entry
   * @param itemId what it was done to
   * @param performedOn when it was done
   * @param kind what kind of work
   * @param cost what it cost, or {@code null}
   * @param note anything else worth knowing, or {@code null}
   * @param recordedAt when the entry was written down, which is not when the work happened
   * @param recordedBy who wrote it down
   */
  record MaintenanceEntryView(
      UUID id,
      UUID itemId,
      LocalDate performedOn,
      String kind,
      Money cost,
      String note,
      Instant recordedAt,
      UUID recordedBy) {}
}
