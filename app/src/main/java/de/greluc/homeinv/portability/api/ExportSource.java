/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.api;

/**
 * How one building block writes itself into an export (REQ-PORT-003, REQ-PORT-004).
 *
 * <h2>Each block exports itself</h2>
 *
 * <p>The same arrangement {@code TenantErasure} already has, and for the same reason: {@code
 * portability} must not read another block's tables (ADR-0002, REQ-NFR-019…024), and it has no
 * business knowing what an item is. So it owns the <b>archive</b> — the format, the ZIP, the
 * manifest, the ordering — and each block owns <b>what of it belongs in one</b>.
 *
 * <p>It also means a block added later exports itself by implementing this, with nothing in
 * {@code portability} to change. A block that does not implement it contributes nothing, which is
 * correct for {@code platform} and would be a silent gap for anything holding tenant data — so
 * {@code ExportCoverageTest} compares the implementations against the blocks that own a
 * tenant-scoped table.
 *
 * <h2>Read from PostgreSQL, never from a derived store</h2>
 *
 * <p>An implementation reads its own rows. Not the search index, not a cache: those are derived and
 * rebuildable, and an export assembled from one would be an archive that is subtly wrong exactly
 * when the index is lagging — which is the failure REQ-PORT-003's "importable into another
 * instance" cannot survive.
 */
public interface ExportSource {

  /**
   * Which block this is.
   *
   * <p>Used as the directory in the archive, so it appears in the manifest and in the file names a
   * person browsing the ZIP sees.
   *
   * @return the building block's name, lower case
   */
  String block();

  /**
   * Writes this block's share of one tenant.
   *
   * <p>Called with the tenant context already established, so a query needs no tenant predicate of
   * its own. It must be safe to call twice: a failed export is simply requested again.
   *
   * @param sink where the rows go
   */
  void exportTo(Sink sink);

  /**
   * Where an export source writes.
   *
   * <p>Deliberately narrow. A source names a dataset and hands over rows; it does not choose a file
   * name, a format, a compression level or an order between blocks, because those are properties of
   * the archive and belong to the one place that owns it.
   */
  interface Sink {

    /**
     * Opens a dataset and writes every row of it.
     *
     * <p>One call per dataset, and the rows arrive as a stream rather than a list: a tenant's items
     * are the largest thing in the archive and holding them all in memory to write them out would
     * put the ceiling in the wrong place.
     *
     * @param dataset what to call it — {@code items}, {@code locations}. Becomes
     *     {@code data/<block>/<dataset>.jsonl} in the archive
     * @param rows the rows, each serialised as one JSON line. A view type, never an entity: an
     *     entity does not leave its block (REQ-NFR-022), and an archive is as far outside it as
     *     anything gets
     */
    void write(String dataset, java.util.stream.Stream<?> rows);
  }
}
