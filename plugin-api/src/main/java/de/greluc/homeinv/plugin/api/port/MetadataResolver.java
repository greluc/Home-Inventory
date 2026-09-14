/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api.port;

import de.greluc.homeinv.plugin.api.CallContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a code into what is known about the thing it identifies (09 §9.2).
 *
 * <p>ISBN through Open Library or the DNB, EAN and GTIN through Open Food Facts or GS1, MPN,
 * Discogs, TMDB, IGDB. <b>Every implementation is a plugin</b>, without exception: each of them
 * calls a host outside the deployment, and the core has no outbound route at all (ADR-0026).
 *
 * <p>What comes back is a <i>proposal</i> and never a change. The core records it, a person accepts
 * or rejects it, and only then does an item change (the enrichment flow of stage 3). A resolver
 * that could write would be foreign data entering the inventory unseen.
 *
 * <p>Stage 3, written at stage 1 with the rest of the contract (REQ-PLG-001).
 */
public interface MetadataResolver {

  /**
   * Which code schemes this resolver answers for.
   *
   * <p>Uppercase and stable: {@code ISBN13}, {@code EAN13}, {@code GTIN14}, {@code MPN}. The core
   * asks only the resolvers that named the scheme in hand, so a resolver that lists a scheme it
   * cannot really answer buys itself calls it will decline.
   *
   * @return the schemes
   */
  Set<String> schemes();

  /**
   * Looks a code up.
   *
   * @param context who is asking. A resolver with a per-tenant API key finds it by this
   * @param scheme which scheme the code is in, one of {@link #schemes()}
   * @param code the code itself, normalised by the caller — no separators, no check-digit
   *     variations
   * @return what is known, or empty when the source simply has no entry. Empty is an answer and not
   *     a failure: most codes are in no catalogue
   * @throws de.greluc.homeinv.plugin.api.PluginException when the source could not be reached or
   *     refused the credentials, which is what a caller retries and a circuit breaker counts
   */
  Optional<Resolved> resolve(CallContext context, String scheme, String code);

  /**
   * What a source knows about a code.
   *
   * @param title the name of the thing, as the source gives it
   * @param attributes everything else, keyed by the attribute key the resolver claims it maps to.
   *     The core maps these onto the tenant's own type definition and drops what does not fit — a
   *     resolver cannot create fields
   * @param images the pictures, as bytes
   * @param source where this came from, shown beside the proposal so that a person can weigh it —
   *     {@code Open Library}, {@code Open Food Facts}
   * @param sourceUrl the page a person can read to check it, or empty. Displayed as a link with
   *     {@code rel="noopener noreferrer"} and <b>never fetched by the core</b> (REQ-SEC-034)
   * @param confidence how sure the resolver is, from 0 to 100. A resolver that cannot tell says
   *     100 rather than inventing a spread; the core sorts proposals by it
   */
  record Resolved(
      String title,
      Map<String, String> attributes,
      List<Image> images,
      String source,
      String sourceUrl,
      int confidence) {}

  /**
   * One picture belonging to a proposal.
   *
   * <p>The bytes travel, not a URL. A URL would mean the core fetching something a foreign source
   * named, which is exactly what REQ-SEC-034 forbids — and the plugin is the one that already has
   * the network capability and the egress allowlist for it.
   *
   * @param mediaType what it is, which the core checks against what it accepts rather than trusts
   * @param content the bytes
   * @param caption what it shows, when the source says. Empty otherwise
   */
  record Image(String mediaType, byte[] content, String caption) {}
}
