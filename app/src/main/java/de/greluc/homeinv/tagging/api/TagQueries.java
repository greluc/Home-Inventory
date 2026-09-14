/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tagging.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What another building block may ask about tag assignments (REQ-SRCH-002).
 *
 * <p>Separate from {@link TagService}, which is where a tag is created, assigned and merged. This
 * one answers questions and changes nothing, and it exists because {@code search} has to narrow a
 * list by tag without reading {@code tagging}'s tables: the assignment lives in
 * {@code tagging.tag_assignment} and nobody outside this block may join it (ADR-0002, REQ-NFR-019).
 *
 * <p>Everything here is answered inside the caller's tenant, through row-level security like every
 * other read. A tag of another tenant is not "no such tag" here — it simply is not there.
 */
public interface TagQueries {

  /**
   * The items carrying any of these tags.
   *
   * <p>An "or" across the names, which is what {@code filter=tag:in:broken,repair} means: a
   * multi-select facet widens within its own dimension and narrows across dimensions. The caller
   * intersects when it holds two separate tag filters.
   *
   * <p>Names and not ids, because the name is the identity a person uses for a tag — it is unique
   * per tenant and case-insensitively so, which is why {@code tag_name_unique} is on
   * {@code lower(name)}. A name nothing is called yields nothing rather than a refusal: it matches
   * no item, which is a true answer.
   *
   * <p>A merged tag answers under its old name as well. {@link TagService#merge} leaves a tombstone
   * pointing at what the tag became (REQ-CORE-063), and a list that stopped finding the items
   * somebody had tagged "broken" because the tag was renamed into "defective" would be wrong in the
   * way that is hardest to notice.
   *
   * @param names the tag names, compared without regard to case; possibly none
   * @return the ids of the items carrying at least one, without duplicates and in no particular
   *     order; empty when no name matches anything
   */
  List<UUID> itemsTagged(Collection<String> names);

  /**
   * How many of these items carry each tag (REQ-SRCH-002).
   *
   * <p>The tag facet. The ids are the whole matching list rather than one page of it, because a
   * facet counts the list and not the screen — and they are passed in rather than queried for,
   * because which items match is {@code inventory}'s question and this block must not ask it
   * (ADR-0002).
   *
   * <p>Keyed by the tag's name, because that is what a {@code tag} filter takes back and a facet's
   * bucket is a token a client puts straight into one. A tag that has been merged away contributes
   * nothing of its own: its assignments now belong to the tag it became, which is where the count
   * belongs too.
   *
   * @param itemIds the items to count over, possibly none
   * @return the count per tag name; empty when no item carries a tag
   */
  Map<String, Long> countByTag(Collection<UUID> itemIds);
}
