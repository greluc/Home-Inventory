/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules that live in the migrations and can only be checked there
 * ({@code 07 §7.9}, "CI checks").
 *
 * <p>These are the ones a Java test cannot see and a reviewer reliably misses. A new table without
 * row-level security compiles, starts, serves traffic and returns other tenants' rows; nothing about
 * it looks wrong until it is. The rules are therefore read out of the SQL text itself.
 *
 * <p>Text analysis has an obvious weakness — it does not parse SQL — and a deliberate answer to it:
 * every rule below is phrased so that the failure mode is a false <em>positive</em>. A migration
 * written unusually fails this test and has to be looked at; one that quietly omits a policy cannot
 * pass it.
 */
@DisplayName("The migrations")
class MigrationRulesTest {

  private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

  /**
   * Tables that carry no {@code tenant_id} by decision, from the closed list in {@code 07 §7.1}.
   *
   * <p>The list is closed on purpose: adding an entry here is more friction than adding the column,
   * which is the point. Each needs a sentence in that chapter saying what protects it instead.
   */
  private static final List<String> INSTANCE_WIDE =
      List.of(
          "identity.app_user",
          // What the operator installed. A plugin is installed for the instance
          // and consented to per tenant (09 §9.4), so the registration describes
          // the deployment and the GRANT beside it carries the tenant id and the
          // policy. A tenant-scoped registration would mean installing a plugin
          // once per tenant, which is the thing REQ-PLG-013 says an operator
          // does once.
          "plugins.plugin_registration",
          "identification.public_code",
          "identification.label_base_url_usage",
          "audit.chain_anchor",
          // Infrastructure rather than domain data (07 §7.1, rule 4): the relay
          // reads every tenant's rows by design, and a policy would hide them
          // from the one process whose job is to publish them.
          "outbox.event_publication",
          // Evidence of an erasure has to outlive the thing it is about: a
          // tenant-scoped certificate would be removed by the very run that
          // writes it (REQ-TEN-011). It holds no content — a tenant id, the name
          // it had, who asked, when, and counts — and only the instance operator
          // reads it.
          "tenancy.erasure_certificate",
          // The second factor is asked for between the password and the session,
          // when there is no tenant yet to scope a policy with (REQ-AUTH-002).
          // Reachable only through the caller's own session: no endpoint takes a
          // user id, and no operator path reaches somebody else's authenticator.
          "identity.credential");

  /**
   * The tables rule 4 exempts, as 07 §7.1 lists them.
   *
   * <p>Closed, like the instance-wide list above and for the same reason: a check a table could opt
   * itself out of enforces nothing. Five kinds, and the chapter says of each why the columns would
   * be meaningless — derived from another row, a record of something that happened, infrastructure
   * owned by a mechanism, issued and never edited, or a rule that exists or does not.
   */
  private static final List<String> NOT_DOMAIN_TABLES =
      List.of(
          // Derived.
          "inventory.item_attr_index",
          // Append-only records of an event.
          "sync.change_log",
          "audit.audit_entry",
          "audit.chain_anchor",
          "audit.chain_truncation",
          "audit.revision_record",
          "identification.label_base_url_usage",
          "tenancy.erasure_certificate",
          // Infrastructure.
          //
          // A queued notification is a unit of work owned by the delivery
          // mechanism, like an outbox row: its `state` and `next_attempt_at` are
          // the mechanism's bookkeeping, and an `updated_by` on it would name the
          // scheduler rather than a person. Its attempts are an append-only
          // record of what happened on each try.
          "notification.notification",
          "notification.delivery_attempt",
          "outbox.event_publication",
          "idempotency.processed_request",
          "crypto.tenant_data_key",
          // Issued, never edited.
          "identification.public_code",
          "identification.code_binding",
          "inventory.item_relation",
          "inventory.item_bundle",
          // A rule that exists or does not.
          "catalog.location_category_child");

  /** What rule 4 demands of every other table. */
  private static final List<String> REQUIRED_COLUMNS =
      List.of("version", "created_at", "updated_at", "created_by", "updated_by");

  /** Where a column reaches a table that already exists. */
  private static final Pattern ADD_COLUMN =
      Pattern.compile(
          "alter\\s+table\\s+([a-z_]+\\.[a-z_]+)\\s+add\\s+column", Pattern.CASE_INSENSITIVE);

  private static final Pattern CREATE_TABLE =
      Pattern.compile("create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([a-z_]+\\.[a-z_]+)",
          Pattern.CASE_INSENSITIVE);

