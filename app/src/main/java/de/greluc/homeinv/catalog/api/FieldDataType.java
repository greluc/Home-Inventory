/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.api;

import java.util.Arrays;
import java.util.Locale;

/**
 * The sixteen kinds of value a tenant-defined field can hold (REQ-CORE-022).
 *
 * <h2>Why the list is closed</h2>
 *
 * <p>ADR-0020 draws its line here: a field is a declaration, not a program. Every member of this
 * list is validatable from a JSON Schema, projectable into one column of {@code item_attr_index},
 * and renderable by a client that has never seen the tenant's configuration. A seventeenth kind
 * added because one tenant wanted it would have to be all three of those in Java, TypeScript and
 * Kotlin — which is why a new kind is a change to the product and not to a tenant's data.
 *
 * <p>The wire token is the spelling the database {@code CHECK}, the API and the generated schema all
 * use. It is <b>data</b>: it sits in {@code catalog.field_definition.data_type}, travels in the
 * tenant export and is read by clients, so it does not follow Java's naming — {@code MULTI_ENUM} is
 * {@code multi-enum} everywhere outside this file.
 *
 * @see <a href="../../../../../../../../docs/adr/0020-configuration-as-data.md">ADR-0020</a>
 */
public enum FieldDataType {

  /** A single line of text. */
  TEXT("text", StorageClass.TEXT, false, false),

  /** Several lines of text, limited Markdown at the presentation layer only. */
  MULTILINE("multiline", StorageClass.TEXT, false, false),

  /** A whole number. */
  INTEGER("integer", StorageClass.NUMBER, false, false),

  /**
   * An exact decimal, carried as a string.
   *
   * <p>A JSON number would be a double by the time it reached a browser, and this project forbids
   * binary floating point on any path where the value is arithmetic rather than a measurement of
   * something already approximate (ADR-0025).
   */
  DECIMAL("decimal", StorageClass.NUMBER, false, false),

  /**
   * An amount and its ISO 4217 code, as {@code {"amount":"49.90","currency":"EUR"}}.
   *
   * <p>The code travels into {@code item_attr_index.unit_value}, so that every total over a money
   * field groups by currency and a mixed-currency sum is never produced (REQ-CORE-032).
   */
  MONEY("money", StorageClass.NUMBER, false, true),

  /** True or false. There is no third state; a field that may be unknown is not required. */
  BOOLEAN("boolean", StorageClass.BOOLEAN, false, false),

  /** A calendar date with no time and no zone — a purchase date, a best-before date. */
  DATE("date", StorageClass.DATE, false, false),

  /** An instant, stored and compared in UTC (REQ-NFR-035). */
  DATETIME("datetime", StorageClass.DATE, false, false),

  /** One value out of a value list (REQ-CORE-029). */
  ENUM("enum", StorageClass.TEXT, true, false),

  /**
   * Several values out of a value list.
   *
   * <p>Projected as one row per chosen value would break the primary key of {@code item_attr_index},
   * which is {@code (item_id, field_key)}; a multi-enum is therefore searchable through the JSONB
   * containment index rather than the side table, and {@link #storageClass()} says so.
   */
  MULTI_ENUM("multi-enum", StorageClass.NONE, true, false),

  /** An absolute URL. */
  URL("url", StorageClass.TEXT, false, false),

  /** An e-mail address. */
  EMAIL("email", StorageClass.TEXT, false, false),

  /**
   * A number and its unit, as {@code {"value":"2.5","unit":"kg"}}.
   *
   * <p>The unit travels into {@code unit_value} for the same reason money's currency does: adding
   * grams to kilograms produces a number that looks right.
   */
  QUANTITY("quantity", StorageClass.NUMBER, false, true),

  /** The id of another item, a location or a value list entry — what it may point at is a constraint. */
  REFERENCE("reference", StorageClass.REFERENCE, false, false),

  /**
   * A licence key, an account number: encrypted at rest and gated by a permission (ADR-0019).
   *
   * <p>Never projected. A side table exists to be queried, and a value that may only be read by a
   * caller holding a particular permission must not be filterable by one who does not.
   */
  SECRET("secret", StorageClass.NONE, false, false),

  /** The id of a media object held by this tenant. */
  FILE("file", StorageClass.NONE, false, false);

  /** Where a value of this kind lands in {@code inventory.item_attr_index}. */
  public enum StorageClass {
    /** {@code num_value}. */
    NUMBER,
    /** {@code text_value}. */
    TEXT,
    /** {@code date_value}. */
    DATE,
    /** {@code bool_value}. */
    BOOLEAN,
    /** {@code ref_value}. */
    REFERENCE,
    /** Not projected at all — the JSONB index serves it, or nothing does. */
    NONE
  }

  private final String token;
  private final StorageClass storageClass;
  private final boolean needsValueList;
  private final boolean carriesUnit;

  FieldDataType(String token, StorageClass storageClass, boolean needsValueList, boolean carriesUnit) {
    this.token = token;
    this.storageClass = storageClass;
    this.needsValueList = needsValueList;
    this.carriesUnit = carriesUnit;
  }

  /**
   * The spelling used in the database, the API and the generated schema.
   *
   * @return the wire token, for example {@code multi-enum}
   */
  public String token() {
    return token;
  }

  /**
   * Which column of {@code item_attr_index} a value of this kind is projected into.
   *
   * @return the storage class, {@link StorageClass#NONE} when the kind is not projected
   */
  public StorageClass storageClass() {
    return storageClass;
  }

  /**
   * Whether a field of this kind must name a value list.
   *
   * <p>The database says the same thing in {@code field_definition_value_list_only_for_enums}; this
   * is the half that can answer before the write is attempted.
   *
   * @return true for {@link #ENUM} and {@link #MULTI_ENUM}
   */
  public boolean needsValueList() {
    return needsValueList;
  }

  /**
   * Whether a value of this kind carries a unit that must survive into {@code unit_value}.
   *
   * @return true for {@link #MONEY} and {@link #QUANTITY}
   */
  public boolean carriesUnit() {
    return carriesUnit;
  }

  /**
   * Whether this kind can be mirrored into the side table at all.
   *
   * @return true when {@link #storageClass()} is not {@link StorageClass#NONE}
   */
  public boolean projectable() {
    return storageClass != StorageClass.NONE;
  }

  /**
   * The kind a wire token names.
   *
   * @param token the token as it appears in the database or in a request
   * @return the matching kind
   * @throws IllegalArgumentException when no kind uses that token, which is the answer a request
   *     naming an unsupported data type gets
   */
  public static FieldDataType ofToken(String token) {
    String needle = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
    return Arrays.stream(values())
        .filter(candidate -> candidate.token.equals(needle))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Unknown field data type '"
                        + token
                        + "'. The supported ones are: "
                        + Arrays.stream(values()).map(FieldDataType::token).toList()));
  }
}
