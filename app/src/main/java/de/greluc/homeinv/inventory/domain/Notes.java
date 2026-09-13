/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.domain;

import java.util.regex.Pattern;

/**
 * The limited Markdown an item's notes may hold (REQ-CORE-014).
 *
 * <h2>What "limited" means here</h2>
 *
 * <p>The acceptance is one sentence — "HTML in notes is stripped server-side" — and that is what
 * this does. Markdown itself is left alone: it is text until a client renders it, and which subset a
 * client renders is a client's decision. What is removed is the part of Markdown that is not
 * Markdown at all, the raw HTML every implementation passes through by default.
 *
 * <h2>Why the server does it at all</h2>
 *
 * <p>Because a client that renders the notes as HTML is one bug away from executing somebody else's
 * script, and a tenant's members write these notes for each other. Stripping on the way IN means
 * every reader is protected, including a client written next year by somebody who did not read this
 * paragraph — and it means the stored value is what will be shown, rather than something that has to
 * be cleaned again on every read.
 *
 * <p>Tags are removed and their text kept: a note reading {@code <b>fragile</b>} becomes
 * {@code fragile} rather than disappearing. A person wrote the word, whatever they wrapped it in.
 */
public final class Notes {

  /**
   * Anything that looks like an HTML tag, opening, closing or self-closing.
   *
   * <p>Deliberately blunt. A parser would tell {@code <b>} from {@code 3 < 4 > 2}, and a parser is
   * also what has the bugs; in a field whose whole purpose is prose, removing every angle-bracketed
   * run costs a reader nothing and leaves nothing behind to interpret.
   */
  private static final Pattern TAG = Pattern.compile("<[^>]*>");

  /** A comment, which a tag pattern alone would leave half of. */
  private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");

  /**
   * A script or a style element, WITH its contents.
   *
   * <p>The two elements whose text is not text. Stripping the tags alone would leave
   * {@code alert('x')} sitting in the note as a sentence nobody wrote — inert, because there is no
   * longer a tag around it, and still nothing a person meant to keep. Everything else loses its tags
   * and keeps its words, because a person wrote those words whatever they wrapped them in.
   */
  private static final Pattern SCRIPT_OR_STYLE =
      Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>");

  private Notes() {}

  /**
   * Removes the HTML from a note, keeping the text.
   *
   * @param written what the person typed, possibly {@code null}
   * @return the note as it is stored, or {@code null} when nothing was written
   */
  public static String sanitise(String written) {
    if (written == null || written.isBlank()) {
      return null;
    }
    String text = SCRIPT_OR_STYLE.matcher(written).replaceAll("");
    text = COMMENT.matcher(text).replaceAll("");
    return TAG.matcher(text).replaceAll("").trim();
  }
}
