/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.infrastructure;

import com.google.protobuf.ByteString;
import de.greluc.homeinv.plugin.api.CallContext;
import de.greluc.homeinv.plugin.api.PluginException;
import de.greluc.homeinv.plugin.api.port.DocumentRenderer;
import de.greluc.homeinv.plugin.v1.DocumentHeader;
import de.greluc.homeinv.plugin.v1.DocumentRendererFormatsRequest;
import de.greluc.homeinv.plugin.v1.DocumentRendererGrpc;
import de.greluc.homeinv.plugin.v1.DocumentRendererRenderRequest;
import de.greluc.homeinv.plugin.v1.DocumentRendererRenderResponse;
import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Speaks {@code DocumentRenderer} to a plugin (REQ-LIFE-016, ADR-0070).
 *
 * <p>The runtime's first <b>streaming</b> adapter, and it streams for a reason rather than for
 * style: an insurance report of a household carries a photograph per item, and a document that
 * arrived as one message would put a ceiling on how much a tenant may own — in the same place, and
 * for the same bad reason, as an export held in memory would.
 *
 * <p>Every message after the header carries exactly one block, so the renderer can begin laying out
 * the first page while the last picture is still on the wire.
 */
@Component
public class DocumentRendererAdapter implements PortAdapter<DocumentRenderer> {

  @Override
  public Class<DocumentRenderer> port() {
    return DocumentRenderer.class;
  }

  @Override
  public DocumentRenderer adapt(Channel channel, int deadlineMillis) {
    return new Grpc(channel, deadlineMillis);
  }

  /**
   * One plugin's document renderer, over gRPC.
   *
   * @param channel the plugin's channel
   * @param deadlineMillis how long a call may take
   */
  private record Grpc(Channel channel, int deadlineMillis) implements DocumentRenderer {

    @Override
    public Set<String> outputMediaTypes(CallContext context) {
      try {
        return Set.copyOf(
            DocumentRendererGrpc.newBlockingStub(channel)
                .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
                .outputFormats(
                    DocumentRendererFormatsRequest.newBuilder()
                        .setContext(PluginWire.contextOf(context))
                        .build())
                .getMediaTypesList());
      } catch (StatusRuntimeException failure) {
        throw PluginWire.failureOf(failure, channel.authority());
      }
    }

    @Override
    public Rendered render(CallContext context, Document document, String mediaType) {
      ByteArrayOutputStream content = new ByteArrayOutputStream();
      AtomicReference<String> producedType = new AtomicReference<>(mediaType);
      AtomicReference<String> filename = new AtomicReference<>();
      AtomicReference<Throwable> failed = new AtomicReference<>();
      CountDownLatch finished = new CountDownLatch(1);

      var requests =
          DocumentRendererGrpc.newStub(channel)
              .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
              .render(
                  new io.grpc.stub.StreamObserver<DocumentRendererRenderResponse>() {
                    @Override
                    public void onNext(DocumentRendererRenderResponse part) {
                      if (part.hasHeader()) {
                        producedType.set(part.getHeader().getMediaType());
                        filename.set(part.getHeader().getSuggestedFilename());
                      } else {
                        content.writeBytes(part.getContent().toByteArray());
                      }
                    }

                    @Override
                    public void onError(Throwable error) {
                      failed.set(error);
                      finished.countDown();
                    }

                    @Override
                    public void onCompleted() {
                      finished.countDown();
                    }
                  });

      try {
        requests.onNext(
            DocumentRendererRenderRequest.newBuilder()
                .setHeader(headerOf(context, document, mediaType))
                .build());
        for (Block block : document.blocks()) {
          requests.onNext(
              DocumentRendererRenderRequest.newBuilder().setBlock(DocumentWire.blockOf(block))
                  .build());
        }
        requests.onCompleted();

        // The deadline is the stub's; this waits a little longer so that a
        // renderer which answers just inside it is not cut off by the wait
        // rather than by the policy.
        if (!finished.await(deadlineMillis + 1_000L, TimeUnit.MILLISECONDS)) {
          throw new PluginException(
              PluginException.Kind.DEADLINE_EXCEEDED,
              "The renderer did not finish the document within " + deadlineMillis + " ms");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new PluginException(
            PluginException.Kind.DEADLINE_EXCEEDED, "The render was interrupted");
      }

      if (failed.get() instanceof StatusRuntimeException status) {
        throw PluginWire.failureOf(status, channel.authority());
      }
      if (failed.get() != null) {
        throw new PluginException(PluginException.Kind.UNAVAILABLE, failed.get().getMessage());
      }
      return new Rendered(content.toByteArray(), producedType.get(), filename.get());
    }

    /**
     * The header that opens a render.
     *
     * @param context who it is for
     * @param document what is being rendered
     * @param mediaType what to produce
     * @return the message
     */
    private DocumentHeader headerOf(CallContext context, Document document, String mediaType) {
      DocumentHeader.Builder header =
          DocumentHeader.newBuilder()
              .setContext(PluginWire.contextOf(context))
              .setTitle(document.title() == null ? "" : document.title())
              .setMediaType(mediaType);
      if (document.metadata() != null) {
        header.putAllMetadata(document.metadata());
      }
      Page page = document.page();
      if (page != null) {
        header.setPage(
            de.greluc.homeinv.plugin.v1.Page.newBuilder()
                .setSize(page.size() == null ? "A4" : page.size())
                .setLandscape(page.landscape())
                .setMarginMillimetres(page.marginMillimetres())
                .build());
      }
      return header.build();
    }
  }

