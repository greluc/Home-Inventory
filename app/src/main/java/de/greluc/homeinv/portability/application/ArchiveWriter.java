/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.application;

import de.greluc.homeinv.portability.api.ExportSource;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds one export archive (REQ-PORT-003).
 *
 * <h2>To a file, not to memory</h2>
 *
 * <p>The blob store is content-addressed, so the archive's SHA-256 has to be known <b>before</b> it
 * is stored — and an archive can be tens of megabytes. Holding it in a byte array to hash it would
 * put the ceiling on how much a tenant may own, which is the wrong place for a ceiling. So it is
 * written to a temporary file through a {@link DigestOutputStream}: the hash falls out of the write
 * and the bytes never all exist at once.
 *
 * <h2>JSON Lines, not one document</h2>
 *
 * <p>One object per line per dataset (04 §4.5 names the format). A single JSON array of ten
 * thousand items cannot be read without holding all of it, which makes the import side need as much
 * memory as the export side — and the receiving instance is the one least able to promise that.
 * Lines are also what lets an import report "row 4,812" rather than "the file is wrong".
 */
public class ArchiveWriter implements ExportSource.Sink, AutoCloseable {

  /** What the archive says about itself, so a reader knows what they have. */
  private final Map<String, Object> manifest = new LinkedHashMap<>();

  /** One entry per dataset: what it was called and how many rows it holds. */
  private final List<Map<String, Object>> datasets = new ArrayList<>();

  /** One entry per file: where it is and how large. The manifest's other half. */
  private final List<Map<String, Object>> files = new ArrayList<>();

  /** What the archive deliberately does not hold, so a reader is not left guessing. */
  private final java.util.SortedMap<String, Map<String, Object>> withheld = new java.util.TreeMap<>();

  private final ObjectMapper json;
  private final Path file;
  private final MessageDigest digest;
  private final ZipOutputStream zip;

  /** The block currently writing, so a dataset lands in the right directory. */
  private String block = "unknown";

  /**
   * Opens an archive.
   *
   * @param json the mapper, which is the application's own so the archive reads like the API
   * @param file where to build it
   * @throws IOException when the file cannot be opened
   */
  public ArchiveWriter(ObjectMapper json, Path file) throws IOException {
    this.json = json;
    this.file = file;
    try {
      this.digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is not available in this JVM", impossible);
    }
    OutputStream out = Files.newOutputStream(file);
    this.zip = new ZipOutputStream(new BufferedOutputStream(new DigestOutputStream(out, digest)));
  }

  /**
   * Which block is writing now.
   *
   * @param block the building block's name
   */
  public void beginBlock(String block) {
    this.block = block;
  }

  @Override
  public void write(String dataset, Stream<?> rows) {
    String name = "data/" + block + "/" + dataset + ".jsonl";
    long written = 0;
    try {
      zip.putNextEntry(new ZipEntry(name));
      // One row per line, written as it arrives: the stream is the point, and
      // collecting it here would undo the reason the port takes one.
      try (Stream<?> open = rows) {
        java.util.Iterator<?> each = open.iterator();
        while (each.hasNext()) {
          zip.write(json.writeValueAsBytes(each.next()));
          zip.write('\n');
          written++;
        }
      }
      zip.closeEntry();
    } catch (IOException failed) {
      throw new UncheckedIOException("The export archive could not be written: " + name, failed);
    }
    datasets.add(Map.of("block", block, "dataset", dataset, "rows", written, "file", name));
  }

  @Override
  public void writeFile(String path, java.io.InputStream bytes) {
    try {
      zip.putNextEntry(new ZipEntry(path));
      // Copied through rather than read into memory: a photograph is megabytes
      // and there may be thousands of them, so the archive is the only place the
      // whole of it ever exists.
      long copied = bytes.transferTo(zip);
      zip.closeEntry();
      files.add(Map.of("block", block, "file", path, "bytes", copied));
    } catch (IOException failed) {
      throw new UncheckedIOException("The export archive could not be written: " + path, failed);
    }
  }

  @Override
  public void withheld(String what, String why) {
    // Keyed by block and field, so a source that reports the same omission once
    // per row -- which is the easy mistake -- still produces one line.
    withheld.put(block + "/" + what, Map.of("block", block, "what", what, "why", why));
  }

  /**
   * Records something the archive should say about itself.
   *
   * @param key what it is
   * @param value the value
   */
  public void manifest(String key, Object value) {
    manifest.put(key, value);
  }

  /**
   * Writes the manifest and closes the archive.
   *
   * <p>The manifest goes <b>last</b>, because it counts what was written and cannot be complete
   * before that. A reader looks it up by name rather than by position, so where it sits in the file
   * does not matter to them — and the alternative is writing counts nobody has yet.
   *
   * @return what was built
   * @throws IOException when the archive cannot be finished
   */
  public Archive finish() throws IOException {
    manifest.put("datasets", List.copyOf(datasets));
    manifest.put("files", List.copyOf(files));
    manifest.put("withheld", List.copyOf(withheld.values()));
    zip.putNextEntry(new ZipEntry("manifest.json"));
    zip.write(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
    zip.closeEntry();
    zip.close();

    StringBuilder hex = new StringBuilder(64);
    for (byte b : digest.digest()) {
      hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    }
    return new Archive(file, hex.toString(), Files.size(file));
  }

  @Override
  public void close() throws IOException {
    // Idempotent on a stream already closed by `finish`, so a try-with-resources
    // around a successful build is not an error.
    try {
      zip.close();
    } catch (IOException alreadyClosed) {
      // The archive was finished; nothing to do and nothing to report.
      return;
    }
  }

  /**
   * A finished archive.
   *
   * @param file where it is
   * @param sha256 its content address, which is what the blob store knows it by
   * @param byteSize how large it is
   */
  public record Archive(Path file, String sha256, long byteSize) {}
}
