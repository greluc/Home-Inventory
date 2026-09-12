/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.api;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Decides what a file actually is, from its first bytes (REQ-MED-004).
 *
 * <p>The filename and the declared {@code Content-Type} are discarded, not consulted as a hint.
 * Both are chosen by the uploader, and the whole class of attack this closes is a file that claims
 * to be one thing and is another — an HTML page named {@code photo.jpg} that a browser would render
 * as a page, a script with an image extension.
 *
 * <p>The allowlist is closed (REQ-MED-003, REQ-SEC-039), and the type is read from the magic
 * bytes while the file name and the declared type are discarded (REQ-SEC-038, REQ-MED-004).
 * <b>SVG is rejected</b> and that is not an oversight: SVG
 * is XML that can carry script, so an SVG served from our own origin is a cross-site scripting
 * vector with a picture frame around it. <b>HEIC is accepted and transcoded</b>, never stored as it
 * arrived, because it is what phones produce and almost nothing else reads.
 *
 * <p>How many bytes are needed: {@value #PROBE_BYTES}. Enough for every signature below, including
 * the ISO base media box at offset 4 that distinguishes HEIC and AVIF from each other.
 */
public final class MediaTypeDetector {

  /** How many leading bytes the detector needs. */
  public static final int PROBE_BYTES = 32;

  private MediaTypeDetector() {}

  /**
   * Detects the media type of a file from its leading bytes.
   *
   * @param head at least {@link #PROBE_BYTES} bytes from the start of the file, or fewer if the file
   *     is shorter
   * @return the detected type
   * @throws UnsupportedMediaTypeException when the bytes match nothing on the allowlist, or match
   *     something deliberately excluded
   */
  public static Detected detect(byte[] head) {
    if (head == null || head.length < 12) {
      throw new UnsupportedMediaTypeException("unknown");
    }

    if (startsWith(head, 0xFF, 0xD8, 0xFF)) {
      return new Detected("image/jpeg", true);
    }
    if (startsWith(head, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
      return new Detected("image/png", true);
    }
    if (startsWith(head, 'R', 'I', 'F', 'F') && matchesAt(head, 8, 'W', 'E', 'B', 'P')) {
      return new Detected("image/webp", true);
    }
    if (startsWith(head, '%', 'P', 'D', 'F', '-')) {
      return new Detected("application/pdf", false);
    }

    // ISO base media file format: bytes 4..8 are 'ftyp', and the brand that
    // follows says which dialect. AVIF and HEIC share the container, so the
    // brand is the only thing that tells them apart.
    if (matchesAt(head, 4, 'f', 't', 'y', 'p')) {
      String brand = asciiLowerCase(head, 8, 4);
      return switch (brand) {
        case "avif", "avis" -> new Detected("image/avif", true);
        // Accepted and transcoded to AVIF on ingest; never stored as it arrived
        // (REQ-MED-003), because almost nothing outside Apple's ecosystem reads it.
        case "heic", "heix", "hevc", "hevx", "mif1", "msf1" -> new Detected("image/heic", true);
        default -> throw new UnsupportedMediaTypeException("application/octet-stream (ftyp:" + brand + ")");
      };
    }

    // SVG is XML and is rejected before anything else can accept it as text.
    // Checked explicitly rather than by omission, so the refusal is deliberate
    // rather than a consequence of the text sniffing below.
    // Compared as ASCII BYTES rather than as a lowercased string. Case folding is
    // a transformation on attacker-supplied input immediately before a security
    // decision, and the whole class of surprise there — one character folding
    // onto another — is avoided by never folding: the only thing that matches
    // "<svg" is those four bytes in either case, and nothing else.
    if (startsWithAsciiIgnoringCase(head, "<?xml") || startsWithAsciiIgnoringCase(head, "<svg")) {
      throw new UnsupportedMediaTypeException("image/svg+xml");
    }

    if (looksLikeText(head)) {
      // TXT and CSV are the same bytes; the distinction is a matter of
      // punctuation and not worth guessing at. Both are served as plain text
      // with a download disposition, so the difference changes nothing.
      return new Detected("text/plain", false);
    }

    throw new UnsupportedMediaTypeException(
        "unknown (" + HexFormat.of().formatHex(Arrays.copyOf(head, Math.min(head.length, 8))) + ")");
  }

  /**
   * Whether the bytes look like text rather than a binary format.
   *
   * <p>Deliberately conservative: a NUL byte or a control character other than tab, newline and
   * carriage return means binary. That rejects some legitimate text encodings, which is the right
   * direction to be wrong in for an allowlist.
   *
   * @param head the leading bytes
   * @return {@code true} when every byte is printable text
   */
  private static boolean looksLikeText(byte[] head) {
    for (byte b : head) {
      int value = b & 0xFF;
      if (value == 0) {
        return false;
      }
      if (value < 0x20 && value != '\t' && value != '\n' && value != '\r') {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether the head begins with an ASCII prefix, ignoring ASCII case only.
   *
   * <p>Leading ASCII whitespace is skipped, because an XML document may begin with it.
   *
   * @param head the first bytes of the file
   * @param prefix the ASCII prefix to look for, written in lowercase
   * @return true when the head starts with it
   */
  private static boolean startsWithAsciiIgnoringCase(byte[] head, String prefix) {
    int start = 0;
    while (start < head.length
        && (head[start] == ' ' || head[start] == '\t' || head[start] == '\n'
            || head[start] == '\r')) {
      start++;
    }
    if (head.length - start < prefix.length()) {
      return false;
    }
    for (int index = 0; index < prefix.length(); index++) {
      int actual = head[start + index] & 0xFF;
      if (actual >= 'A' && actual <= 'Z') {
        actual += 'a' - 'A';
      }
      if (actual != prefix.charAt(index)) {
        return false;
      }
    }
    return true;
  }

  /**
   * A fixed-length run of bytes as lowercase ASCII.
   *
   * <p>Any byte outside printable ASCII becomes {@code ?}, so a brand built from arbitrary bytes
   * cannot carry anything into the message the refusal is reported with.
   *
   * @param head the first bytes of the file
   * @param offset where to start
   * @param length how many bytes
   * @return the lowercased run
   */
  private static String asciiLowerCase(byte[] head, int offset, int length) {
    StringBuilder out = new StringBuilder(length);
    for (int index = offset; index < offset + length && index < head.length; index++) {
      int value = head[index] & 0xFF;
      if (value >= 'A' && value <= 'Z') {
        value += 'a' - 'A';
      }
      out.append(value >= 0x20 && value < 0x7F ? (char) value : '?');
    }
    return out.toString();
  }

  private static boolean startsWith(byte[] data, int... signature) {
    return matchesAt(data, 0, signature);
  }

  private static boolean matchesAt(byte[] data, int offset, int... signature) {
    if (data.length < offset + signature.length) {
      return false;
    }
    for (int i = 0; i < signature.length; i++) {
      if ((data[offset + i] & 0xFF) != (signature[i] & 0xFF)) {
        return false;
      }
    }
    return true;
  }

  /**
   * What a file turned out to be.
   *
   * @param mediaType the detected type
   * @param image whether it is an image, and therefore gets derivatives and EXIF stripping
   */
  public record Detected(String mediaType, boolean image) {}
}
