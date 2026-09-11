/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

/**
 * Maps a PostgreSQL {@code ltree} column to a Java {@link String}.
 *
 * <p>Hibernate has no {@code ltree}, and the two obvious shortcuts both fail in ways that are worth
 * recording, because each looks like it works until it does not:
 *
 * <ul>
 *   <li>Mapping the field as {@code VARCHAR} writes a {@code varchar} parameter, and PostgreSQL
 *       refuses it: <em>column "path" is of type ltree but expression is of type character
 *       varying</em>. There is no implicit cast.
 *   <li>Mapping it with {@code @JdbcTypeCode(SqlTypes.OTHER)} makes Hibernate treat the value as an
 *       arbitrary object and Java-serialise it, so the parameter arrives as {@code bytea}.
 * </ul>
 *
 * <p>What does work is binding the {@link String} with {@link Types#OTHER}: the PostgreSQL driver
 * then sends it as an <em>unknown</em> type and the server resolves it against the column, which is
 * the same mechanism that makes {@code jsonb} parameters work. Reading is unremarkable — the driver
 * hands back the textual form.
 *
 * <p>Lives in {@code platform} because it is infrastructure no block owns, and because a second
 * {@code ltree} column anywhere should use this rather than rediscover the above.
 */
public class LtreeType implements UserType<String> {

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
      // setObject with OTHER, not setString: setString would send a varchar and
      // the server would refuse it for lack of an implicit cast.
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
  public java.io.Serializable disassemble(String value) {
    return value;
  }

  @Override
  public String assemble(java.io.Serializable cached, Object owner) {
    return (String) cached;
  }
}
