/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The isolation proof {@code REQ-SEC-007} asks for — and the second line {@code REQ-TEN-001} promises, which is the same proof seen from the other side: all data belongs to a tenant and the separation holds independently of the application logic —: <b>every</b> table, two tenants, and a wrongly
 * set context.
 *
 * <h2>Why this exists next to the tests that were already here</h2>
 *
 * <p>Two halves of the property were covered and the middle was not.
 * {@code MigrationRulesTest} reads the migration SQL and proves every domain table <em>declares</em>
 * {@code tenant_id} and row-level security with {@code FORCE} — a new table without RLS fails the
 * build, which is {@code CLAUDE.md} rule 2's first clause. {@code TenantIsolationIT} proves the
 * behaviour end to end through HTTP — for {@code inventory.item}, and for nothing else.
 *
 * <p>What neither covers is the other nine tenant-scoped tables at the level the requirement names:
 * live rows, two tenants, and a context that is wrong or missing. A declaration is not an
 * observation — a policy can be declared and be wrong, and {@code FORCE} can be declared on a table
 * whose policy compares the wrong column.
 *
 * <h2>Why it seeds as the superuser</h2>
 *
 * <p>Because a proof that no rows are visible is worthless over an empty table, and most of these
 * tables have no stage-0 write path through the application: {@code item_type} and
 * {@code item_type_version} belong to the type system, which is stage 1, and {@code media_variant}
 * is written by a derivation this test does not run. Seeding through the application would therefore
 * cover what the application happens to write today, which is the coverage this test exists to stop
 * relying on.
 *
 * <p>So the rows are written by the container's superuser, which bypasses RLS, and every assertion
 * afterwards is made as {@code homeinv_app}, which does not. <b>A tenant-scoped table with no rows
 * fails this test</b> — that is what stops a table added later from passing silently, and it is the
 * reason the seeder is generic rather than a list of inserts somebody has to remember to extend.
 *
 * <h2>What the seeder knows</h2>
 *
 * <p>Nothing table-specific. It reads columns, foreign keys and check constraints from the
 * catalogue and derives a value that satisfies them: the first literal of an
 * {@code = ANY (ARRAY[…])} constraint, a non-empty string for {@code length(btrim(x)) > 0}, a
 * 64-character hex string for a {@code ~ '^[0-9a-f]{64}$'} constraint, a positive number where one
 * is demanded. Where it cannot, the test fails and says which column — which is the signal to teach
 * it, not to exempt the table.
 */
@DisplayName("The isolation proof")
class TenantIsolationProofIT extends AbstractIntegrationTest {

  /** Schemas that hold no domain data: Flyway's history and PostgreSQL's own. */
  private static final Set<String> NOT_DOMAIN = Set.of("flyway", "pg_catalog", "information_schema");

  private static final Pattern ANY_ARRAY = Pattern.compile("'([^']+)'::");

  /** The regular expression of a {@code column ~ 'pattern'} check, as pg prints it. */
  private static final Pattern REGEX_CHECK = Pattern.compile("~ '([^']+)'");

  /** The constraint PostgreSQL names when it refuses a row. */
  private static final Pattern REFUSED_CONSTRAINT =
      Pattern.compile("violates check constraint \"([^\"]+)\"");

  /** The two columns of an {@code a <> b} check, which ask for two different rows. */
  private static final Pattern DISTINCT = Pattern.compile("\\((\\w+) <> (\\w+)\\)");

  /** The columns of a {@code num_nonnulls(a, b) = 1} check. */
  private static final Pattern ONE_OF =
      Pattern.compile("num_nonnulls\\(([^)]*)\\)\\s*=\\s*1");
  /** The lower bound of a {@code BETWEEN a AND b} check, as pg prints it. */
  private static final Pattern BETWEEN =
      Pattern.compile(">=\\s*\\(?(-?\\d+)\\)?\\s*\\)?\\s*AND", Pattern.CASE_INSENSITIVE);

  private static final Pattern HEX_LENGTH = Pattern.compile("\\^\\[0-9a-f]\\{(\\d+)}\\$");

