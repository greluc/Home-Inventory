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
          "identification.public_code",
          "identification.label_base_url_usage",
          "audit.chain_anchor",
          // Infrastructure rather than domain data (07 §7.1, rule 4): the relay
          // reads every tenant's rows by design, and a policy would hide them
          // from the one process whose job is to publish them.
          "outbox.event_publication");

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
    Pattern singleColumn =
        Pattern.compile(
            "foreign\\s+key\\s*\\(\\s*[a-z_]+\\s*\\)\\s*references", Pattern.CASE_INSENSITIVE);
    Pattern inlineReference =
        Pattern.compile("^\\s*[a-z_]+\\s+uuid[^,]*\\breferences\\b", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    List<String> offenders = new ArrayList<>();
    for (Path script : scripts()) {
      String text = Files.readString(script, StandardCharsets.UTF_8);
      String withoutComments = stripComments(text);
      if (singleColumn.matcher(withoutComments).find()
          || inlineReference.matcher(withoutComments).find()) {
        // The one documented exception: membership -> app_user, single-column
        // because app_user is instance-wide and has no tenant to carry.
        if (!script.getFileName().toString().contains("tenant_and_membership")) {
          offenders.add(script.getFileName().toString());
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
