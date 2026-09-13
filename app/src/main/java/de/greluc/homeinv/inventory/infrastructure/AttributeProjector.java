/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

import de.greluc.homeinv.catalog.api.FieldDefinitionView;
import de.greluc.homeinv.catalog.api.TypeRegistry;
import de.greluc.homeinv.platform.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Mirrors an item's searchable attributes into {@code item_attr_index} (REQ-CORE-013, ADR-0004).
 *
 * <h2>Why this is not a cache</h2>
 *
 * <p>Every method here runs with {@link Propagation#MANDATORY}: it must be inside the transaction
 * that wrote the item. That is the whole property REQ-CORE-013 asks for — an attribute filter is
 * <em>transactionally exact</em>, which it stops being the moment the projection can lag by even one
 * commit. A cache may be stale; this may not, and that is the difference between it and the search
 * index beside it.
 *
 * <p>Only the fields a tenant marked searchable, sortable or facetable are mirrored, and a
 * {@code secret} is never mirrored whatever its flags say ({@link FieldDefinitionView#projected()}):
 * this table answers filters, and a value gated by a permission must not be filterable by somebody
 * who does not hold it.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AttributeProjector {

  private final JdbcClient jdbc;
  private final TypeRegistry types;
  private final ObjectMapper mapper;

  /**
   * Rewrites one item's rows to match the attribute set it now carries.
   *
   * <p>Delete then insert, rather than a merge: the set of projected fields changes when a type
   * version changes, so a merge would leave rows behind for fields the item no longer has. Three to
   * eight rows is the normal size of this, which is what makes the simple thing the right one
   * (07 §7.3).
   *
   * @param itemId the item
   * @param typeVersionId the version whose definitions say what is projected
   * @param attributesJson the attribute set as JSON text
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void project(UUID itemId, UUID typeVersionId, String attributesJson) {
    clear(itemId);
    if (attributesJson == null || attributesJson.isBlank()) {
      return;
    }
    UUID tenantId = TenantContext.require();
    JsonNode attributes = mapper.readTree(attributesJson);

    for (FieldDefinitionView field : types.fields(typeVersionId)) {
      if (!field.projected()) {
        continue;
      }
      JsonNode value = attributes.get(field.key());
      if (value == null || value.isNull()) {
        continue;
      }
      write(tenantId, itemId, field, value);
    }
  }

  /**
   * Removes every row this item projects.
   *
   * <p>Called when an item is trashed, and not only when it is finally removed: 07 §7.3 makes the
   * soft deletion take the rows with it, so a trashed item stops answering attribute filters at once
   * rather than for the whole retention period. Restoring it projects them again.
   *
   * @param itemId the item
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void clear(UUID itemId) {
    jdbc.sql("delete from inventory.item_attr_index where tenant_id = ? and item_id = ?")
        .params(TenantContext.require(), itemId)
        .update();
  }

  /**
   * Writes one field's value into the column its kind belongs in.
   *
   * @param tenantId the tenant, named in the row as everywhere
   * @param itemId the item
   * @param field the definition, which decides the column and whether a unit travels with it
   * @param value the value as it appears in the attribute set
   */
  private void write(UUID tenantId, UUID itemId, FieldDefinitionView field, JsonNode value) {
    BigDecimal number = null;
    String text = null;
    Instant date = null;
    Boolean flag = null;
    UUID reference = null;
    String unit = null;

    switch (field.dataType()) {
      case INTEGER, DECIMAL -> number = decimal(value);
      case MONEY -> {
        number = decimal(value.get("amount"));
        unit = string(value.get("currency"));
      }
      case QUANTITY -> {
        number = decimal(value.get("value"));
        unit = string(value.get("unit"));
      }
      case BOOLEAN -> flag = value.asBoolean();
      case DATE -> date = LocalDate.parse(value.asString()).atStartOfDay(ZoneOffset.UTC).toInstant();
      case DATETIME -> date = Instant.parse(value.asString());
      case REFERENCE -> reference = UUID.fromString(value.asString());
      // TEXT, MULTILINE, URL, EMAIL and ENUM all project as text; the kinds that
      // project as nothing were filtered out by `projected()` above.
      default -> text = value.asString();
    }

    jdbc.sql(
            """
            insert into inventory.item_attr_index
                (tenant_id, item_id, field_key, num_value, text_value, date_value, bool_value,
                 ref_value, unit_value)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            tenantId,
            itemId,
            field.key(),
            number,
            text,
            // Wrapped, not passed as an Instant: the driver cannot infer a SQL
            // type for one and refuses the statement. Nothing caught it until a
            // shipped template brought the first sortable `date` field, because
            // every earlier test projected text, numbers and money only.
            date == null ? null : java.sql.Timestamp.from(date),
            flag,
            reference,
            unit)
        .update();
  }

  /**
   * A JSON value as an exact decimal.
   *
   * <p>Through its text, never through a double: {@code decimal} and {@code money} travel as strings
   * for exactly this reason (ADR-0025), and reading a JSON number through {@code asDouble} would
   * undo that at the last step.
   *
   * @param value the value, possibly {@code null}
   * @return the number, or {@code null}
   */
  private BigDecimal decimal(JsonNode value) {
    return value == null || value.isNull() ? null : new BigDecimal(value.asString());
  }

  /**
   * A JSON value as text.
   *
   * @param value the value, possibly {@code null}
   * @return the text, or {@code null}
   */
  private String string(JsonNode value) {
    return value == null || value.isNull() ? null : value.asString();
  }
}