  /**
   * The length of a {@code length(x) = n} check, as pg prints it.
   *
   * <p>A digest column says its size exactly rather than as a range — {@code audit.audit_entry}'s
   * two hashes and {@code audit.chain_truncation.oldest_hash} are all SHA-256 and all 32 bytes. The
   * seeder knew {@code BETWEEN} and not this, so the first table to use the exact form failed the
   * proof by being unseedable rather than by being unisolated.
   */
  private static final Pattern EXACT_LENGTH =
      Pattern.compile("length\\([^)]*\\)\\s*=\\s*\\(?(\\d+)\\)?", Pattern.CASE_INSENSITIVE);

  @Autowired private JdbcClient jdbc;
  @Autowired private TransactionTemplate transactions;

  @Test
  @DisplayName("every tenant-scoped table hides one tenant's rows from another, "
      + "and from none (REQ-SEC-007)")
  void everyTableIsIsolated() throws SQLException {
    UUID alpha = UUID.randomUUID();
    UUID beta = UUID.randomUUID();

    List<String> tenantScoped;
    try (Connection superuser = asSuperuser()) {
      tenantScoped = tenantScopedTables(superuser);

      // If this ever finds nothing, the discovery is broken and every assertion
      // below would pass over an empty list — the shape of vacuous proof this
      // test exists to avoid.
      assertThat(tenantScoped)
          .as("tables with row-level security and FORCE, discovered from the catalogue")
          .isNotEmpty();

      Map<String, Map<UUID, UUID>> seeded = new HashMap<>();
      for (String table : inDependencyOrder(superuser, tenantScoped)) {
        for (UUID tenant : List.of(alpha, beta)) {
          seedRow(superuser, table, tenant, seeded);
        }
      }
    }

    for (String table : tenantScoped) {
      long ownRows = count(alpha, "select count(*) from " + table);
      assertThat(ownRows)
          .as("%s holds a row for the tenant whose context is set — without one, "
              + "'no foreign rows are visible' proves nothing", table)
          .isPositive();

      // The wrong context: every row the other tenant owns is invisible, and the
      // count is not merely filtered by the query — the query asks for everything.
      assertThat(count(beta, "select count(*) from " + table + " where tenant_id = '" + alpha + "'"))
          .as("%s: tenant beta can see alpha's rows", table)
          .isZero();
      assertThat(count(alpha, "select count(*) from " + table + " where tenant_id = '" + beta + "'"))
          .as("%s: tenant alpha can see beta's rows", table)
          .isZero();

      // No context at all: zero rows rather than every row. A missing context is
      // the case that decides whether a bug is a bug or a breach (CLAUDE.md rule 2).
      assertThat(countWithoutContext("select count(*) from " + table))
          .as("%s: rows are visible with no tenant context set", table)
          .isZero();
    }
  }

