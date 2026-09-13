/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

/**
 * Maps a PostgreSQL {@code jsonb} column to a Java {@link String}.
 *
 * <h2>Why the value stays text</h2>
 *
 * <p>The blocks that hold a {@code jsonb} column — {@code inventory.item.attributes},
 * {@code locations.location.attributes} — do not interpret it. What a key means belongs to
 * {@code catalog}, validation happens before the value reaches the aggregate, and mapping the column
 * to a parsed structure would invite the holder to start reasoning about fields it does not own
 * (04 §4.5). Text in, text out, and the one place that reads it as JSON is the one that owns the
 * definitions.
 *
 * <h2>Why a user type rather than an annotation</h2>
 *
 * <p>The same reason {@link LtreeType} exists, and its Javadoc names this case: binding a
 * {@link String} with {@link Types#OTHER} makes the PostgreSQL driver send the value as
 * <em>unknown</em> and lets the server resolve it against the column. {@code setString} would send
 * a {@code varchar}, and PostgreSQL refuses that against {@code jsonb} for lack of an implicit
 * cast — <em>column "attributes" is of type jsonb but expression is of type character varying</em>.
 *
 * <p>Hibernate's {@code @JdbcTypeCode(SqlTypes.JSON)} would also work and would tie the mapping to
 * one Hibernate version's opinion of what JSON is; this is forty lines that state exactly what is
 * sent.
 */
public class JsonbType implements UserType<String> {

  /** The JDBC type that makes the driver send the value as unknown, for the server to resolve. */
  @Override
  public int getSqlType() {
    return Types.OTHER;
  }

  @Override
  public Class<String> returnedClass() {
    return String.class;
  }

  @Override
  public boolean equals(String first, String second) {
    // Textual equality, not semantic: two documents differing only in key order are
    // different text, and this type is not a JSON comparator. Hibernate uses this to
    // decide whether a field is dirty, and a false "unchanged" would lose a write.
    return Objects.equals(first, second);
  }

  @Override
  public int hashCode(String value) {
    return Objects.hashCode(value);
  }

  @Override
  public String nullSafeGet(
      ResultSet resultSet, int position, SharedSessionContractImplementor session, Object owner)
      throws SQLException {
    return resultSet.getString(position);
  }

  @Override
  public void nullSafeSet(
      PreparedStatement statement, String value, int index, SharedSessionContractImplementor session)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.OTHER);
    } else {
      statement.setObject(index, value, Types.OTHER);
    }
  }

  @Override
  public String deepCopy(String value) {
    return value; // Strings are immutable; a copy would be the same object anyway.
  }

  @Override
  public boolean isMutable() {
    return false;
  }

  @Override
  public Serializable disassemble(String value) {
    return value;
  }

  @Override
  public String assemble(Serializable cached, Object owner) {
    return (String) cached;
  }
}
