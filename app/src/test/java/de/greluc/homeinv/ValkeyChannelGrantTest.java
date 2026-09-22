/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.eventstream.application.LiveChanges;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every Valkey channel the application uses is one the deployment's ACL grants (REQ-SEC-104).
 *
 * <h2>Why a test and not a comment</h2>
 *
 * <p>{@code internal} is a network and not a trust boundary (ADR-0044): Valkey authenticates its
 * caller by ACL, and <b>an ACL grants no pub/sub channel at all by default</b>. A channel the
 * application publishes to and the ACL does not name is not a degraded feature — the listener fails
 * to start, the context refresh is cancelled, and the container exits.
 *
 * <p>That happened on 2026-09-21: the live-change nudge of {@code REQ-API-011} shipped with a
 * channel nothing granted, and the rootless smoke suite found it under both runtimes. The unit
 * tests could not: they run against a Valkey with no ACL at all, which is the right trade for a
 * test container and exactly the blind spot that needs a second look.
 *
 * <p>So this is that second look, and it is cheap: the channel is a constant in the code and a word
 * in a generated file, and a fact stated twice is checked rather than trusted (REQ-CON-015).
 *
 * <p>No container: this reads two files.
 */
@DisplayName("The Valkey channel grants")
class ValkeyChannelGrantTest {

  /** The generated ACL, from {@code deploy/services.yaml} by {@code deploy/generate.py}. */
  private static final Path ACL =
      Path.of("..", "deploy", "generated", "valkey-users.acl");

  @Test
  @DisplayName("name the live-change channel the event stream publishes to")
  void theLiveChannelIsGranted() throws IOException {
    String acl = Files.readString(ACL, StandardCharsets.UTF_8);

    // `&<channel>` is how Valkey spells a pub/sub grant. Literal rather than a
    // glob, because Valkey matches the two commands differently: a SUBSCRIBE
    // channel is glob-matched against the patterns, and a PSUBSCRIBE pattern has
    // to appear literally -- so `&homeinv.*` would look like it covered this and
    // would not (deploy/generate.py says the same thing at more length).
    assertThat(acl)
        .as(
            "the application publishes to `%s` and subscribes to it on every replica. An ACL that"
                + " does not name it answers NOPERM, the listener fails to start, and the container"
                + " exits -- which is how this test came to exist (REQ-API-011, ADR-0044)",
            LiveChanges.CHANNEL)
        .contains("&" + LiveChanges.CHANNEL);
  }

  @Test
  @DisplayName("still deny the default user, which is what makes the rest of it mean anything")
  void theDefaultUserIsOff() throws IOException {
    String acl = Files.readString(ACL, StandardCharsets.UTF_8);

    // Valkey ships `default` enabled and without a password. Leaving it on would
    // make every grant below it decoration: anything that reached port 6379 on
    // `internal` would read every session and every rate-limit counter
    // (REQ-SEC-104, ADR-0044).
    assertThat(acl).contains("user default off");
  }
}