  @Test
  @DisplayName("refuses to write a row belonging to another tenant (REQ-SEC-007)")
  void writingAcrossTenantsIsRefused() throws SQLException {
    UUID alpha = UUID.randomUUID();
    UUID beta = UUID.randomUUID();

    try (Connection superuser = asSuperuser()) {
      Map<String, Map<UUID, UUID>> seeded = new HashMap<>();
      for (String table : inDependencyOrder(superuser, tenantScopedTables(superuser))) {
        seedRow(superuser, table, alpha, seeded);
      }
    }

    // `tenancy.tenant` is the simplest row that needs nothing else to exist, which
    // makes it the honest one to attempt: a refusal here is the policy's WITH CHECK
    // clause and not a foreign key getting there first.
    //
    // The `id` is what is written, not `tenant_id` — that column is
    // `GENERATED ALWAYS AS (id)`, so writing it is a grammar error and would prove
    // nothing about the policy. Giving the row beta's id gives it beta's tenant_id.
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    status -> {
                      jdbc.sql("select set_config('app.tenant_id', ?, true)")
                          .param(alpha.toString())
                          .query()
                          .singleValue();
                      jdbc.sql("insert into tenancy.tenant (id, name) values (?::uuid, ?)")
                          .param(beta.toString())
                          .param("a tenant this caller does not own")
                          .update();
                    }))
        .as("the WITH CHECK half of the policy: a row written under alpha's context "
            + "carrying beta's tenant_id")
        // In the CAUSE, not the message. PostgreSQL answers a policy violation with
        // SQLState 42501, `insufficient_privilege`, which Spring translates to
        // `BadSqlGrammarException` — a name that describes the state code's usual
        // meaning rather than this one. The database's own words are what is asserted.
        .hasStackTraceContaining("violates row-level security policy");
  }

  // -------------------------------------------------------------------------

  private Connection asSuperuser() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /**
   * Every table the database itself reports as tenant-scoped.
   *
   * <p>From the catalogue and not from a list in this file: a list is the thing that goes stale when
   * a table is added, which is exactly what this test is for.
   *
   * @param superuser a connection that may read the catalogue
   * @return qualified table names, ordered
   * @throws SQLException when the catalogue cannot be read
   */
  private List<String> tenantScopedTables(Connection superuser) throws SQLException {
    List<String> tables = new ArrayList<>();
    String sql =
        "select n.nspname, c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace "
            + "where c.relkind = 'r' and c.relrowsecurity and c.relforcerowsecurity "
            + "and n.nspname not like 'pg\\_%' order by 1, 2";
    try (Statement statement = superuser.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        if (!NOT_DOMAIN.contains(rows.getString(1))) {
          tables.add(rows.getString(1) + "." + rows.getString(2));
        }
      }
    }
    return tables;
  }

  /**
   * The tables to seed, parents before children.
   *
   * <p>The closure includes tables that are <em>not</em> tenant-scoped when something tenant-scoped
   * references them — {@code tenancy.membership} needs an {@code identity.app_user}, and that table
   * is instance-wide by design (07 §7.1, rule 4).
   *
   * @param superuser a connection that may read the catalogue
   * @param tables the tenant-scoped tables to start from
   * @return every table to seed, in an order that satisfies the foreign keys
   * @throws SQLException when the catalogue cannot be read
   */
  private List<String> inDependencyOrder(Connection superuser, List<String> tables)
      throws SQLException {
    Map<String, Set<String>> dependsOn = new LinkedHashMap<>();
    Set<String> pending = new LinkedHashSet<>(tables);
    while (!pending.isEmpty()) {
      String table = pending.iterator().next();
      pending.remove(table);
      Set<String> parents = new LinkedHashSet<>();
      for (ForeignKey key : foreignKeys(superuser, table)) {
        if (!key.target().equals(table)) {
          parents.add(key.target());
          if (!dependsOn.containsKey(key.target())) {
            pending.add(key.target());
          }
        }
      }
      dependsOn.put(table, parents);
    }

    List<String> ordered = new ArrayList<>();
    Set<String> placed = new HashSet<>();
    while (ordered.size() < dependsOn.size()) {
      boolean progressed = false;
      for (Map.Entry<String, Set<String>> entry : dependsOn.entrySet()) {
        if (placed.contains(entry.getKey())) {
          continue;
        }
        if (placed.containsAll(entry.getValue())) {
          ordered.add(entry.getKey());
          placed.add(entry.getKey());
          progressed = true;
        }
      }
      if (!progressed) {
        // A cycle between tables. Nothing in this schema has one, and if one
        // appears the seeder has to be told how to break it rather than guessing.
        throw new IllegalStateException(
            "A foreign-key cycle among " + dependsOn.keySet() + "; the seeder cannot order them");
      }
    }
    return ordered;
  }

  /**
   * Writes one row, deriving every value from the catalogue.
   *
   * @param superuser the connection to write with, which bypasses row-level security
   * @param table the qualified table name
   * @param tenant the tenant the row belongs to, ignored for an instance-wide table
   * @param seeded ids already written, per table and tenant, for foreign keys to point at
   * @throws SQLException when the row cannot be written
   */
  private void seedRow(
      Connection superuser, String table, UUID tenant, Map<String, Map<UUID, UUID>> seeded)
      throws SQLException {
    Map<UUID, UUID> perTenant = seeded.computeIfAbsent(table, ignored -> new HashMap<>());
    if (perTenant.containsKey(tenant)) {
      return;
    }

    Map<String, String> checks = checkClauses(superuser, table);
    Map<String, String> foreignTargets = new HashMap<>();
    for (ForeignKey key : foreignKeys(superuser, table)) {
      for (String column : key.columns()) {
        if (!"tenant_id".equals(column)) {
          foreignTargets.put(column, key.target());
        }
      }
    }

    UUID id = UUID.randomUUID();
    List<String> columns = new ArrayList<>();
    List<String> values = new ArrayList<>();

    for (Column column : requiredColumns(superuser, table)) {
      columns.add(column.name());
      values.add(valueFor(table, column, tenant, id, checks, foreignTargets, seeded));
    }
    // Nullable foreign keys are filled too, where their target is already seeded.
    // Not thoroughness for its own sake: `inventory.item` carries
    // `CHECK (kind <> 'PHYSICAL' OR location_id IS NOT NULL OR deleted_at IS NOT NULL)`,
    // so a row whose `kind` came from the first literal of an enum check needs the
    // nullable `location_id` to satisfy the row-level check. A SELF-reference is
    // left null on purpose — `locations.location.parent_id` is what makes a row a
    // root, and `(depth = 0) = (parent_id IS NULL)` says so.
    for (Map.Entry<String, String> reference : foreignTargets.entrySet()) {
      if (columns.contains(reference.getKey()) || reference.getValue().equals(table)) {
        continue;
      }
      Map<UUID, UUID> rows = seeded.getOrDefault(reference.getValue(), Map.of());
      UUID parent = rows.getOrDefault(tenant, rows.values().stream().findFirst().orElse(null));
      if (parent != null) {
        columns.add(reference.getKey());
        values.add("'" + parent + "'");
      }
    }

    if (hasColumn(superuser, table, "id") && columns.stream().noneMatch("id"::equals)) {
      columns.add("id");
      // Where `tenant_id` is generated from `id`, the row belongs to the tenant
      // whose id it carries — so seeding it for a given tenant means giving it
      // that tenant's id. Read from the catalogue rather than special-cased by
      // name: any table built that way is handled.
      values.add("'" + (generatesTenantIdFromId(superuser, table) ? tenant : id) + "'");
    }

    // The nullable columns above are filled optimistically, and some tables say
    // which combinations are legal: `field_definition` carries exactly one owner,
    // and its value list belongs to enumerations only. Rather than teaching the
    // seeder each rule, the row is offered to PostgreSQL and narrowed by whatever
    // it names — which is the one description of those rules that cannot drift.
    for (int attempt = 0; ; attempt++) {
      String sql =
          "insert into " + table + " (" + String.join(", ", columns) + ") values ("
              + String.join(", ", values) + ")";
      try (Statement statement = superuser.createStatement()) {
        statement.executeUpdate(sql);
        break;
      } catch (SQLException refused) {
        // Read from the message rather than from the driver's own exception type:
        // `org.postgresql.util` is a runtime dependency here, and a test that
        // imported it would pull the driver onto the compile classpath to learn
        // one word PostgreSQL already puts in the text.
        Matcher named = REFUSED_CONSTRAINT.matcher(String.valueOf(refused.getMessage()));
        String constraint = named.find() ? named.group(1) : null;
        if (attempt >= 3
            || constraint == null
            || !relax(
                superuser, table, constraint, columns, values, tenant, foreignTargets, seeded)) {
          throw new AssertionError(
              "The isolation proof could not seed " + table + ". Teach the seeder the column it "
                  + "could not derive a value for rather than exempting the table — an unseeded "
                  + "table makes this proof vacuous for it.\n  " + sql + "\n  "
                  + refused.getMessage(),
              refused);
        }
      }
    }
    perTenant.put(tenant, id);
  }

  /**
   * Changes the row so that the check PostgreSQL refused on accepts it, so it can be offered again.
   *
   * <p>Three shapes are understood, and all three come from the catalogue rather than from a list
   * here. {@code a <> b} points the second of its two columns at a second row of whatever it
   * references, because that check exists to say the two ends are different things;
   * {@code num_nonnulls(a, b) = 1} keeps the first of its columns that the row carries and drops the
   * rest; anything else drops every nullable column the clause names. A clause that fits none of
   * them cannot be satisfied by changing anything here, and this says so by changing nothing — which
   * surfaces as the original failure with its own message.
   *
   * @param superuser the connection that may read the catalogue
   * @param table the qualified table name
   * @param constraint the constraint PostgreSQL refused on
   * @param columns the columns of the pending insert, modified in place
   * @param values their values, kept in step with the columns
   * @param tenant the tenant the row belongs to
   * @param foreignTargets the table each foreign-key column points at
   * @param seeded ids already written, per table and tenant
   * @return whether anything changed, and a retry is therefore worth making
   * @throws SQLException when the catalogue cannot be read
   */
  private boolean relax(
      Connection superuser,
      String table,
      String constraint,
      List<String> columns,
      List<String> values,
      UUID tenant,
      Map<String, String> foreignTargets,
      Map<String, Map<UUID, UUID>> seeded)
      throws SQLException {

    String clause = constraintDefinition(superuser, table, constraint);
    if (clause == null) {
      return false;
    }

    // `source_id <> target_id`: the row wants two DIFFERENT rows of the table those
    // columns reference, and the seeder keeps exactly one per table and tenant. A
    // second one is written for the occasion and the later column is pointed at it,
    // which is what `inventory.item_relation` asks for and what any other table
    // saying "these two ends are not the same thing" will ask for too.
    Matcher distinct = DISTINCT.matcher(clause);
    while (distinct.find()) {
      String second = distinct.group(2);
      int at = columns.indexOf(second);
      String target = foreignTargets.get(second);
      if (at >= 0 && target != null && columns.contains(distinct.group(1))) {
        values.set(at, "'" + seedAnotherRow(superuser, target, tenant, seeded) + "'");
        return true;
      }
    }

    Matcher oneOf = ONE_OF.matcher(clause);
    if (oneOf.find()) {
      List<String> named = Arrays.stream(oneOf.group(1).split(",")).map(String::trim).toList();
      boolean kept = false;
      boolean changed = false;
      for (String column : named) {
        int at = columns.indexOf(column);
        if (at < 0) {
          continue;
        }
        if (!kept) {
          kept = true;
          continue;
        }
        columns.remove(at);
        values.remove(at);
        changed = true;
      }
      return changed;
    }

    Set<String> nullable = nullableColumns(superuser, table);
    boolean changed = false;
    for (String column : new ArrayList<>(columns)) {
      if (nullable.contains(column) && clause.contains(column)) {
        int at = columns.indexOf(column);
        columns.remove(at);
        values.remove(at);
        changed = true;
      }
    }
    return changed;
  }

  /**
   * Writes one more row of a table, beside the one the seeder keeps for this tenant.
   *
   * <p>For the rows that reference two different rows of the same table. The map of seeded ids holds
   * one per table and tenant, because that is all a foreign key needs; this borrows the same
   * machinery to make a second, and leaves the map pointing at the first, so that nothing else
   * changes.
   *
   * @param superuser the connection to write with
   * @param table the qualified table name
   * @param tenant the tenant the row belongs to
   * @param seeded ids already written, per table and tenant
   * @return the id of the extra row
   * @throws SQLException when the row cannot be written
   */
  private UUID seedAnotherRow(
      Connection superuser, String table, UUID tenant, Map<String, Map<UUID, UUID>> seeded)
      throws SQLException {
    Map<UUID, UUID> perTenant = seeded.computeIfAbsent(table, ignored -> new HashMap<>());
    UUID first = perTenant.remove(tenant);
    seedRow(superuser, table, tenant, seeded);
    UUID extra = perTenant.get(tenant);
    if (first != null) {
      perTenant.put(tenant, first);
    }
    return extra;
  }

  /**
   * The definition of one named constraint.
   *
   * @param superuser the connection that may read the catalogue
   * @param table the qualified table name
   * @param constraint the constraint name
   * @return the clause as PostgreSQL prints it, or {@code null} when there is no such constraint
   * @throws SQLException when the catalogue cannot be read
   */
  private String constraintDefinition(Connection superuser, String table, String constraint)
      throws SQLException {
    String sql =
        "select pg_get_constraintdef(oid) from pg_constraint "
            + "where conrelid = '" + table + "'::regclass and conname = '" + constraint + "'";
    try (Statement statement = superuser.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      return rows.next() ? rows.getString(1) : null;
    }
  }

  /**
   * Which of a table's columns accept null.
   *
   * @param superuser the connection that may read the catalogue
   * @param table the qualified table name
   * @return the nullable column names
   * @throws SQLException when the catalogue cannot be read
   */
  private Set<String> nullableColumns(Connection superuser, String table) throws SQLException {
    String[] parts = table.split("\\.");
    Set<String> nullable = new LinkedHashSet<>();
    String sql =
        "select column_name from information_schema.columns where table_schema = '"
            + parts[0] + "' and table_name = '" + parts[1] + "' and is_nullable = 'YES'";
    try (Statement statement = superuser.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        nullable.add(rows.getString(1));
      }
    }
    return nullable;
  }

  /**
   * A value that satisfies the column's type and every check constraint naming it.
   *
   * @param table the table, for the error message
   * @param column the column
   * @param tenant the tenant the row belongs to
   * @param id the row's own id, so a self-referencing path can use it
   * @param checks check clauses by the column they name
   * @param foreignTargets the table each foreign-key column points at
   * @param seeded ids already written
   * @return a SQL literal
   */
  private String valueFor(
      String table,
      Column column,
      UUID tenant,
      UUID id,
      Map<String, String> checks,
      Map<String, String> foreignTargets,
      Map<String, Map<UUID, UUID>> seeded) {

    if ("tenant_id".equals(column.name())) {
      return "'" + tenant + "'";
    }
    String target = foreignTargets.get(column.name());
    if (target != null) {
      Map<UUID, UUID> rows = seeded.getOrDefault(target, Map.of());
      // An instance-wide parent has one row under the first tenant seeded; a
      // tenant-scoped one has a row per tenant, and the composite key means it
      // must be this tenant's.
      UUID parent = rows.getOrDefault(tenant, rows.values().stream().findFirst().orElse(null));
      if (parent == null) {
        throw new AssertionError(
            table + "." + column.name() + " points at " + target + ", which has not been seeded");
      }
      return "'" + parent + "'";
    }

    String check = checks.getOrDefault(column.name(), "");
    return switch (column.type().toLowerCase(Locale.ROOT)) {
      case "uuid" -> "'" + UUID.randomUUID() + "'";
      case "timestamp with time zone", "timestamp without time zone" -> "now()";
      case "boolean" -> "false";
      // Zero unless a check demands more. `location.depth` must be 0 for the row to
      // be a root — `nlevel(path) = depth + 1` with one label, and
      // `(depth = 0) = (parent_id IS NULL)` with no parent — while `byte_size` and
      // the pixel dimensions carry `> 0`. Both are read off the constraint rather
      // than guessed, which is what keeps this generic.
      case "integer", "bigint", "smallint" -> numberValue(check);
      case "numeric", "double precision", "real" -> numberValue(check);
      case "jsonb", "json" -> "'{}'::jsonb";
      case "bytea" -> byteaValue(check);
      case "ltree" -> "text2ltree('n' + '')";
      // `USER-DEFINED` is what information_schema calls a domain or an extension
      // type; here that is `ltree`, and the only one is `location.path`, whose
      // check ties it to `depth`. One label, depth zero, no parent.
      case "user-defined" -> "text2ltree('n" + id.toString().replace("-", "") + "')";
      default -> textValue(check, id);
    };
  }

  /**
   * A string the column's check constraint accepts, and that no other row already has.
   *
   * <p>Uniqueness matters as much as the check does: these tables carry unique indexes —
   * {@code app_user_email_unique}, {@code location_sibling_name}, the per-tenant content address —
   * and two rows seeded from one literal collide on them. The row's own id is what makes each value
   * its own, so the values stay derived rather than counted out.
   *
   * @param check the check clause naming this column, or an empty string
   * @param id the row's id, which makes the value unique
   * @return a SQL string literal
   */
  private String textValue(String check, UUID id) {
    String unique = id.toString().replace("-", "");
    Matcher hex = HEX_LENGTH.matcher(check);
    if (hex.find()) {
      int length = Integer.parseInt(hex.group(1));
      return "'" + unique.repeat(length / unique.length() + 1).substring(0, length) + "'";
    }
    Matcher regex = REGEX_CHECK.matcher(check);
    if (regex.find()) {
      // Verified rather than guessed: the candidates are tried against the column's
      // own expression and the first that matches wins. A shape none of them fits
      // fails here by name, which is the signal to add a candidate.
      String expression = regex.group(1);
      for (String candidate : List.of("ip" + unique, unique, "isolation-proof-" + unique)) {
        if (candidate.matches(expression)) {
          return "'" + candidate + "'";
        }
      }
      throw new AssertionError(
          "The isolation proof has no seed value matching " + expression + ". Add one to the "
              + "candidates in textValue rather than relaxing the constraint.");
    }
    if (check.contains("= ANY")) {
      // An enumerated column takes one of its literals exactly; nothing may be
      // appended to it, so a table with a unique index over an enum would need a
      // second value rather than a longer one. None has.
      Matcher literal = ANY_ARRAY.matcher(check);
      if (literal.find()) {
        return "'" + literal.group(1) + "'";
      }
    }
    return "'isolation-proof-" + unique + "'";
  }

  /**
   * A number the column's check accepts.
   *
   * <p>Zero unless a check demands otherwise. {@code location.depth} must be 0 for the row to be a
   * root — {@code nlevel(path) = depth + 1} with one label, and {@code (depth = 0) = (parent_id IS
   * NULL)} with no parent — while {@code byte_size} and the pixel dimensions carry {@code > 0}, and
   * {@code crypto.tenant_data_key.kek_version} carries a {@code BETWEEN 1 AND 255}. All of them are
   * read off the constraint rather than guessed, which is what keeps this generic.
   *
   * @param check the check clause naming this column, or an empty string
   * @return a SQL numeric literal
   */
  private static String numberValue(String check) {
    Matcher between = BETWEEN.matcher(check);
    if (between.find()) {
      return between.group(1);
    }
    return check.contains("> 0") ? "1" : "0";
  }

  /**
   * Bytes the column's check accepts.
   *
   * <p>One zero byte unless a length is demanded, in which case exactly the lower bound of it.
   * {@code crypto.tenant_data_key.wrapped_dek} is the case that asked for this: a wrapped AES-256
   * key is a nonce, a ciphertext and a tag, and the constraint says so rather than accepting
   * whatever length a row happens to carry.
   *
   * @param check the check clause naming this column, or an empty string
   * @return a SQL bytea literal
   */
  private static String byteaValue(String check) {
    Matcher between = BETWEEN.matcher(check);
    if (between.find()) {
      return "decode(repeat('00', " + between.group(1) + "), 'hex')";
    }
    Matcher exact = EXACT_LENGTH.matcher(check);
    if (exact.find()) {
      return "decode(repeat('00', " + exact.group(1) + "), 'hex')";
    }
    return "'\\x00'::bytea";
  }

  private record Column(String name, String type) {}

  private record ForeignKey(List<String> columns, String target) {}

  private List<Column> requiredColumns(Connection superuser, String table) throws SQLException {
    List<Column> columns = new ArrayList<>();
    String[] parts = table.split("\\.");
    String sql =
        "select column_name, data_type from information_schema.columns "
            + "where table_schema = '" + parts[0] + "' and table_name = '" + parts[1] + "' "
            + "and is_nullable = 'NO' and column_default is null "
            // A generated column is computed, and writing one is an error rather
            // than a value. `tenancy.tenant.tenant_id` is `GENERATED ALWAYS AS (id)`:
            // a tenant's identity IS the tenant id, which is why the row's own id is
            // set to the tenant below.
            + "and is_generated <> 'ALWAYS' order by ordinal_position";
    try (Statement statement = superuser.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        columns.add(new Column(rows.getString(1), rows.getString(2)));
      }
    }
    return columns;
  }

  /**
   * Whether the table's {@code tenant_id} is computed from its {@code id}.
   *
   * @param superuser a connection that may read the catalogue
   * @param table the qualified table name
   * @return {@code true} when `tenant_id` is `GENERATED ALWAYS AS (id)`
   * @throws SQLException when the catalogue cannot be read
   */
  private boolean generatesTenantIdFromId(Connection superuser, String table) throws SQLException {
    String[] parts = table.split("\\.");
    String sql =
        "select generation_expression from information_schema.columns where table_schema = '"
            + parts[0] + "' and table_name = '" + parts[1] + "' and column_name = 'tenant_id' "
            + "and is_generated = 'ALWAYS'";
    try (Statement statement = superuser.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      return rows.next() && "id".equals(rows.getString(1));
    }
  }

  private boolean hasColumn(Connection superuser, String table, String column) throws SQLException {
    String[] parts = table.split("\\.");
    String sql =
        "select count(*) from information_schema.columns where table_schema = '" + parts[0]
            + "' and table_name = '" + parts[1] + "' and column_name = '" + column + "'";
    try (Statement statement = superuser.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      return rows.next() && rows.getInt(1) > 0;
    }
  }

  private Map<String, String> checkClauses(Connection superuser, String table) throws SQLException {
    Map<String, String> clauses = new HashMap<>();
    String sql =
        "select a.attname, pg_get_constraintdef(c.oid) from pg_constraint c "
            + "join unnest(c.conkey) as k(attnum) on true "
            + "join pg_attribute a on a.attrelid = c.conrelid and a.attnum = k.attnum "
            + "where c.contype = 'c' and c.conrelid = '" + table + "'::regclass";
    try (Statement statement = superuser.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        clauses.merge(rows.getString(1), rows.getString(2), (first, second) -> first + " " + second);
      }
    }
    return clauses;
  }

  private List<ForeignKey> foreignKeys(Connection superuser, String table) throws SQLException {
    List<ForeignKey> keys = new ArrayList<>();
    String sql =
        "select c.conname, c.confrelid::regclass::text, "
            + "  (select array_agg(a.attname order by k.ordinality) "
            + "     from unnest(c.conkey) with ordinality as k(attnum, ordinality) "
            + "     join pg_attribute a on a.attrelid = c.conrelid and a.attnum = k.attnum) "
            + "from pg_constraint c where c.contype = 'f' and c.conrelid = '" + table + "'::regclass";
    try (Statement statement = superuser.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        String target = rows.getString(2);
        String[] columns = (String[]) rows.getArray(3).getArray();
        keys.add(new ForeignKey(List.of(columns), qualified(target)));
      }
    }
    return keys;
  }

  /**
   * Qualifies a name {@code regclass} printed without a schema because it was on the search path.
   *
   * @param name the name as printed
   * @return the same name, schema-qualified
   */
  private String qualified(String name) {
    return name.contains(".") ? name : "public." + name;
  }

  private long count(UUID tenant, String sql) {
    return transactions.execute(
        status -> {
          jdbc.sql("select set_config('app.tenant_id', ?, true)")
              .param(tenant.toString())
              .query()
              .singleValue();
          return jdbc.sql(sql).query(Long.class).single();
        });
  }

  private long countWithoutContext(String sql) {
    return transactions.execute(
        status -> {
          jdbc.sql("select set_config('app.tenant_id', '', true)").query().singleValue();
          return jdbc.sql(sql).query(Long.class).single();
        });
  }
}
