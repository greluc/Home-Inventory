/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.application;

import de.greluc.homeinv.portability.api.CsvIngest;
import de.greluc.homeinv.portability.api.MappingProfile;
import de.greluc.homeinv.portability.api.PlacePath;
import de.greluc.homeinv.portability.api.TagIngest;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import de.greluc.homeinv.portability.infrastructure.ProvenanceQueries;
import org.springframework.stereotype.Component;
import tools.jackson.dataformat.csv.CsvMapper;
import tools.jackson.dataformat.csv.CsvSchema;

/**
 * Reads a CSV from another system through a mapping profile (REQ-PORT-001, REQ-PORT-002,
 * REQ-PORT-008).
 *
 * <h2>Every row, or none of them</h2>
 *
 * <p>Called inside the import's single transaction, like the archive side, so a row that cannot be
 * read leaves nothing behind (REQ-PORT-007). The errors are collected rather than thrown one at a
 * time: somebody importing five hundred rows wants the list, not the first line that went wrong
 * followed by four more attempts to find the second.
 *
 * <h2>What it does with what it does not understand</h2>
 *
 * <p>Three different things, and the difference matters.
 *
 * <ul>
 *   <li>A <b>column the profile does not map</b> is listed in the report and skipped. Another
 *       system's export always carries columns this one has no home for.
 *   <li>An <b>attribute the item type does not declare</b> is listed and skipped, for the same
 *       reason: which fields an item has is the tenant's decision (ADR-0020), and an import that
 *       refused the first unknown one would never finish.
 *   <li>A <b>value that cannot be read as what its column says it is</b> — a date that is not a
 *       date, a price that is not a number — is an <b>error</b>, named by line and column, and it
 *       fails the import. That is data loss rather than a mismatch of vocabulary.
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CsvImportRunner {

  /** How many mapped rows the report carries, so somebody can see what the mapping did. */
  private static final int PREVIEW_ROWS = 20;

  /**
   * How many rows one import reads.
   *
   * <p>The whole file is held while the transaction is open, so the ceiling is explicit rather than
   * discovered as an {@code OutOfMemoryError}. A hundred thousand rows is far past any household
   * and still small enough to hold; a file larger than that is a conversation rather than a
   * surprise, and the message says to split it.
   */
  private static final int MAX_ROWS = 100_000;

  private final CsvIngest items;
  private final PlacePath places;
  private final TagIngest tags;
  private final ProvenanceQueries provenance;

  /**
   * Reads one file into the tenant.
   *
   * @param bytes the uploaded file, which the caller closes
   * @param profile what its columns mean
   * @param jobId the job, recorded as the provenance of every item
   * @param actor who asked
   * @return what happened, for the report
   * @throws IllegalArgumentException when the file cannot be read at all, or a value in it cannot
   */
  public Map<String, Object> read(
      InputStream bytes, MappingProfile profile, UUID jobId, UUID actor) {
    List<Map<String, String>> rows = parse(bytes);
    List<String> errors = new ArrayList<>();
    List<Map<String, Object>> preview = new ArrayList<>();
    Set<String> unmapped = new LinkedHashSet<>();
    Set<String> unknownFields = new LinkedHashSet<>();

    int created = 0;
    int updated = 0;
    int line = 1;
    for (Map<String, String> row : rows) {
      line++;
      for (String column : row.keySet()) {
        if (!profile.columns().containsKey(column)) {
          unmapped.add(column);
        }
      }
      try {
        CsvIngest.MappedItem mapped = map(row, profile, line, errors);
        if (mapped == null) {
          continue;
        }
        if (preview.size() < PREVIEW_ROWS) {
          preview.add(asPreview(mapped));
        }
        UUID existing = provenance.itemOf(profile.source(), mapped.sourceKey());
        UUID place = places.resolveOrCreate(mapped.locationPath(), actor);
        CsvIngest.Ingested ingested =
            items.ingest(withPlace(mapped, place), existing, actor);
        unknownFields.addAll(ingested.fieldsNotDeclared());
        tags.assign(ingested.itemId(), mapped.tags(), actor);
        provenance.record(
            ingested.itemId(), jobId, profile.source(), mapped.sourceKey(), line, actor);
        if (existing == null) {
          created++;
        } else {
          updated++;
        }
      } catch (RuntimeException failed) {
        errors.add("line " + line + ": " + failed.getMessage());
      }
    }

    if (!errors.isEmpty()) {
      // One message with the first few, because a job's `failure` column is
      // read by a person and a thousand lines of it is not read at all. The
      // count says how many more there were.
      throw new IllegalArgumentException(
          errors.size()
              + " row(s) could not be read, and nothing was written. "
              + String.join("; ", errors.subList(0, Math.min(5, errors.size())))
              + (errors.size() > 5 ? "; …" : ""));
    }

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("profile", profile.key());
    report.put("rows", rows.size());
    report.put("created", created);
    report.put("updated", updated);
    report.put("unmappedColumns", List.copyOf(unmapped));
    report.put("fieldsTheTypeDoesNotDeclare", List.copyOf(unknownFields));
    report.put("preview", preview);
    return report;
  }

  /**
   * The file as a list of rows, keyed by its own header.
   *
   * @param bytes the upload
   * @return one map per row
   */
  private List<Map<String, String>> parse(InputStream bytes) {
    CsvMapper mapper = CsvMapper.builder().build();
    // The header names the columns, which is the whole premise of a mapping
    // profile: the file says what its columns are called and the profile says
    // what they mean.
    CsvSchema schema = CsvSchema.emptySchema().withHeader();
    List<Map<String, String>> rows = new ArrayList<>();
    try (var values =
        mapper.readerFor(Map.class).with(schema).<Map<String, String>>readValues(bytes)) {
      while (values.hasNext()) {
        if (rows.size() >= MAX_ROWS) {
          throw new IllegalArgumentException(
              "The file has more than " + MAX_ROWS + " rows, which is more than one import holds "
                  + "in memory. Split it and import the parts.");
        }
        rows.add(values.next());
      }
    } catch (RuntimeException unreadable) {
      throw new IllegalArgumentException(
          "The file could not be read as CSV: " + unreadable.getMessage());
    }
    if (rows.isEmpty()) {
      throw new IllegalArgumentException("The file has a header and no rows.");
    }
    return rows;
  }

  /**
   * One row, through the profile.
   *
   * @param row the row, by its own column names
   * @param profile what they mean
   * @param line which line, for an error that somebody can act on
   * @param errors where a value that cannot be read is recorded
   * @return the mapped row, or null when it had no name and is therefore not an item
   */
  private CsvIngest.MappedItem map(
      Map<String, String> row, MappingProfile profile, int line, List<String> errors) {
    Map<String, String> byTarget = new LinkedHashMap<>();
    Map<String, String> attributes = new LinkedHashMap<>();
    for (Map.Entry<String, String> column : row.entrySet()) {
      String target = profile.columns().get(column.getKey());
      String value = column.getValue() == null ? null : column.getValue().strip();
      if (target == null || value == null || value.isEmpty()) {
        continue;
      }
      if (target.startsWith("attributes.")) {
        attributes.put(target.substring("attributes.".length()), value);
      } else {
        byTarget.putIfAbsent(target, value);
      }
    }

    String name = byTarget.get("name");
    if (name == null || name.isBlank()) {
      errors.add("line " + line + ": no name, and an item without one is not an item");
      return null;
    }

    return new CsvIngest.MappedItem(
        byTarget.get("sourceKey"),
        name,
        byTarget.get("description"),
        byTarget.get("notes"),
        decimal(byTarget.get("quantity"), "quantity", line),
        byTarget.get("quantityUnit"),
        byTarget.get("location"),
        splitTags(byTarget.get("tags")),
        decimal(byTarget.get("purchaseAmount"), "purchaseAmount", line),
        profile.defaultCurrency(),
        date(byTarget.get("purchasedOn"), "purchasedOn", line),
        byTarget.get("purchaseSource"),
        date(byTarget.get("warrantyUntil"), "warrantyUntil", line),
        flag(byTarget.get("lifetimeWarranty")),
        decimal(byTarget.get("minimumStock"), "minimumStock", line),
        Map.copyOf(attributes));
  }

  /**
   * The same row with its place resolved.
   *
   * @param item the mapped row
   * @param place the place, or null
   * @return the row carrying the place's id in its path slot
   */
  private static CsvIngest.MappedItem withPlace(CsvIngest.MappedItem item, UUID place) {
    return new CsvIngest.MappedItem(
        item.sourceKey(),
        item.name(),
        item.description(),
        item.notes(),
        item.quantity(),
        item.quantityUnit(),
        place == null ? null : place.toString(),
        item.tags(),
        item.purchaseAmount(),
        item.purchaseCurrency(),
        item.purchasedOn(),
        item.purchaseSource(),
        item.warrantyUntil(),
        item.lifetimeWarranty(),
        item.minimumStock(),
        item.attributes());
  }

  /**
   * One mapped row as the report shows it.
   *
   * @param item the row
   * @return the fields that were filled, so a person can see what the mapping did
   */
  private static Map<String, Object> asPreview(CsvIngest.MappedItem item) {
    Map<String, Object> shown = new LinkedHashMap<>();
    shown.put("name", item.name());
    if (item.locationPath() != null) {
      shown.put("location", item.locationPath());
    }
    if (!item.tags().isEmpty()) {
      shown.put("tags", item.tags());
    }
    if (item.purchaseAmount() != null) {
      shown.put("purchaseAmount", item.purchaseAmount().toPlainString());
    }
    if (!item.attributes().isEmpty()) {
      shown.put("attributes", item.attributes());
    }
    return shown;
  }

  /**
   * A number, or an error naming the column.
   *
   * @param value the text
   * @param column which column, for the message
   * @param line which line
   * @return the number, or null when the column was absent
   */
  private static BigDecimal decimal(String value, String column, int line) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      // A thousands separator is the one thing a spreadsheet adds that a number
      // parser refuses, and it is not ambiguous here: the decimal point is what
      // every system this reads exports.
      return new BigDecimal(value.replace(",", "").replace(" ", ""));
    } catch (NumberFormatException notANumber) {
      throw new IllegalArgumentException(column + " is not a number: " + value);
    }
  }

  /**
   * A date, or an error naming the column.
   *
   * @param value the text
   * @param column which column
   * @param line which line
   * @return the date, or null when the column was absent
   */
  private static LocalDate date(String value, String column, int line) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String text = value.length() > 10 ? value.substring(0, 10) : value;
    try {
      return LocalDate.parse(text);
    } catch (DateTimeParseException notADate) {
      throw new IllegalArgumentException(
          column + " is not a date in the form YYYY-MM-DD: " + value);
    }
  }

  /**
   * A boolean, read the way a spreadsheet writes one.
   *
   * @param value the text
   * @return whether it says yes
   */
  private static boolean flag(String value) {
    return value != null
        && Set.of("true", "yes", "y", "1").contains(value.strip().toLowerCase(java.util.Locale.ROOT));
  }

  /**
   * Tags, as another system wrote them.
   *
   * @param value the cell
   * @return the names, never null
   */
  private static List<String> splitTags(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return Arrays.stream(value.split("[;,]"))
        .map(String::strip)
        .filter(tag -> !tag.isEmpty())
        .distinct()
        .toList();
  }
}
