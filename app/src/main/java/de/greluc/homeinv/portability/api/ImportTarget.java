/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.api;

import java.util.List;
import java.util.Map;

/**
 * How one building block reads itself back out of an archive (REQ-PORT-003, REQ-PORT-004,
 * REQ-PORT-007).
 *
 * <p>The mirror of {@link ExportSource}, and for the same reason: {@code portability} owns the
 * archive and each block owns what of it belongs to it. A block that exports itself and does not
 * implement this is one whose data travels into an archive and no further, which
 * {@code ArchiveMoveIT} is there to notice.
 *
 * <h2>Order matters here and did not there</h2>
 *
 * <p>An export may write its blocks in any order, because a ZIP has no foreign keys. An import may
 * not: an item written against a type version the receiving instance does not have yet is a row
 * that cannot be inserted, and a place inside a place that has not arrived is the same problem one
 * level down. {@link #order()} is how a block says where it sits in that chain.
 *
 * <h2>Merge by id, and the archive wins</h2>
 *
 * <p>Rows are matched on their own {@code id}. One that is not there is inserted; one that is there
 * is <b>overwritten</b> from the archive. That is the decision an import has to make and it is made
 * once, here, rather than differently in each block — importing into a tenant with rows of its own
 * is a restore as much as it is a move, and a merge that kept the local row would produce a result
 * nobody asked for out of two states that were both complete.
 *
 * <h2>People do not travel</h2>
 *
 * <p>The archive carries accounts, memberships and the roles a tenant defined, because Art. 15 asks
 * who had access (REQ-PORT-006). <b>An import writes none of them.</b> An uploaded file that could
 * create accounts is an account-creation endpoint with no rate limit and no consent, and a
 * membership would need an account to point at. They are read for the report and left there.
 *
 * <p>Two consequences follow and both are stated rather than hidden. A tenant moving with five
 * members invites those five again on the other side. And the field-visibility rules a tenant wrote
 * do not arrive either — they are attached to role definitions, which do not — so the receiving
 * instance decides who reads a sensitive field by its own defaults until somebody sets them again.
 * The import report says so in as many words.
 */
public interface ImportTarget {

  /**
   * Which block this is.
   *
   * <p>The same name {@link ExportSource#block()} uses, because it is the directory the datasets
   * were written into.
   *
   * @return the building block's name, lower case
   */
  String block();

  /**
   * Where this block sits in the dependency chain.
   *
   * <p>Lower runs first. The numbers are spaced so one can be inserted between two without
   * renumbering the rest: {@code catalog} at 10 (types before anything written against them),
   * {@code locations} at 20, {@code inventory} at 30, {@code tagging} and {@code media} at 40 (both
   * point at items and places), {@code search} at 50 and {@code notification} at 60 (a reminder
   * rule points at a saved search).
   *
   * @return the position, lower first
   */
  int order();

  /**
   * Reads this block's share of the archive.
   *
   * <p>Called inside the receiving tenant's context and inside <b>one</b> transaction covering the
   * whole import, so a failure anywhere leaves nothing behind (REQ-PORT-007).
   *
   * @param archive what was uploaded
   * @param ids what the archive's ids mean here. Written by {@code catalog}, which matches its
   *     rows by their natural key because every tenant is provisioned with the same built-ins under
   *     different ids; read by everything that points at one
   * @return what happened, for the report
   */
  Outcome importFrom(Archive archive, Remapping ids);

  /**
   * One archive, opened.
   *
   * <p>Deliberately narrow, and deliberately not a {@code ZipFile}: a target names a dataset and
   * receives rows. Where they came from, in what order the entries sit and whether the file is even
   * a ZIP are properties of the archive, and they belong to the one place that owns it.
   */
  interface Archive {

    /**
     * Every row of one dataset, in the order it was written.
     *
     * <p>A list rather than a stream: an import is one transaction and the rows are needed twice —
     * once to write them and once to count what happened — so the laziness an export needs would
     * buy nothing here and cost the ability to report.
     *
     * @param block whose dataset
     * @param dataset its name, as {@link ExportSource.Sink#write} was given it
     * @return the rows, each a map of column name to value, or empty when the archive has no such
     *     dataset — which is what an archive from an older version looks like and is not an error
     */
    List<Map<String, Object>> rows(String block, String dataset);

    /**
     * Whether the archive holds a file.
     *
     * @param path where in the archive, as {@link ExportSource.Sink#writeFile} was given it
     * @return whether it is there
     */
    boolean hasFile(String path);

    /**
     * The bytes of one file.
     *
     * @param path where in the archive
     * @return the content, which the caller closes
     * @throws java.io.IOException when it cannot be read
     * @throws IllegalArgumentException when the archive has no such file
     */
    java.io.InputStream openFile(String path) throws java.io.IOException;
  }

  /**
   * What one block did.
   *
   * @param inserted rows that were not there
   * @param overwritten rows that were, and now hold what the archive said
   * @param skipped rows deliberately not written, each for a reason the report can give
   */
  record Outcome(int inserted, int overwritten, int skipped) {

    /** Nothing at all, for a block whose datasets are absent from the archive. */
    public static final Outcome NOTHING = new Outcome(0, 0, 0);

    /**
     * The two outcomes added together.
     *
     * @param other what else happened
     * @return the sum
     */
    public Outcome plus(Outcome other) {
      return new Outcome(
          inserted + other.inserted(),
          overwritten + other.overwritten(),
          skipped + other.skipped());
    }
  }
}
