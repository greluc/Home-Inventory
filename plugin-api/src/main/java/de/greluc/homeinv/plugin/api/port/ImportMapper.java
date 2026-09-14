/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api.port;

import de.greluc.homeinv.plugin.api.CallContext;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads somebody else's export and says what it contains (09 §9.2).
 *
 * <p>The CSV profile is in the core image. Homebox, InvenTree and Snipe-IT are plugins, because
 * each is a foreign format somebody else keeps changing.
 *
 * <p><b>A mapper proposes and never writes.</b> It turns a file into rows of key and value; the
 * core validates them against the tenant's own type definitions, refuses what does not fit and
 * writes what does. A mapper that could create fields would be a foreign file changing the type
 * system (ADR-0020).
 *
 * <p>Stage 1 (REQ-IE-002).
 */
public interface ImportMapper {

  /**
   * What this mapper reads.
   *
   * @param context who is asking
   * @return its description, which the core shows in the list of importable formats
   */
  Descriptor describe(CallContext context);

  /**
   * Reads the beginning of a file and shows what it would make of it.
   *
   * <p>Shown to a person before the import runs, so that the wrong file is noticed while nothing
   * has been written yet.
   *
   * @param context who it is for
   * @param source the file. Only the beginning is read, and the implementation must not require the
   *     whole of it
   * @param maxRows how many rows at most to return
   * @return the columns it found and the first rows, mapped
   * @throws de.greluc.homeinv.plugin.api.PluginException when the file is not in this format —
   *     which is how the core picks the right mapper when the person did not say
   */
  Preview preview(CallContext context, InputStream source, int maxRows);

  /**
   * Reads the whole file, handing over each row as it is read.
   *
   * <p>Streamed in both directions: an export of thirty thousand items must not be held in memory
   * by either side, and the core writes rows as they arrive rather than at the end. A mapper that
   * needs the whole file before its first row says so by failing the preview.
   *
   * @param context who it is for
   * @param source the file
   * @param options what the person chose in the preview — which columns map to which fields, which
   *     to ignore. Keys are the mapper's own, and it ignores what it does not know
   * @param rows what receives each mapped row
   * @throws de.greluc.homeinv.plugin.api.PluginException when the file cannot be read. Rows already
   *     handed over stay handed over: the core has written them and an import is resumable, not
   *     transactional over thirty thousand rows
   */
  void read(CallContext context, InputStream source, Map<String, String> options, RowHandler rows);

  /** What the core does with each row as it arrives. */
  interface RowHandler {

    /**
     * One row.
     *
     * @param row the mapped values
     */
    void row(Row row);
  }

  /**
   * What a mapper reads.
   *
   * @param formatKey the stable key, for example {@code homebox}
   * @param name what a person sees
   * @param mediaTypes what the file may be, for example {@code text/csv} or {@code application/zip}
   * @param optionKeys the options {@link #read} understands, so that the core can offer them
   *     without knowing the format
   */
  record Descriptor(
      String formatKey, String name, Set<String> mediaTypes, Set<String> optionKeys) {}

  /**
   * What a mapper would make of a file.
   *
   * @param columns the source's own column names, in the order they appear, so that a person can
   *     map them by eye
   * @param rows the first rows, already mapped
   * @param totalRows how many rows the file has, or {@code -1} when that cannot be known without
   *     reading all of it. Never a guess presented as a count
   */
  record Preview(List<String> columns, List<Row> rows, long totalRows) {}

  /**
   * One row of a foreign export.
   *
   * @param values the fields, keyed by the core's own field key where the mapper knows it and by
   *     the source's column name where it does not. The core resolves the rest against the tenant's
   *     type definition and reports what it could not place, rather than dropping it silently
   * @param sourceReference what the row was called in the source — a line number, an id. Recorded
   *     with the imported item so that a person can find the original again
   */
  record Row(Map<String, String> values, String sourceReference) {}
}
