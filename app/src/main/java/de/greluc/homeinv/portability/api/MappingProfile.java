/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a CSV's columns mean (REQ-PORT-001, REQ-PORT-002).
 *
 * <h2>Data, not a language</h2>
 *
 * <p>A profile is a set of pairs: this column is that field. Nothing in it is an expression, a
 * formula or a condition, and that is a rule rather than an omission — ADR-0020 says the
 * configuration surface must not become a programming language, and a mapping with an {@code if} in
 * it is the first step of exactly that. A file that needs more than pairs needs editing before it
 * is uploaded, or a plugin.
 *
 * <h2>Two ship with the product</h2>
 *
 * <p>Homebox and InvenTree (REQ-PORT-002), as constants rather than as rows: they are the same on
 * every instance and in every tenant, so a row per tenant would be provisioning writing the same
 * thing over and over, and a built-in nobody may edit is what a constant already is. A tenant
 * needing one of them changed copies it into a profile of its own.
 *
 * @param key how the profile is named in a request — {@code homebox}, {@code inventree}
 * @param name what it is called in a list
 * @param source the system a file comes from, recorded as the provenance of every item it creates
 *     (REQ-PORT-008)
 * @param columns the mapping, from the CSV's own header to a target. A header the map does not name
 *     is reported and not written; the report lists them once, because a column nobody mapped is
 *     usually a column somebody meant to
 * @param defaultCurrency the currency every price in the file is in. A CSV rarely says, and
 *     guessing per row is how a household ends up owning things in three currencies
 * @param itemTypeKey which item type the rows are written against, or null for the tenant's
 *     built-in {@code general}
 */
public record MappingProfile(
    String key,
    String name,
    String source,
    Map<String, String> columns,
    String defaultCurrency,
    String itemTypeKey) {

  /**
   * The targets a column may be mapped to.
   *
   * <p>Anything else is a key into {@link CsvIngest.MappedItem#attributes()}, written as {@code
   * attributes.<fieldKey>} — and skipped, with the column named in the report, when the item type
   * does not declare that field. A file from another system always carries columns this one has no
   * field for, and an import that refused the first of them would never finish.
   */
  public static final List<String> TARGETS =
      List.of(
          "sourceKey",
          "name",
          "description",
          "notes",
          "quantity",
          "quantityUnit",
          "location",
          "tags",
          "purchaseAmount",
          "purchasedOn",
          "purchaseSource",
          "warrantyUntil",
          "lifetimeWarranty",
          "minimumStock");

  /**
   * Homebox, as its own exporter writes a file (REQ-PORT-002).
   *
   * <p>The column names are Homebox's, verbatim: its {@code io_row.go} tags every field with the
   * header it writes. {@code HB.purchase_time} and {@code HB.sold_time} are the older spellings of
   * two of them and Homebox still accepts both, so both are here — a file exported from an older
   * installation is exactly the file somebody is trying to move away from.
   *
   * <p>{@code HB.location} is a path and Homebox nests it with {@code /}, which is what {@link
   * PlacePath} expects. Serial, model and manufacturer numbers become attributes: this application
   * has no columns for them, because which fields an item has is the tenant's decision (ADR-0020).
   * They arrive if the type declares them and are reported if it does not.
   */
  public static final MappingProfile HOMEBOX =
      new MappingProfile(
          "homebox",
          "Homebox",
          "homebox",
          columns(
              "HB.import_ref", "sourceKey",
              "HB.name", "name",
              "HB.description", "description",
              "HB.notes", "notes",
              "HB.quantity", "quantity",
              "HB.location", "location",
              "HB.tags", "tags",
              "HB.labels", "tags",
              "HB.purchase_price", "purchaseAmount",
              "HB.purchase_date", "purchasedOn",
              "HB.purchase_time", "purchasedOn",
              "HB.purchase_from", "purchaseSource",
              "HB.warranty_expires", "warrantyUntil",
              "HB.lifetime_warranty", "lifetimeWarranty",
              "HB.serial_number", "attributes.serialNumber",
              "HB.model_number", "attributes.modelNumber",
              "HB.manufacturer", "attributes.manufacturer",
              "HB.url", "attributes.url",
              "HB.asset_id", "attributes.assetId"),
          "EUR",
          null);

  /**
   * InvenTree, as its part export writes a file (REQ-PORT-002).
   *
   * <p>A different shape and worth saying why: InvenTree is a parts system, so a row is a
   * <b>part</b> rather than a thing on a shelf. {@code IPN} — the internal part number — is what it
   * calls a row by and is therefore the source key; {@code category_path} is the nearest thing it
   * has to a place, and {@code keywords} to tags. {@code minimum_stock} maps straight across,
   * because this application has that notion for the same reason InvenTree does.
   */
  public static final MappingProfile INVENTREE =
      new MappingProfile(
          "inventree",
          "InvenTree",
          "inventree",
          columns(
              "IPN", "sourceKey",
              "name", "name",
              "description", "description",
              "notes", "notes",
              "category_path", "location",
              "keywords", "tags",
              "units", "quantityUnit",
              "minimum_stock", "minimumStock",
              "link", "attributes.url",
              "revision", "attributes.revision"),
          "EUR",
          null);

  /** Both of the above, in the order a list shows them. */
  public static final List<MappingProfile> BUILT_IN = List.of(HOMEBOX, INVENTREE);

  /**
   * The built-in profile with this key.
   *
   * @param key its key
   * @return the profile, or empty when no built-in has that key
   */
  public static java.util.Optional<MappingProfile> builtIn(String key) {
    return BUILT_IN.stream().filter(profile -> profile.key().equals(key)).findFirst();
  }

  /**
   * A column map from alternating pairs.
   *
   * <p>{@code Map.of} stops at ten pairs and has no order; both matter here, because the Homebox
   * profile has nineteen and the report lists them as they were written.
   *
   * @param pairs column, target, column, target, …
   * @return the map, in that order
   */
  private static Map<String, String> columns(String... pairs) {
    if (pairs.length % 2 != 0) {
      throw new IllegalArgumentException("A column map is pairs; this one has an odd number");
    }
    Map<String, String> map = new LinkedHashMap<>();
    for (int at = 0; at < pairs.length; at += 2) {
      map.put(pairs[at], pairs[at + 1]);
    }
    // `Map.copyOf` would lose the order, which is the thing this method exists
    // for: the report lists a profile's columns as they were written.
    return java.util.Collections.unmodifiableMap(map);
  }
}
