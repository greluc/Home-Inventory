/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.crypto.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The additional authenticated data is an injective encoding of its tuple (REQ-SEC-047).
 *
 * <p>The requirement asks for this in as many words, and it cannot be shown through the cipher: two
 * tuples that collided would produce one ciphertext that opened under both, which is exactly what a
 * test cannot observe. So the encoding is tested directly.
 *
 * <p>What makes it injective is the <b>length prefix</b> on the field key. Concatenation alone is
 * unambiguous today only because the one variable-length member sits last — so the first time
 * somebody appends a member to this AAD, two different tuples would begin producing the same bytes
 * and the guarantee the whole scheme rests on would be gone with no visible symptom.
 */
@DisplayName("The encryption's authenticated data")
class EnvelopeAadTest {

  private static final UUID TENANT = UUID.fromString("018f2f4a-0000-7000-8000-00000000a1a1");
  private static final UUID OTHER_TENANT = UUID.fromString("018f2f4a-0000-7000-8000-00000000b2b2");
  private static final UUID ENTITY = UUID.fromString("018f2f4a-0000-7000-8000-00000000c3c3");
  private static final UUID OTHER_ENTITY = UUID.fromString("018f2f4a-0000-7000-8000-00000000d4d4");

  @Test
  @DisplayName("differs whenever any member of the tuple differs")
  void everyMemberIsLoadBearing() {
    List<byte[]> encodings = new ArrayList<>();
    for (int dekId : new int[] {1, 2}) {
      for (UUID tenant : new UUID[] {TENANT, OTHER_TENANT}) {
        for (UUID entity : new UUID[] {ENTITY, OTHER_ENTITY}) {
          // The pairs that would collide under a plain concatenation IF the field
          // key were not last, and which will collide the moment a member is
          // appended after it: "ab" + "" against "a" + "b".
          for (String field : new String[] {"", "a", "b", "ab", "ab ", " ab", "licenceKey"}) {
            encodings.add(EnvelopeCrypto.aad(dekId, tenant, entity, field));
          }
        }
      }
    }

    Set<String> distinct = new HashSet<>();
    for (byte[] encoding : encodings) {
      distinct.add(java.util.HexFormat.of().formatHex(encoding));
    }
    assertThat(distinct).as("no two tuples share an encoding").hasSize(encodings.size());
  }

  @Test
  @DisplayName("prefixes the field key with its length, which is what keeps it injective")
  void theFieldKeyIsLengthPrefixed() {
    String field = "licenceKey";
    byte[] encoding = EnvelopeCrypto.aad(1, TENANT, ENTITY, field);

    // version(1) + dek_id(1) + tenantId(16) + entityId(16) = 34, then the length.
    ByteBuffer buffer = ByteBuffer.wrap(encoding);
    assertThat(buffer.get()).as("format version").isEqualTo((byte) 0x01);
    assertThat(buffer.get()).as("data key version").isEqualTo((byte) 1);
    assertThat(buffer.getLong()).isEqualTo(TENANT.getMostSignificantBits());
    assertThat(buffer.getLong()).isEqualTo(TENANT.getLeastSignificantBits());
    assertThat(buffer.getLong()).isEqualTo(ENTITY.getMostSignificantBits());
    assertThat(buffer.getLong()).isEqualTo(ENTITY.getLeastSignificantBits());
    assertThat(buffer.getShort()).as("the length prefix").isEqualTo((short) field.length());

    byte[] rest = new byte[buffer.remaining()];
    buffer.get(rest);
    assertThat(new String(rest, StandardCharsets.UTF_8)).isEqualTo(field);
  }
}
