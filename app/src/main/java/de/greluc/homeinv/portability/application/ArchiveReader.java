/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.application;

import de.greluc.homeinv.portability.api.ImportTarget;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import tools.jackson.databind.ObjectMapper;

/**
 * One uploaded archive, read into memory once (REQ-PORT-007).
 *
 * <h2>Why in memory, when the writer went to such lengths not to be</h2>
 *
 * <p>An export streams because it cannot know how large a tenant is and must not put a ceiling on
 * one. An import is the other way round: the file has already arrived, its size is known before a
 * byte is read, and {@code REQ-PORT-007} asks for all of it or none of it — which means the whole
 * import is one transaction and every row has to be available inside it. A ZIP is also not
 * seekable through {@link ZipInputStream}, so serving a dataset on demand would mean re-reading the
 * file from the start per block.
 *
 * <p>The ceiling is therefore explicit rather than accidental: {@link #MAX_UNPACKED} bytes, refused
 * with a message rather than met with an {@code OutOfMemoryError}. It is also what stops a zip bomb
 * — a few hundred kilobytes that unpack to gigabytes — from being a way to take the instance down
 * (REQ-SEC-034's shape, one archive at a time).
 *
 * <h2>Blobs are kept as bytes and not as rows</h2>
 *
 * <p>Everything under {@code media/blobs/} is held aside for {@code media} to store, and everything
 * under {@code data/} is parsed as JSON Lines. An entry that is neither is ignored: an archive from
 * a later version may carry things this one does not understand, and refusing the whole import over
 * one unknown file would make every future addition a breaking change.
 */
public final class ArchiveReader implements ImportTarget.Archive {

  /**
   * The largest an archive may unpack to.
   *
   * <p>256 MiB. Generous for a household inventory whose photographs are the bulk of it, and small
   * enough that the worker cannot be pushed over by one upload. A tenant larger than this is a real
   * case and it is a conversation, not a silent failure: the import says what the limit is.
   */
  public static final long MAX_UNPACKED = 256L * 1024 * 1024;

  /** Every dataset, by {@code <block>/<dataset>}. */
  private final Map<String, List<Map<String, Object>>> datasets = new HashMap<>();

  /** Every file that is not a dataset, by its path in the archive. */
  private final Map<String, byte[]> files = new HashMap<>();

  /** What the archive says about itself. */
  private final Map<String, Object> manifest;

  /**
   * Unpacks an archive.
   *
   * @param json the mapper, which is the application's own so the archive reads as it was written
   * @param archive the uploaded bytes, which the caller closes
   * @throws IOException when it cannot be read
   * @throws IllegalArgumentException when it is not a ZIP, or unpacks to more than {@link
   *     #MAX_UNPACKED}
   */
  public ArchiveReader(ObjectMapper json, InputStream archive) throws IOException {
    long unpacked = 0;
    Map<String, Object> read = Map.of();
    try (ZipInputStream zip = new ZipInputStream(archive)) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (entry.isDirectory()) {
          continue;
        }
        String name = entry.getName();
        // Read with a running total rather than trusting the entry's declared
        // size: the declared size is a number in the file, written by whoever
        // made it, and a zip bomb is exactly the case where it lies.
        byte[] content = readAtMost(zip, MAX_UNPACKED - unpacked, name);
        unpacked += content.length;

        if ("manifest.json".equals(name)) {
          read = json.readValue(content, Map.class);
        } else if (name.startsWith("data/") && name.endsWith(".jsonl")) {
          datasets.put(
              name.substring("data/".length(), name.length() - ".jsonl".length()),
              parseLines(json, content));
        } else {
          files.put(name, content);
        }
      }
    }
    if (read.isEmpty()) {
      throw new IllegalArgumentException(
          "The file has no manifest.json and is not an export of this application.");
    }
    this.manifest = read;
  }

  /**
   * What the archive says about itself.
   *
   * @return the manifest, as it was written
   */
  public Map<String, Object> manifest() {
    return manifest;
  }

  @Override
  public List<Map<String, Object>> rows(String block, String dataset) {
    return datasets.getOrDefault(block + "/" + dataset, List.of());
  }

  @Override
  public boolean hasFile(String path) {
    return files.containsKey(path);
  }

  @Override
  public InputStream openFile(String path) {
    byte[] content = files.get(path);
    if (content == null) {
      throw new IllegalArgumentException("The archive holds no file at " + path);
    }
    return new ByteArrayInputStream(content);
  }

  /**
   * One entry, refusing to read past what is left of the budget.
   *
   * @param zip the stream, positioned on the entry
   * @param budget how many bytes may still be read
   * @param name the entry, for the message
   * @return its content
   * @throws IOException when the stream fails
   * @throws IllegalArgumentException when the entry would exceed the budget
   */
  private static byte[] readAtMost(ZipInputStream zip, long budget, String name)
      throws IOException {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    long left = budget;
    for (int read = zip.read(buffer); read >= 0; read = zip.read(buffer)) {
      left -= read;
      if (left < 0) {
        throw new IllegalArgumentException(
            "The archive unpacks to more than "
                + (MAX_UNPACKED / 1024 / 1024)
                + " MiB, which is more than one import may hold in memory. It stopped at "
                + name
                + ".");
      }
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }

  /**
   * One dataset's lines as rows.
   *
   * @param json the mapper
   * @param content the file
   * @return one map per line, blank lines ignored
   */
  private static List<Map<String, Object>> parseLines(ObjectMapper json, byte[] content) {
    List<Map<String, Object>> rows = new ArrayList<>();
    String text = new String(content, StandardCharsets.UTF_8);
    int line = 0;
    for (String each : text.split("\n")) {
      line++;
      if (each.isBlank()) {
        continue;
      }
      try {
        rows.add(json.readValue(each, Map.class));
      } catch (RuntimeException malformed) {
        // Named by line, which is the whole reason the format is JSON Lines
        // rather than one document: "row 4,812" is actionable and "the file is
        // wrong" is not.
        throw new IllegalArgumentException(
            "The archive could not be read at line " + line + ": " + malformed.getMessage());
      }
    }
    return rows;
  }

  /**
   * Turns a checked failure into the unchecked one the runner reports.
   *
   * @param failed what went wrong
   * @return never returns
   */
  static RuntimeException unreadable(IOException failed) {
    return new UncheckedIOException("The archive could not be read", failed);
  }
}
