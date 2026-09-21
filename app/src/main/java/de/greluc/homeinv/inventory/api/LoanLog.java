/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Who has an item, and since when (REQ-LIFE-005).
 *
 * <h2>One open loan at a time</h2>
 *
 * <p>A thing is in one pair of hands. {@link #lend} on an item that is already out is refused with
 * {@link ItemLentException} rather than opening a second loan, and the refusal comes from a partial
 * unique index rather than from a check this class makes first — two requests arriving together
 * would both find no open loan, and only the database can decide between them.
 *
 * <h2>The return is an edit, not a second record</h2>
 *
 * <p>Unlike a maintenance entry ({@link MaintenanceLog}), a loan is edited: {@link #returnItem}
 * writes the date into the row that recorded the handover. That is what a loan <i>is</i> — one
 * thing that starts and ends — and two rows for it would be two rows that can disagree about
 * whether the drill came back.
 *
 * <h2>A lent item cannot be deleted</h2>
 *
 * <p>REQ-LIFE-005's acceptance criterion, and it is enforced in {@link ItemService#delete}: putting
 * something in the trash while somebody else has it would lose the only record of who to ask.
 * Returning it first is the way, and the refusal says so.
 */
public interface LoanLog {

  /**
   * Hands an item to somebody.
   *
   * @param itemId what is going out
   * @param loan to whom, since when, and back when
   * @param actor who is recording it
   * @return the open loan
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such item — which
   *     is also the answer for another tenant's item, because the two are indistinguishable from
   *     outside (REQ-SEC-025)
   * @throws ItemLentException when the item is already out
   */
  LoanView lend(UUID itemId, NewLoan loan, UUID actor);

  /**
   * Records that an item came back.
   *
   * <p>Idempotent in the outcome that matters: returning something already returned leaves the
   * first return standing rather than overwriting its date, because the first one is the true one.
   *
   * @param itemId the item
   * @param loanId which loan
   * @param returnedOn when it came back; never before the handover
   * @param actor who is recording it
   * @return the closed loan
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such item or no
   *     such loan on it
   */
  LoanView returnItem(UUID itemId, UUID loanId, LocalDate returnedOn, UUID actor);

  /**
   * The loan an item is currently out on, if it is out.
   *
   * <p>This is what "a lent item is recognisable as such" means, and it is one row rather than a
   * flag on the item: a flag would be a second answer to what this table already says, and the kind
   * that goes wrong the day a return is recorded and the flag is not.
   *
   * @param itemId the item
   * @return the open loan, or empty when the item is here
   */
  Optional<LoanView> openLoanOf(UUID itemId);

  /**
   * Who has had an item, most recent handover first.
   *
   * <p>Bounded rather than paged, for {@link MaintenanceLog#entriesOf}'s reason: a thing is lent
   * tens of times in its life, not thousands.
   *
   * @param itemId the item
   * @param limit how many at most; capped at 200 (REQ-NFR-010)
   * @return the loans, most recent handover first
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such item
   */
  List<LoanView> loansOf(UUID itemId, int limit);

  /**
   * Whether an item is out, without reading the loan.
   *
   * <p>What {@link ItemService#delete} asks before refusing. Separate from {@link #openLoanOf}
   * because the caller needs the answer and not the row, and because a deletion path that pulled a
   * borrower's name into memory in order to throw it away would be reading a person's data for
   * nothing.
   *
   * @param itemId the item
   * @return whether somebody has it
   */
  boolean isLent(UUID itemId);

  /**
   * A handover to record.
   *
   * @param borrowerUserId a member of this tenant, or {@code null} when the borrower is not one
   * @param borrowerName who has it, in the lender's own words, or {@code null} when the borrower is
   *     a member. Exactly one of the two is given — most things are lent to people with no account
   *     here, and a system that could record only members would be answered by writing the
   *     neighbour's name into the note
   * @param handedOutOn when it went out
   * @param dueOn when it is due back, or {@code null} when nothing was agreed
   * @param note anything else worth knowing, or {@code null}
   */
  record NewLoan(
      UUID borrowerUserId,
      String borrowerName,
      LocalDate handedOutOn,
      LocalDate dueOn,
      String note) {}

  /**
   * One loan, open or closed.
   *
   * @param id the loan
   * @param itemId what was lent
   * @param borrowerUserId the member who has it, or {@code null}
   * @param borrowerName the name that was typed, or {@code null}
   * @param handedOutOn when it went out
   * @param dueOn when it is due back, or {@code null}
   * @param returnedOn when it came back, or {@code null} while it is still out
   * @param note anything else worth knowing, or {@code null}
   * @param recordedAt when the handover was written down
   * @param recordedBy who wrote it down
   */
  record LoanView(
      UUID id,
      UUID itemId,
      @Nullable UUID borrowerUserId,
      @Nullable String borrowerName,
      LocalDate handedOutOn,
      @Nullable LocalDate dueOn,
      @Nullable LocalDate returnedOn,
      @Nullable String note,
      Instant recordedAt,
      UUID recordedBy) {

    /**
     * Whether the item is still out.
     *
     * @return {@code true} while no return has been recorded
     */
    public boolean isOpen() {
      return returnedOn == null;
    }

    /**
     * Whether the item is out and past its due date.
     *
     * <p>What REQ-LIFE-006's reminder asks. An open loan with no agreed date is never overdue —
     * there is nothing for it to be late against, and treating "no date" as "due immediately" would
     * make a reminder out of a lend nobody put a date on.
     *
     * @param today the day to judge it against, in the tenant's zone
     * @return {@code true} when it is out and {@code dueOn} has passed
     */
    public boolean isOverdueOn(LocalDate today) {
      return isOpen() && dueOn != null && today.isAfter(dueOn);
    }
  }
}
