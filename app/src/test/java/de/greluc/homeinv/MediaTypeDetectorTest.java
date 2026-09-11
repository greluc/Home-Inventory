/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.media.api.MediaTypeDetector;
import de.greluc.homeinv.media.api.UnsupportedMediaTypeException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the upload allowlist (REQ-MED-003, REQ-MED-004).
 *
 * <p>A unit test with no container, because the logic has no dependencies — and it is the piece of
 * the media path where a mistake is a security hole rather than a bug. Every case below is either a
 * format the requirement names or a way somebody would try to get past it.
 */
@DisplayName("Media type detection")
class MediaTypeDetectorTest {

  @Test
  @DisplayName("recognises the image formats the allowlist names")
  void acceptsAllowedImages() {
    assertThat(MediaTypeDetector.detect(jpeg()).mediaType()).isEqualTo("image/jpeg");
    assertThat(MediaTypeDetector.detect(png()).mediaType()).isEqualTo("image/png");
    assertThat(MediaTypeDetector.detect(webp()).mediaType()).isEqualTo("image/webp");
    assertThat(MediaTypeDetector.detect(isoBmff("avif")).mediaType()).isEqualTo("image/avif");
  }

  @Test
  @DisplayName("accepts HEIC, which is what phones produce")
  void acceptsHeic() {
    // REQ-MED-003: accepted and transcoded to AVIF on ingest, never stored as it
    // arrived. Detection is the half this test covers.
    assertThat(MediaTypeDetector.detect(isoBmff("heic")).mediaType()).isEqualTo("image/heic");
    assertThat(MediaTypeDetector.detect(isoBmff("mif1")).mediaType()).isEqualTo("image/heic");
  }

  @Test
  @DisplayName("tells AVIF and HEIC apart by brand, not by guessing")
  void distinguishesIsoBmffDialects() {
    // Both are the same container. Only the four bytes after 'ftyp' differ, and
    // getting this wrong would mean transcoding a file that needed no transcoding
    // or storing one that did.
    assertThat(MediaTypeDetector.detect(isoBmff("avif")).mediaType()).isEqualTo("image/avif");
    assertThat(MediaTypeDetector.detect(isoBmff("heix")).mediaType()).isEqualTo("image/heic");
    assertThatThrownBy(() -> MediaTypeDetector.detect(isoBmff("mp42")))
        .isInstanceOf(UnsupportedMediaTypeException.class);
  }

  @Test
  @DisplayName("rejects SVG, which is a script that looks like a picture")
  void rejectsSvg() {
    byte[] svg = pad("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>");
    assertThatThrownBy(() -> MediaTypeDetector.detect(svg))
        .isInstanceOf(UnsupportedMediaTypeException.class)
        .hasMessageContaining("image/svg+xml");

    // Also when it arrives with an XML declaration first, which is the ordinary
    // way an SVG file starts.
    byte[] declared = pad("<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\"/>");
    assertThatThrownBy(() -> MediaTypeDetector.detect(declared))
        .isInstanceOf(UnsupportedMediaTypeException.class);
  }

  @Test
  @DisplayName("ignores what the file claims to be")
  void trustsOnlyTheBytes() {
    // The attack this closes: an HTML page that a browser would render, arriving
    // as photo.jpg with Content-Type: image/jpeg. Neither the name nor the header
    // reaches the detector - only these bytes do.
    byte[] html = pad("<!DOCTYPE html><html><body><script>fetch('/steal')</script></body></html>");
    // It is not an image; it sniffs as text, which is served as a download and
    // never as a document from our origin.
    assertThat(MediaTypeDetector.detect(html).mediaType()).isEqualTo("text/plain");
    assertThat(MediaTypeDetector.detect(html).image()).isFalse();
  }

  @Test
  @DisplayName("refuses a binary it does not recognise")
  void rejectsUnknownBinary() {
    byte[] elf = new byte[MediaTypeDetector.PROBE_BYTES];
    elf[0] = 0x7F;
    elf[1] = 'E';
    elf[2] = 'L';
    elf[3] = 'F';
    assertThatThrownBy(() -> MediaTypeDetector.detect(elf))
        .isInstanceOf(UnsupportedMediaTypeException.class);
  }

  @Test
  @DisplayName("refuses a file too short to identify")
  void rejectsTruncated() {
    assertThatThrownBy(() -> MediaTypeDetector.detect(new byte[] {(byte) 0xFF, (byte) 0xD8}))
        .isInstanceOf(UnsupportedMediaTypeException.class);
    assertThatThrownBy(() -> MediaTypeDetector.detect(null))
        .isInstanceOf(UnsupportedMediaTypeException.class);
  }

  @Test
  @DisplayName("accepts PDF and plain text as documents, not as images")
  void acceptsDocuments() {
    assertThat(MediaTypeDetector.detect(pad("%PDF-1.7\n%âãÏÓ\n")).mediaType())
        .isEqualTo("application/pdf");
    assertThat(MediaTypeDetector.detect(pad("%PDF-1.7\n%x\n")).image()).isFalse();

    assertThat(MediaTypeDetector.detect(pad("name;quantity\nHammer;1\n")).mediaType())
        .isEqualTo("text/plain");
  }

  private static byte[] jpeg() {
    byte[] b = new byte[MediaTypeDetector.PROBE_BYTES];
    b[0] = (byte) 0xFF;
    b[1] = (byte) 0xD8;
    b[2] = (byte) 0xFF;
    b[3] = (byte) 0xE0;
    return b;
  }

  private static byte[] png() {
    byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    byte[] b = new byte[MediaTypeDetector.PROBE_BYTES];
    System.arraycopy(signature, 0, b, 0, signature.length);
    return b;
  }

  private static byte[] webp() {
    byte[] b = new byte[MediaTypeDetector.PROBE_BYTES];
    System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 4);
    System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, b, 8, 4);
    return b;
  }

  /**
   * An ISO base media file with the given brand.
   *
   * @param brand the four-character brand after {@code ftyp}
   * @return probe-length bytes
   */
  private static byte[] isoBmff(String brand) {
    byte[] b = new byte[MediaTypeDetector.PROBE_BYTES];
    b[3] = 0x20; // box size, not inspected
    System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, b, 4, 4);
    System.arraycopy(brand.getBytes(StandardCharsets.US_ASCII), 0, b, 8, 4);
    return b;
  }

  private static byte[] pad(String text) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    out.writeBytes(bytes);
    while (out.size() < MediaTypeDetector.PROBE_BYTES) {
      out.write(' ');
    }
    return out.toByteArray();
  }
}