  /**
   * Turns the Java document model into the contract's messages.
   *
   * <p>Its own class rather than a method here, because the <b>host channel</b> turns the same
   * messages back into the same model going the other way (ADR-0071), and a translation written
   * twice is a translation that disagrees with itself.
   */
  static final class DocumentWire {

    private DocumentWire() {}

    /**
     * One block, as the contract carries it.
     *
     * @param block the block
     * @return the message
     */
    static de.greluc.homeinv.plugin.v1.Block blockOf(DocumentRenderer.Block block) {
      var builder = de.greluc.homeinv.plugin.v1.Block.newBuilder();
      switch (block) {
        case DocumentRenderer.Heading heading ->
            builder.setHeading(
                de.greluc.homeinv.plugin.v1.Heading.newBuilder()
                    .setLevel(heading.level())
                    .setText(nonNull(heading.text())));
        case DocumentRenderer.Paragraph paragraph ->
            builder.setParagraph(
                de.greluc.homeinv.plugin.v1.Paragraph.newBuilder()
                    .setText(nonNull(paragraph.text())));
        case DocumentRenderer.Facts facts -> {
          var message =
              de.greluc.homeinv.plugin.v1.Facts.newBuilder().setCaption(nonNull(facts.caption()));
          for (DocumentRenderer.Fact fact : facts.entries()) {
            message.addEntries(
                de.greluc.homeinv.plugin.v1.Fact.newBuilder()
                    .setLabel(nonNull(fact.label()))
                    .setValue(nonNull(fact.value())));
          }
          builder.setFacts(message);
        }
        case DocumentRenderer.Table table -> {
          var message =
              de.greluc.homeinv.plugin.v1.Table.newBuilder()
                  .setCaption(nonNull(table.caption()))
                  .addAllColumns(table.columns())
                  .addAllNumericColumns(table.numeric() == null ? List.of() : table.numeric());
          for (List<String> row : table.rows()) {
            message.addRows(
                de.greluc.homeinv.plugin.v1.TableRow.newBuilder().addAllCells(row).build());
          }
          builder.setTable(message);
        }
        case DocumentRenderer.Image image ->
            builder.setImage(
                de.greluc.homeinv.plugin.v1.Image.newBuilder()
                    .setContent(ByteString.copyFrom(image.content()))
                    .setMediaType(nonNull(image.mediaType()))
                    .setCaption(nonNull(image.caption()))
                    .setWidthMillimetres(image.widthMillimetres()));
        case DocumentRenderer.PageBreak ignored ->
            builder.setPageBreak(de.greluc.homeinv.plugin.v1.PageBreak.getDefaultInstance());
        case DocumentRenderer.Spacer spacer ->
            builder.setSpacer(
                de.greluc.homeinv.plugin.v1.Spacer.newBuilder()
                    .setMillimetres(spacer.millimetres()));
      }
      return builder.build();
    }

    /**
     * The text, or an empty string.
     *
     * <p>Protobuf has no null for a string and setting one throws. An absent caption is an empty
     * caption on the wire, which is what the contract says it means.
     *
     * @param text the text, possibly null
     * @return it, or empty
     */
    private static String nonNull(String text) {
      return text == null ? "" : text;
    }
  }
}
