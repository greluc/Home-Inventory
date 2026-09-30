/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api.port;

import de.greluc.homeinv.plugin.api.CallContext;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a described document into a rendered one — a PDF, or anything else somebody writes a
 * renderer for (REQ-LIFE-016, REQ-PLG-001).
 *
 * <h2>General on purpose</h2>
 *
 * <p>The first thing that needs one is the insurance report, and this port is deliberately not
 * about insurance reports. Anything in this system that has to become a document describes it as a
 * {@link Document} and asks whichever renderer the operator installed. A second report, a printed
 * inventory, a handover note for a lent item: none of them needs a new port, a new plugin or a PDF
 * library in the core.
 *
 * <p>It is available to <b>other plugins</b> as well, through the host channel: a plugin that
 * produces documents describes one and asks the core to render it, rather than carrying a PDF
 * library of its own and a font pack to go with it.
 *
 * <h2>A document is described, not programmed</h2>
 *
 * <p>{@link Block} is a closed set — a heading, a paragraph, a table, a list of facts, a picture, a
 * break. There is no template language here and there will not be one: ADR-0020 says the
 * configuration surface must not become a programming language, and a document format with
 * conditionals in it is that language arriving through the back door. A renderer lays out what it
 * is given and evaluates nothing.
 *
 * <p>The consequence is deliberate: <b>the core decides what a document says and the plugin decides
 * what it looks like.</b> A renderer may choose fonts, spacing, how a table breaks across pages and
 * what a heading weighs. It may not decide that a figure is worth showing, because it is not the
 * one that knows.
 *
 * <h2>Pictures travel with the document</h2>
 *
 * <p>An {@link Image} carries its bytes. The core hands out no URL for a plugin to fetch
 * (REQ-SEC-034) and a renderer has no route to the blob store, so a reference would be a reference
 * to nothing. It makes a document with photographs large, which is why {@link #render} is expected
 * to be called with a stream on the wire rather than one message.
 */
public interface DocumentRenderer {

  /**
   * What this renderer can produce.
   *
   * @param context who is asking
   * @return the media types, {@code application/pdf} for the one everybody wants. A renderer
   *     producing several offers several, and the caller names the one it wants
   */
  Set<String> outputMediaTypes(CallContext context);

  /**
   * Renders one document.
   *
   * @param context who it is for
   * @param document what it says
   * @param mediaType what to produce it as, one of {@link #outputMediaTypes}
   * @return the rendered bytes
   * @throws de.greluc.homeinv.plugin.api.PluginException when the media type is not one this
   *     renderer writes, or the document cannot be laid out — a table of forty columns on a
   *     postcard, an image this renderer cannot decode
   */
  Rendered render(CallContext context, Document document, String mediaType);

  /**
   * A document to be rendered.
   *
   * @param title what it is called, which a renderer puts in the document's metadata and usually at
   *     the top of the first page
   * @param metadata anything else worth recording in the file — {@code author}, {@code subject},
   *     {@code generatedAt}. A renderer writes what its format has a place for and ignores the rest
   * @param page the paper to lay it out on
   * @param blocks the content, in order. An empty list is a deliberately empty document rather than
   *     an error: a report of nothing is a true answer to a question about nothing
   */
  record Document(String title, Map<String, String> metadata, Page page, List<Block> blocks) {}

  /**
   * The paper.
   *
   * @param size a paper size by name — {@code A4}, {@code A5}, {@code Letter}. A renderer that does
   *     not know a size says so rather than guessing, because a document laid out on the wrong
   *     paper is one somebody prints twice
   * @param landscape whether it is turned on its side
   * @param marginMillimetres how much white to leave on every edge
   */
  record Page(String size, boolean landscape, int marginMillimetres) {}

  /**
   * One piece of a document.
   *
   * <p>Sealed, and that is the point rather than a detail: the set of things a document can contain
   * is fixed by this contract, so a renderer written today handles every document the core will
   * ever describe within this major version. A seventeenth kind of block is a breaking change and
   * {@code buf breaking} says so.
   */
  sealed interface Block
      permits Heading, Paragraph, Facts, Table, Image, PageBreak, Spacer {}

  /**
   * A heading.
   *
   * @param level 1 for the largest, down to 6. A renderer maps them to its own scale
   * @param text the heading
   */
  record Heading(int level, String text) implements Block {}

  /**
   * A run of prose.
   *
   * @param text the text. Plain, with no markup: a renderer that interpreted markup would be
   *     evaluating its input, which is the one thing this contract does not do
   */
  record Paragraph(String text) implements Block {}

  /**
   * A list of labelled facts — what a report says about one thing.
   *
   * @param caption what the list is about, or null
   * @param entries the facts, in the order they should read. Both halves are already formatted:
   *     money is a string because the core knows its currency and its scale and a renderer does not
   */
  record Facts(String caption, List<Fact> entries) implements Block {}

  /**
   * One labelled fact.
   *
   * @param label what it is
   * @param value what it says, already formatted
   */
  record Fact(String label, String value) {}

  /**
   * A table.
   *
   * @param caption what it is a table of, or null
   * @param columns the column headings
   * @param rows the rows, each as long as {@code columns}. A row of a different length is a
   *     document the renderer refuses, because silently padding one would hide a bug in whatever
   *     built it
   * @param numeric which columns hold numbers, by index, so a renderer can align them right. A
   *     formatting hint and nothing more: the values are already strings
   */
  record Table(String caption, List<String> columns, List<List<String>> rows, Set<Integer> numeric)
      implements Block {}

  /**
   * A picture.
   *
   * @param content the bytes, carried with the document because a renderer has no route to the blob
   *     store and the core hands out no URL (REQ-SEC-034)
   * @param mediaType what the bytes are, {@code image/jpeg} or {@code image/png}
   * @param caption what to print under it, or null
   * @param widthMillimetres how wide to draw it; the height follows the aspect ratio. Zero means
   *     "as wide as the text", which is what a photograph in a report usually wants
   */
  record Image(byte[] content, String mediaType, String caption, int widthMillimetres)
      implements Block {}

  /** Start a new page here. */
  record PageBreak() implements Block {}

  /**
   * Vertical white space.
   *
   * @param millimetres how much
   */
  record Spacer(int millimetres) implements Block {}

  /**
   * A rendered document.
   *
   * @param content the bytes
   * @param mediaType what they are
   * @param suggestedFilename what to call the file when somebody downloads it, without a path. The
   *     core may override it; a renderer that has a better idea says so here
   */
  record Rendered(byte[] content, String mediaType, String suggestedFilename) {}
}
