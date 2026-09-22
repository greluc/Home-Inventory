/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The SQL of the straight-line depreciation (REQ-LIFE-009).
 *
 * <p>Reads under the ordinary row-level security, like everything else: the run enters a tenant's
 * context and sees that tenant's rows.
 */
@Component
@RequiredArgsConstructor
public class DepreciationQueries {

  /**
   * Everything a refresh may write.
   *
   * <p>A thing that has been sold or thrown away is left out: its worth is the disposal price
   * somebody recorded, not a fraction of what it cost. So is anything whose current value was
   * typed or produced by a plugin — those are not this run's to correct.
   */
  private static final String DEPRECIABLE =
      """
      select id, item_type_version_id, purchase_amount, purchase_currency, purchased_on,
             current_source
      from inventory.item
      where purchase_amount is not null
        and purchased_on is not null
        and deleted_at is null
        and lifecycle_state in ('ACTIVE', 'LENT')
        and (current_source is null or current_source = 'DEPRECIATION')
      order by id
      """;

  private final JdbcClient jdbc;

  /**
   * Which tenants have anything a refresh could write.
   *
   * <p>Through the {@code SECURITY DEFINER} function of {@code V69}: the run has no tenant context
   * because it is looking for the ones that need one (07 §7.5).
   *
   * @return the tenants
   */
  @Transactional(readOnly = true)
  public List<UUID> tenantsWithSomethingToDepreciate() {
    return jdbc
        .sql("select tenant_id from inventory.tenants_with_depreciable_items()")
        .query((rs, row) -> rs.getObject(1, UUID.class))
        .list();
  }

  /**
   * The items a refresh may write, in this tenant.
   *
   * @return them
   */
  @Transactional(readOnly = true)
  public List<Depreciable> depreciable() {
    return jdbc.sql(DEPRECIABLE).query(DepreciationQueries::toDepreciable).list();
  }

  /**
   * Writes a depreciated value.
   *
   * @param itemId which item
   * @param amount what it is worth
   * @param currency the currency of the purchase, because a depreciation is of that money
   * @param asOf the day the figure speaks for
   */
  @Transactional
  public void write(UUID itemId, BigDecimal amount, String currency, LocalDate asOf) {
    jdbc.sql(
            """
            update inventory.item
               set current_amount = ?, current_currency = ?, current_as_of = ?,
                   current_source = 'DEPRECIATION'
             where id = ?
               and (current_source is null or current_source = 'DEPRECIATION')
            """)
        .param(amount)
        .param(currency)
        .param(asOf)
        .param(itemId)
        .update();
  }

  /**
   * Removes a depreciated value whose type no longer says how long anything lasts.
   *
   * @param itemId which item
   */
  @Transactional
  public void clear(UUID itemId) {
    jdbc.sql(
            """
            update inventory.item
               set current_amount = null, current_currency = null, current_as_of = null,
                   current_source = null
             where id = ? and current_source = 'DEPRECIATION'
            """)
        .param(itemId)
        .update();
  }

  /**
   * One row a refresh may write.
   *
   * @param rs the row
   * @param rowNum which row
   * @return it
   * @throws SQLException when it cannot be read
   */
  private static Depreciable toDepreciable(ResultSet rs, int rowNum) throws SQLException {
    return new Depreciable(
        rs.getObject("id", UUID.class),
        rs.getObject("item_type_version_id", UUID.class),
        rs.getBigDecimal("purchase_amount"),
        rs.getString("purchase_currency"),
        rs.getObject("purchased_on", LocalDate.class),
        "DEPRECIATION".equals(rs.getString("current_source")));
  }

  /**
   * An item a refresh may write.
   *
   * @param itemId which item
   * @param typeVersionId what it was written against, which is how its useful life is found
   * @param purchaseAmount what it cost
   * @param purchaseCurrency in what
   * @param purchasedOn when
   * @param hasDepreciatedValue whether this run has written one before, which decides whether a
   *     type that has lost its useful life leaves a figure behind to clear
   */
  public record Depreciable(
      UUID itemId,
      UUID typeVersionId,
      BigDecimal purchaseAmount,
      String purchaseCurrency,
      LocalDate purchasedOn,
      boolean hasDepreciatedValue) {}
}