  @Test
  @DisplayName("give every domain table a tenant column and a forced policy")
  void everyDomainTableIsTenantScopedAndForced() throws IOException {
    Map<String, String> tables = tablesWithTheirScript();
    List<String> missing = new ArrayList<>();

    tables.forEach(
        (table, script) -> {
          if (INSTANCE_WIDE.contains(table)) {
            return;
          }
          String lower = script.toLowerCase(Locale.ROOT);
          boolean hasTenantColumn = definitionOf(lower, table).contains("tenant_id");
          boolean enabled = lower.contains("alter table " + table + " enable row level security");
          boolean forced = lower.contains("alter table " + table + " force  row level security")
              || lower.contains("alter table " + table + " force row level security");
          boolean hasPolicy = lower.contains("create policy") && lower.contains("on " + table);

          if (!hasTenantColumn || !enabled || !forced || !hasPolicy) {
            missing.add(
                "%s (tenant_id=%s, enable=%s, force=%s, policy=%s)"
                    .formatted(table, hasTenantColumn, enabled, forced, hasPolicy));
          }
        });

    assertThat(missing)
        .describedAs(
            "Every domain table carries tenant_id and row-level security with FORCE (07 §7.1, "
                + "§7.5). FORCE is the word that matters: without it the policy does not apply to "
                + "the table owner, and the migrator owns every table. A table listed here is "
                + "either missing a rule or belongs on the closed instance-wide list.")
        .isEmpty();
  }

