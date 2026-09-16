/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.infrastructure;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The list of passwords other people have already had taken (REQ-SEC-011, ADR-0067).
 *
 * <h2>Why a list in the image and not a lookup</h2>
 *
 * <p>The usual answer is a range query against Have I Been Pwned. The core has no outbound route at
 * all (ADR-0026), so that call cannot be made from here and will not be: the list ships with the
 * image, is read once at startup, and works in every profile including {@code minimal}. An operator
 * who wants a live service installs the plugin that ADR-0067 provides for it.
 *
 * <h2>Hashes rather than strings</h2>
 *
 * <p>Each entry is kept as the first 64 bits of its SHA-256, in one sorted {@code long[]}: about
 * 800 kB of heap instead of the ten megabytes a {@code HashSet<String>} of a hundred thousand
 * strings would take, with a binary search to look one up. A truncated hash can in principle
 * collide — at this size roughly once in four billion — and the consequence of a collision is that
 * one password somebody chose is refused. That is the harmless direction.
 *
 * <p>It also means the list in memory is not readable as passwords, which is worth having in a heap
 * dump even though every entry is public knowledge.
 */
@Slf4j
@Component
public class BreachedPasswordList {

  /** Where the vendored list sits in the image. */
  private static final String RESOURCE = "/security/breached-passwords.txt.gz";

  /** Entry hashes, sorted, for {@link Arrays#binarySearch(long[], long)}. */
  private final long[] hashes;

  /**
   * Reads the list once.
   *
   * <p>A missing or unreadable resource is fatal. "No scanner" is not a supported configuration and
   * neither is "no list": a policy that silently stopped checking would be the kind of security
   * control that reports success while doing nothing, and the file is part of the image rather than
   * something an operator supplies.
   */
  public BreachedPasswordList() {
    this.hashes = read();
    log.info("The breached-password list holds {} entries (REQ-SEC-011).", hashes.length);
  }

  /**
   * Whether this password is on the list.
   *
   * @param password the password as the person typed it
   * @return {@code true} when it is one of the known-breached ones
   */
  public boolean contains(String password) {
    return password != null && Arrays.binarySearch(hashes, truncatedSha256(password)) >= 0;
  }

  /**
   * How many entries the list holds, for the startup line and for a test that would otherwise be
   * asserting against a file it cannot see.
   *
   * @return the entry count
   */
  public int size() {
    return hashes.length;
  }

  private static long[] truncatedSha256Of(BufferedReader reader) throws IOException {
    long[] buffer = new long[128];
    int count = 0;
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.isEmpty()) {
        continue;
      }
      if (count == buffer.length) {
        buffer = Arrays.copyOf(buffer, buffer.length * 2);
      }
      buffer[count++] = truncatedSha256(line);
    }
    long[] result = Arrays.copyOf(buffer, count);
    Arrays.sort(result);
    return result;
  }

  private static long[] read() {
    try (InputStream resource = BreachedPasswordList.class.getResourceAsStream(RESOURCE)) {
      if (resource == null) {
        throw new IllegalStateException(
            "The breached-password list "
                + RESOURCE
                + " is not in the image. REQ-SEC-011 checks every new password against it, and an"
                + " absent list would mean the check silently stops happening.");
      }
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(new GZIPInputStream(resource), StandardCharsets.UTF_8))) {
        return truncatedSha256Of(reader);
      }
    } catch (IOException unreadable) {
      throw new UncheckedIOException(
          "The breached-password list " + RESOURCE + " could not be read", unreadable);
    }
  }

  /**
   * The first 64 bits of a password's SHA-256.
   *
   * @param password the password
   * @return the truncated digest as a long
   */
  private static long truncatedSha256(String password) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));
      long value = 0;
      for (int index = 0; index < Long.BYTES; index++) {
        value = (value << 8) | (digest[index] & 0xFFL);
      }
      return value;
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is not available in this JVM", impossible);
    }
  }
}
