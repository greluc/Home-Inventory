/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.InvalidCursorException;
import de.greluc.homeinv.platform.UrlSigningKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a cursor promises, without a database (REQ-SEC-106, REQ-SRCH-009).
 *
 * <p>The signing was covered only through {@code SearchAndCursorIT} until 2026-09-14 — which proved
 * that paging worked, not that a forged or borrowed cursor is refused. Those are the properties the
 * signature exists for, and they are cheap to state directly.
 *
 * <p>The separator case is the reason this class arrived when it did. A cursor now carries the value
 * the last row sorted by (`REQ-SRCH-004`), and that value comes out of a row: a name, a
 * manufacturer, anything a person typed. The payload is separated by {@code |}, so a sort value
 * containing one would split it into the wrong pieces — silently, and into a position that resumes
 * somewhere else. It is encoded for exactly that reason and the test below is what holds it.
 */
@DisplayName("A cursor")
class CursorCodecTest {

  private static final String QUERY = "a-query-fingerprint";

  @Test
  @DisplayName("survives a sort value containing the payload separator")
  void aSeparatorInTheSortValueDoesNotSplitThePayload(@TempDir Path directory) throws Exception {
    CursorCodec codec = codecIn(directory);
    UUID id = UUID.randomUUID();
    Instant at = Instant.parse("2026-09-14T10:15:30Z");

    // Every character that means something to the payload, in one value.
    String awkward = "Bosch | GSB 18 | 2.0 Ah";
    String cursor = codec.encode(new CursorCodec.Position(at, id, awkward), QUERY);

    CursorCodec.Position back = codec.decode(cursor, QUERY);
    assertThat(back.sortValue()).isEqualTo(awkward);
    assertThat(back.createdAt()).isEqualTo(at);
    assertThat(back.id()).isEqualTo(id);
  }

  @Test
  @DisplayName("round-trips a position in the default order, carrying no sort value")
  void theDefaultOrderCarriesNoSortValue(@TempDir Path directory) throws Exception {
    CursorCodec codec = codecIn(directory);
    UUID id = UUID.randomUUID();
    Instant at = Instant.parse("2026-09-14T10:15:30Z");

    CursorCodec.Position back =
        codec.decode(codec.encode(CursorCodec.Position.of(at, id), QUERY), QUERY);

    assertThat(back.sortValue()).as("null rather than an empty string, so the two never differ").isNull();
    assertThat(back.createdAt()).isEqualTo(at);
    assertThat(back.id()).isEqualTo(id);
  }

  @Test
  @DisplayName("is refused when it was edited, rather than resuming somewhere else")
  void anEditedCursorIsRefused(@TempDir Path directory) throws Exception {
    CursorCodec codec = codecIn(directory);
    String cursor =
        codec.encode(CursorCodec.Position.of(Instant.now(), UUID.randomUUID()), QUERY);

    // A changed payload with the original signature. This is the case the whole
    // mechanism exists for: an edited cursor does not fail on its own, it
    // quietly starts at a different row and the client never learns it skipped
    // some (REQ-SEC-106).
    String payload = cursor.substring(0, cursor.indexOf('.'));
    String signature = cursor.substring(cursor.indexOf('.'));
    String tampered =
        Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8)
                        .replace("2026", "2027")
                        .getBytes(StandardCharsets.UTF_8))
            + signature;

    assertThatThrownBy(() -> codec.decode(tampered, QUERY))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  @DisplayName("is refused when it belongs to another query (REQ-SRCH-009)")
  void aCursorFromAnotherQueryIsRefused(@TempDir Path directory) throws Exception {
    CursorCodec codec = codecIn(directory);
    String cursor =
        codec.encode(CursorCodec.Position.of(Instant.now(), UUID.randomUUID()), QUERY);

    // Intact, correctly signed, and for a different search. Paging one search
    // with another's cursor is meaningless, and answering anyway would be the
    // same silent wrongness one layer up.
    assertThatThrownBy(() -> codec.decode(cursor, "a-different-query"))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  @DisplayName("is refused when it is not a cursor at all")
  void rubbishIsRefused(@TempDir Path directory) throws Exception {
    CursorCodec codec = codecIn(directory);

    for (String nonsense : new String[] {"", ".", "no-separator", "!!.!!", "a.b.c"}) {
      assertThatThrownBy(() -> codec.decode(nonsense, QUERY))
          .as("%s is not a cursor", nonsense)
          .isInstanceOf(InvalidCursorException.class);
    }
  }

  // -------------------------------------------------------------------------

  /**
   * A codec over a throwaway key.
   *
   * <p>Generated into the temporary directory rather than committed: a key in a worktree is a key
   * that has leaked, whatever it protects.
   *
   * @param directory a per-test temporary directory
   * @return the codec
   * @throws Exception when the key cannot be written, which is the test failing
   */
  private CursorCodec codecIn(Path directory) throws Exception {
    byte[] material = new byte[32];
    new java.security.SecureRandom().nextBytes(material);
    Path keyFile = directory.resolve("cursor.key");
    Files.writeString(keyFile, Base64.getEncoder().encodeToString(material));
    return new CursorCodec(new UrlSigningKey(keyFile.toString()));
  }
}