  @Test
  @DisplayName("carry the tenant in every reference between tenant-scoped tables")
  void referencesBetweenTenantTablesAreComposite() throws IOException {
    // 07 §7.5: a foreign key check bypasses row-level security, always. A
    // single-column reference therefore succeeds across a tenant boundary, and
    // the row comes into existence pointing at somebody else's data. Making the
    // tenant part of the key is what makes that impossible rather than merely
    // caught by the application.
    // The exception is a property of the TARGET rather than a file name: a
    // reference to an instance-wide table is single-column because that table has
    // no tenant to carry. `tenancy.membership -> identity.app_user` is the entry
    // 07 §7.9 names, and `identity.credential -> identity.app_user` is the same
    // case. Keyed on the file name, as this was until 2026-09-13, the rule passed
    // anything that happened to live in the same migration.
    Pattern singleColumn =
        Pattern.compile(
            "foreign\\s+key\\s*\\(\\s*[a-z_]+\\s*\\)\\s*references\\s+([a-z_]+\\.[a-z_]+)",
            Pattern.CASE_INSENSITIVE);
    Pattern inlineReference =
        Pattern.compile(
            "^\\s*[a-z_]+\\s+uuid[^,]*\\breferences\\s+([a-z_]+\\.[a-z_]+)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    List<String> offenders = new ArrayList<>();
    for (Path script : scripts()) {
      String withoutComments = stripComments(Files.readString(script, StandardCharsets.UTF_8));
      for (Pattern pattern : List.of(singleColumn, inlineReference)) {
        Matcher matcher = pattern.matcher(withoutComments);
        while (matcher.find()) {
          String target = matcher.group(1).toLowerCase(Locale.ROOT);
          if (!INSTANCE_WIDE.contains(target)) {
            offenders.add(script.getFileName() + " -> " + target);
          }
        }
      }
    }

    assertThat(offenders)
        .describedAs(
            "Every reference between two tenant-scoped tables is composite on (tenant_id, id) "
                + "(07 §7.5). A foreign key check bypasses row-level security, so a single-column "
                + "reference can point across a tenant boundary and succeed.")
        .isEmpty();
  }

  @Test
  @DisplayName("never make tenant_id a foreign key of its own")
  void tenantIdCarriesNoForeignKey() throws IOException {
    // 07 §7.5: tenant_id is a discriminator, not a reference between aggregates.
    // Declaring REFERENCES tenancy.tenant(id) on it would put a cross-schema
    // foreign key in every table of every block - the module rule turned inside
    // out - and a row whose tenant names no tenant is invisible to every policy
    // anyway, which is the same outcome the constraint would produce.
    Pattern offending =
        Pattern.compile("tenant_id\\s+uuid[^,]*\\breferences\\b", Pattern.CASE_INSENSITIVE);

    List<String> offenders = new ArrayList<>();
    for (Path script : scripts()) {
      if (offending.matcher(stripComments(Files.readString(script, StandardCharsets.UTF_8))).find()) {
        offenders.add(script.getFileName().toString());
      }
    }
    assertThat(offenders).isEmpty();
  }

  @Test
  @DisplayName("give every domain table `version` and the four audit columns (07 §7.1, rule 4)")
  void everyDomainTableIsVersionedAndAudited() throws IOException {
    // 07 §7.1 rule 4 has said "a migration check enforces it" since the chapter
    // was written, and until 2026-09-13 nothing did: the rule was a sentence and
    // the list of tables it exempts was a list nothing read. A rule with no
    // mechanism is exactly the drift that chapter exists to prevent, and this one
    // was sitting in the paragraph that describes the mechanism.
    //
    // Run for the first time it found five tables. Two of them were right to have
    // no such columns and joined the list; three were wrong and got the columns,
    // because a version row records who published it and an assignment records
    // who merged the tag out from under it.
    Map<String, String> tables = tablesWithTheirScript();
    Map<String, String> added = columnsAddedLater();
    List<String> offenders = new ArrayList<>();

    for (Map.Entry<String, String> entry : tables.entrySet()) {
      String table = entry.getKey();
      if (NOT_DOMAIN_TABLES.contains(table)) {
        continue;
      }
      String columns =
          definitionOf(entry.getValue().toLowerCase(Locale.ROOT), table)
              + added.getOrDefault(table, "");
      List<String> absent =
          REQUIRED_COLUMNS.stream().filter(column -> !columns.contains(column)).toList();
      if (!absent.isEmpty()) {
        offenders.add(table + " is missing " + absent);
      }
    }

    assertThat(offenders)
        .describedAs(
            "Every domain table carries `version bigint` and created_at/updated_at/created_by/"
                + "updated_by (07 §7.1, rule 4). A table that is derived, append-only, "
                + "infrastructure, issued-never-edited or a bare rule is exempt — and that list is "
                + "CLOSED: add the row to the table in 07 §7.1 and the name to NOT_DOMAIN_TABLES, "
                + "in the same change, or add the columns.")
        .isEmpty();
  }

  @Test
  @DisplayName("use version numbers that are unique across every block directory")
  void versionsAreUnique() throws IOException {
    // Flyway keeps one history table for all locations, so two blocks that both
    // start at V1 collide - and the failure appears at deployment, not here.
    Map<String, String> byVersion = new LinkedHashMap<>();
    List<String> duplicates = new ArrayList<>();
    for (Path script : scripts()) {
      String name = script.getFileName().toString();
      String version = name.substring(1, name.indexOf("__"));
      String previous = byVersion.put(version, name);
      if (previous != null) {
        duplicates.add(version + ": " + previous + " and " + name);
      }
    }
    assertThat(duplicates).isEmpty();
  }

  /**
   * Every column added to an existing table by a later migration, per table.
   *
   * <p>Without this the audit-column check reads the {@code CREATE TABLE} body alone and fails on a
   * table that has the columns — added in a migration of its own, which is the only way a table
   * that already exists can get one. One statement may add several columns, so the text runs from
   * the match to the next semicolon rather than to the end of the line.
   *
   * @return table name to the text of everything added to it afterwards
   * @throws IOException when a script cannot be read
   */
  private static Map<String, String> columnsAddedLater() throws IOException {
    Map<String, String> additions = new LinkedHashMap<>();
    for (Path script : scripts()) {
      String text =
          stripComments(Files.readString(script, StandardCharsets.UTF_8)).toLowerCase(Locale.ROOT);
      Matcher matcher = ADD_COLUMN.matcher(text);
      while (matcher.find()) {
        int end = text.indexOf(';', matcher.start());
        String statement =
            end < 0 ? text.substring(matcher.start()) : text.substring(matcher.start(), end);
        additions.merge(matcher.group(1), statement, (before, now) -> before + " " + now);
      }
    }
    return additions;
  }

  private static List<Path> scripts() throws IOException {
    try (Stream<Path> walk = Files.walk(MIGRATIONS)) {
      return walk.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
          .sorted(Comparator.comparing(Path::toString))
          .toList();
    }
  }

  /**
   * Maps every created table to the script that created it.
   *
   * @return table name to script text
   * @throws IOException when a script cannot be read
   */
  private static Map<String, String> tablesWithTheirScript() throws IOException {
    Map<String, String> tables = new LinkedHashMap<>();
    for (Path script : scripts()) {
      String text = stripComments(Files.readString(script, StandardCharsets.UTF_8));
      Matcher matcher = CREATE_TABLE.matcher(text);
      while (matcher.find()) {
        tables.put(matcher.group(1).toLowerCase(Locale.ROOT), text);
      }
    }
    return tables;
  }

  /**
   * The body of one {@code CREATE TABLE}, so a column of a neighbouring table cannot satisfy a
   * check meant for this one.
   *
   * @param script the whole script, lowercased and stripped of comments
   * @param table the table name
   * @return the text between that table's parentheses, or the empty string
   */
  private static String definitionOf(String script, String table) {
    int start = script.indexOf("create table " + table);
    if (start < 0) {
      start = script.indexOf("create table if not exists " + table);
    }
    if (start < 0) {
      return "";
    }
    int end = script.indexOf(");", start);
    return end < 0 ? script.substring(start) : script.substring(start, end);
  }

  /**
   * Removes {@code --} comments.
   *
   * <p>Without this the rationale in a comment would satisfy the very check it explains — several of
   * these files describe the rule they follow, and a naive text search would find the description
   * instead of the statement.
   *
   * @param sql the script
   * @return the script without comment lines
   */
  private static String stripComments(String sql) {
    return sql.lines().filter(line -> !line.stripLeading().startsWith("--")).reduce("", (a, b) -> a + "\n" + b);
  }
}
