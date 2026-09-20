/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

import de.greluc.homeinv.catalog.api.FieldDefinitionView;
import de.greluc.homeinv.catalog.api.TypeRegistry;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.Valuation;
import de.greluc.homeinv.portability.api.CsvIngest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a mapped CSV row into an item (REQ-PORT-001, REQ-PORT-008).
 *
 * <h2>Through the service, not around it</h2>
 *
 * <p>{@link ItemService#create} and {@code update}, the same two calls the API makes. An import
 * that wrote rows itself would be a second implementation of every rule an item has — the attribute
 * validation, the sealing of a sensitive value, the audit entry, the search index — and the second
 * implementation is the one nobody updates.
 *
 * <h2>Attributes the type does not declare</h2>
 *
 * <p>Dropped, and their keys reported. A Homebox file carries a serial number and a model number;
 * whether an item in <i>this</i> tenant has fields for those is the tenant's decision (ADR-0020),
 * and refusing the file until they create them would make the migration a project. So the import
 * writes what fits and says what did not, once, by key.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportedItems implements CsvIngest {

  private final ItemService items;
  private final TypeRegistry types;
  private final ObjectMapper json;

  @Override
  public Ingested ingest(MappedItem row, UUID existing, UUID actor) {
    UUID type = defaultItemType();
    UUID typeVersion = types.publishedItemTypeVersion(type);
    List<String> notDeclared = new ArrayList<>();
    String attributes = attributesFor(typeVersion, row.attributes(), notDeclared);
    UUID place = row.locationPath() == null ? null : UUID.fromString(row.locationPath());

    if (existing == null) {
      UUID created =
          items
              .create(
                  new ItemService.CreateItemCommand(
                      null,
                      type,
                      row.name(),
                      row.description(),
                      place == null ? ItemKind.DIGITAL : ItemKind.PHYSICAL,
                      place,
                      row.quantity() == null ? java.math.BigDecimal.ONE : row.quantity(),
                      row.quantityUnit(),
                      attributes,
                      row.notes(),
                      row.minimumStock(),
                      valuationOf(row)),
                  Optional.empty(),
                  actor)
              .item()
              .id();
      return new Ingested(created, notDeclared);
    }

    // A second import of the same file. The row is the newer truth, which is the
    // same rule the archive side follows and the reason a source key is worth
    // carrying at all (REQ-PORT-008).
    items.update(
        existing,
        new ItemService.UpdateItemCommand(
            row.name(),
            row.description(),
            place,
            row.quantity() == null ? java.math.BigDecimal.ONE : row.quantity(),
            row.quantityUnit(),
            attributes,
            row.notes(),
            row.minimumStock(),
            valuationOf(row)),
        java.util.OptionalLong.empty(),
        actor);
    return new Ingested(existing, notDeclared);
  }

  /**
   * The attributes this type will accept, out of what the row carried.
   *
   * @param typeVersion which version says what a field is
   * @param incoming the row's attributes, by key
   * @param notDeclared where the keys that do not fit are collected
   * @return the attributes as JSON
   */
  private String attributesFor(
      UUID typeVersion, Map<String, String> incoming, List<String> notDeclared) {
    if (incoming.isEmpty()) {
      return "{}";
    }
    List<String> declared = types.fields(typeVersion).stream().map(FieldDefinitionView::key).toList();
    Map<String, Object> accepted = new LinkedHashMap<>();
    incoming.forEach(
        (key, value) -> {
          if (declared.contains(key)) {
            accepted.put(key, value);
          } else {
            notDeclared.add(key);
          }
        });
    return json.writeValueAsString(accepted);
  }

  /**
   * What the row says about money, in the shape the service takes.
   *
   * @param row the mapped row
   * @return the valuation, or {@link Valuation#NONE} when the file said nothing about price
   */
  private static Valuation valuationOf(MappedItem row) {
    if (row.purchaseAmount() == null || row.purchaseCurrency() == null) {
      return Valuation.NONE;
    }
    // Purchase only. A CSV from another system says what something cost and
    // when; what it is worth now and what it would cost to replace are this
    // application's own notions and nothing in the file speaks to them.
    return new Valuation(
        new de.greluc.homeinv.platform.Money(
            row.purchaseAmount(), java.util.Currency.getInstance(row.purchaseCurrency())),
        row.purchasedOn(),
        row.purchaseSource(),
        row.warrantyUntil(),
        row.lifetimeWarranty(),
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * The type an imported row is written against.
   *
   * <p>The tenant's built-in {@code general}. Asked of {@code catalog} through its own port rather
   * than read out of its tables: a block reaches another only through its published api, and a
   * query across schemas is exactly the shape ADR-0002 forbids even when the foreign keys already
   * couple the two.
   *
   * @return the type's id
   */
  private UUID defaultItemType() {
    return types
        .itemTypeByKey("general")
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "The tenant has no 'general' item type, so an imported row has nothing to be "
                        + "written against. A provisioned tenant has one."));
  }
}
