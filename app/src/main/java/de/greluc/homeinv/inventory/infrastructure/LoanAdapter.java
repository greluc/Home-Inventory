/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

import de.greluc.homeinv.inventory.api.ItemLentException;
import de.greluc.homeinv.inventory.api.LoanLog;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lending, in SQL (REQ-LIFE-005).
 *
 * <p>The one invariant worth stating: <b>the database decides whether an item is free</b>, through
 * the partial unique index over the open loans. This class inserts and lets that index refuse, so
 * that two requests arriving together cannot both succeed — a check made first and trusted would be
 * exactly the race the index exists to lose.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoanAdapter implements LoanLog {

  /** The cap REQ-NFR-010 puts on every collection. */
  private static final int MAX_PAGE = 200;

  /**
   * The list, most recent handover first.
   *
   * <p>Written out rather than assembled from a shared column list, for the reason {@code
   * MaintenanceLogAdapter} gives: {@code ArchitectureRulesTest} refuses a SQL literal joined by
   * {@code +} to anything, because that is the one shape through which a value can enter a
   * statement (REQ-SEC-031).
   */
  private static final String LOANS =
      """
      select id, item_id, borrower_user_id, borrower_name, handed_out_on, due_on,
             returned_on, note, created_at, created_by
      from inventory.loan
      where tenant_id = ? and item_id = ?
      order by handed_out_on desc, created_at desc, id desc
      limit ?
      """;

  /** The open one, of which there is at most one. */
  private static final String OPEN_LOAN =
      """
      select id, item_id, borrower_user_id, borrower_name, handed_out_on, due_on,
             returned_on, note, created_at, created_by
      from inventory.loan
      where tenant_id = ? and item_id = ? and returned_on is null
      """;

  /** One loan, for the answer a handover or a return gives back. */
  private static final String ONE_LOAN =
      """
      select id, item_id, borrower_user_id, borrower_name, handed_out_on, due_on,
             returned_on, note, created_at, created_by
      from inventory.loan
      where tenant_id = ? and id = ?
      """;

  private final JdbcClient jdbc;
  private final ItemRepository items;
  private final Clock clock;
  private final org.springframework.context.ApplicationEventPublisher events;

  @Override
  @Transactional
  public LoanView lend(UUID itemId, NewLoan loan, UUID actor) {
    UUID tenantId = TenantContext.require();
    // The aggregate decides whether it can go out: it refuses what is already
    // lent, and what was trashed or sold. Doing it here rather than after the
    // insert means the state and the row move together in one transaction, so
    // "is it lent" cannot have two answers (04 §4.4).
    de.greluc.homeinv.inventory.domain.Item item = requireItem(tenantId, itemId);
    item.lend(actor, Instant.now(clock));
    items.flush();

    UUID id;
    try {
      id =
          jdbc.sql(
                  """
                  insert into inventory.loan
                      (tenant_id, item_id, borrower_user_id, borrower_name, handed_out_on,
                       due_on, note, created_by, updated_by)
                  values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                  returning id
                  """)
              .param(tenantId)
              .param(itemId)
              .param(loan.borrowerUserId())
              .param(loan.borrowerName() == null ? null : loan.borrowerName().strip())
              .param(loan.handedOutOn())
              .param(loan.dueOn())
              .param(loan.note())
              .param(actor)
              .param(actor)
              .query(UUID.class)
              .single();
    } catch (DuplicateKeyException alreadyOut) {
      // The partial unique index refusing, rather than a check this method made
      // and trusted. A caller does the same thing about either cause, so the
      // ordinary case and the race get the same sentence.
      throw new ItemLentException("This item is already lent out. Record its return first.");
    }

    events.publishEvent(
        new de.greluc.homeinv.inventory.api.ItemLent(tenantId, itemId, id, loan.dueOn()));
    log.info("Item {} was lent out", itemId);
    return byId(tenantId, id);
  }

  @Override
  @Transactional
  public LoanView returnItem(UUID itemId, UUID loanId, LocalDate returnedOn, UUID actor) {
    UUID tenantId = TenantContext.require();
    de.greluc.homeinv.inventory.domain.Item item = requireItem(tenantId, itemId);

    // Scoped by item as well as by id: a loan id belonging to a different item is
    // not this item's loan, and closing it would act on something the path did
    // not name (REQ-SEC-025).
    int closed =
        jdbc.sql(
                """
                update inventory.loan
                   set returned_on = ?,
                       version = version + 1,
                       updated_at = now(),
                       updated_by = ?
                 where tenant_id = ? and id = ? and item_id = ? and returned_on is null
                """)
            .param(returnedOn)
            .param(actor)
            .param(tenantId)
            .param(loanId)
            .param(itemId)
            .update();

    LoanView loan =
        loanById(tenantId, loanId)
            .filter(found -> found.itemId().equals(itemId))
            .orElseThrow(() -> new NotFoundException("loan", loanId));

    // Nothing was closed because it was closed already: the first return is the
    // true one and stands. Not an error -- what the caller wants is already so.
    if (closed > 0) {
      // Only when a loan actually closed: a second return should not move a state
      // that a trashing may have changed since.
      item.returnedFromLoan(actor, Instant.now(clock));
      items.flush();
      events.publishEvent(
          new de.greluc.homeinv.inventory.api.ItemReturned(tenantId, itemId, loanId, returnedOn));
      log.info("Item {} came back", itemId);
    }
    return loan;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<LoanView> openLoanOf(UUID itemId) {
    UUID tenantId = TenantContext.require();
    requireItem(tenantId, itemId);

    return jdbc.sql(OPEN_LOAN).param(tenantId).param(itemId).query(LoanAdapter::toView).optional();
  }

  @Override
  @Transactional(readOnly = true)
  public List<LoanView> loansOf(UUID itemId, int limit) {
    UUID tenantId = TenantContext.require();
    requireItem(tenantId, itemId);
    return jdbc
        .sql(LOANS)
        .param(tenantId)
        .param(itemId)
        .param(Math.clamp(limit, 1, MAX_PAGE))
        .query(LoanAdapter::toView)
        .list();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isLent(UUID itemId) {
    UUID tenantId = TenantContext.require();
    // No item check here, on purpose: the caller is `delete`, which has already
    // read the item and would otherwise read it again to ask a question it holds
    // the answer to. An item that is not there is not lent.
    return Boolean.TRUE.equals(
        jdbc.sql(
                """
                select exists (
                    select 1 from inventory.loan
                     where tenant_id = ? and item_id = ? and returned_on is null
                )
                """)
            .param(tenantId)
            .param(itemId)
            .query(Boolean.class)
            .single());
  }

  /**
   * The item in the path has to exist and be this tenant's.
   *
   * <p>Checked here rather than left to the foreign key, so that another tenant's item is a 404 like
   * one that never existed instead of a constraint violation that says a row is there
   * (REQ-SEC-025).
   *
   * @param tenantId whose item
   * @param itemId which item
   * @return the item, for the callers that move its state
   */
  private de.greluc.homeinv.inventory.domain.Item requireItem(UUID tenantId, UUID itemId) {
    return items.findAny(tenantId, itemId).orElseThrow(() -> new NotFoundException("item", itemId));
  }

  private LoanView byId(UUID tenantId, UUID id) {
    return loanById(tenantId, id).orElseThrow(() -> new NotFoundException("loan", id));
  }

  private Optional<LoanView> loanById(UUID tenantId, UUID id) {
    return jdbc.sql(ONE_LOAN).param(tenantId).param(id).query(LoanAdapter::toView).optional();
  }

  private static LoanView toView(ResultSet rs, int rowNum) throws SQLException {
    return new LoanView(
        rs.getObject("id", UUID.class),
        rs.getObject("item_id", UUID.class),
        rs.getObject("borrower_user_id", UUID.class),
        rs.getString("borrower_name"),
        rs.getObject("handed_out_on", LocalDate.class),
        rs.getObject("due_on", LocalDate.class),
        rs.getObject("returned_on", LocalDate.class),
        rs.getString("note"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getObject("created_by", UUID.class));
  }
}
