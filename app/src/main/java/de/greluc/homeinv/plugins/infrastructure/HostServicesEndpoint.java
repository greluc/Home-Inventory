/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.infrastructure;

import de.greluc.homeinv.platform.LogSafe;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.plugin.api.CallContext;
import de.greluc.homeinv.plugin.api.PluginException;
import de.greluc.homeinv.plugin.api.port.DocumentRenderer;
import de.greluc.homeinv.plugin.v1.DocumentRendererRenderRequest;
import de.greluc.homeinv.plugin.v1.DocumentRendererRenderResponse;
import de.greluc.homeinv.plugin.v1.HostServicesGrpc;
import de.greluc.homeinv.plugin.v1.RenderedHeader;
import de.greluc.homeinv.plugins.api.ExtensionRegistry;
import de.greluc.homeinv.plugins.api.PluginRegistry;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * What a plugin may ask the core for (ADR-0071).
 *
 * <p>The one service in this system that runs the other way round. Everything about it is narrow on
 * purpose, and the narrowness is enforced in three places rather than assumed:
 *
 * <ol>
 *   <li>the connection, where {@link RegisteredPlugins} refuses a certificate that is not a
 *       registered plugin's;
 *   <li>the call, where the capability {@code host:render-document} is checked for the tenant the
 *       envelope names;
 *   <li>the contract, which has one method on it and no way to read anything.
 * </ol>
 *
 * <p>A missing grant and a missing renderer are the <b>same</b> answer. A caller that could tell
 * them apart would have a way to find out what a tenant has consented to, one probe at a time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HostServicesEndpoint extends HostServicesGrpc.HostServicesImplBase {

  /** The capability a plugin needs to ask for a document (REQ-PLG-005). */
  public static final String CAPABILITY = "host:render-document";

  /** How many blocks one document may carry, so a caller cannot ask the core to hold a library. */
  private static final int MAX_BLOCKS = 10_000;

  private final ExtensionRegistry extensions;
  private final PluginRegistry registrations;
  private final CallerIdentity callers;

  @Override
  public StreamObserver<DocumentRendererRenderRequest> renderDocument(
      StreamObserver<DocumentRendererRenderResponse> responses) {

    String callerId = callers.current().orElse(null);
    return new StreamObserver<>() {

      private final List<DocumentRenderer.Block> blocks = new ArrayList<>();
      private String title = "";
      private String mediaType = "application/pdf";
      private java.util.Map<String, String> metadata = java.util.Map.of();
      private DocumentRenderer.Page page = new DocumentRenderer.Page("A4", false, 18);
      private UUID tenantId;
      private String language = "en";
      private boolean broken;

      @Override
      public void onNext(DocumentRendererRenderRequest part) {
        if (broken) {
          return;
        }
        if (part.hasHeader()) {
          title = part.getHeader().getTitle();
          metadata = part.getHeader().getMetadataMap();
          mediaType =
              part.getHeader().getMediaType().isBlank()
                  ? "application/pdf"
                  : part.getHeader().getMediaType();
          if (part.getHeader().hasPage()) {
            page =
                new DocumentRenderer.Page(
                    part.getHeader().getPage().getSize(),
                    part.getHeader().getPage().getLandscape(),
                    part.getHeader().getPage().getMarginMillimetres());
          }
          // THE TENANT COMES FROM THE ENVELOPE AND IS NOT TAKEN ON TRUST AS AN
          // IDENTITY: what it selects is which tenant's renderer and which
          // tenant's grant apply, both of which are checked below. A caller
          // naming a tenant it has no grant for gets the same refusal as one
          // naming a tenant that does not exist.
          String named = part.getHeader().getContext().getTenantId();
          tenantId = named.isBlank() ? null : UUID.fromString(named);
          language =
              part.getHeader().getContext().getLanguage().isBlank()
                  ? "en"
                  : part.getHeader().getContext().getLanguage();
          return;
        }
        if (blocks.size() >= MAX_BLOCKS) {
          broken = true;
          responses.onError(
              Status.RESOURCE_EXHAUSTED
                  .withDescription("A document may carry at most " + MAX_BLOCKS + " blocks")
                  .asRuntimeException());
          return;
        }
        blocks.add(DocumentWireIn.blockOf(part.getBlock()));
      }

      @Override
      public void onError(Throwable error) {
        // The caller hung up. Nothing was written and nothing needs undoing:
        // rendering a document changes no state.
        log.debug("A plugin abandoned a render request", error);
      }

      @Override
      public void onCompleted() {
        if (broken) {
          return;
        }
        if (callerId == null) {
          responses.onError(
              Status.UNAUTHENTICATED
                  .withDescription("The caller is not a registered plugin")
                  .asRuntimeException());
          return;
        }
        if (tenantId == null) {
          responses.onError(
              Status.INVALID_ARGUMENT
                  .withDescription("The call names no tenant")
                  .asRuntimeException());
          return;
        }

        try {
          DocumentRenderer.Rendered rendered = render();
          responses.onNext(
              DocumentRendererRenderResponse.newBuilder()
                  .setHeader(
                      RenderedHeader.newBuilder()
                          .setMediaType(rendered.mediaType() == null ? mediaType
                              : rendered.mediaType())
                          .setSuggestedFilename(
                              rendered.suggestedFilename() == null
                                  ? ""
                                  : rendered.suggestedFilename())
                          .build())
                  .build());
          responses.onNext(
              DocumentRendererRenderResponse.newBuilder()
                  .setContent(ByteString.copyFrom(rendered.content()))
                  .build());
          responses.onCompleted();
        } catch (NotAllowed refused) {
          // One answer for two conditions, deliberately (ADR-0071).
          responses.onError(
              Status.FAILED_PRECONDITION
                  .withDescription(
                      "No document renderer is available for this call. Either none is installed "
                          + "for that tenant, or this plugin has not been granted "
                          + CAPABILITY)
                  .asRuntimeException());
        } catch (PluginException failed) {
          responses.onError(
              Status.INTERNAL
                  .withDescription("The renderer could not produce the document")
                  .asRuntimeException());
          // The caller id comes off a plugin's certificate, which the operator
          // installed but this process did not choose.
          log.warn("A host render failed for plugin {}", LogSafe.value(callerId), failed);
        }
      }

      /**
       * Checks the grant and renders.
       *
       * @return the document
       * @throws NotAllowed when the plugin may not ask, or nothing can render
       */
      private DocumentRenderer.Rendered render() {
        return TenantContext.callAs(
            tenantId,
            () -> {
              if (!registrations.permits(callerId, CAPABILITY)) {
                throw new NotAllowed();
              }
              DocumentRenderer renderer =
                  extensions
                      .lookup(DocumentRenderer.class, tenantId)
                      .orElseThrow(NotAllowed::new);
              log.debug("Plugin {} asked the core to render a document", callerId);
              return renderer.render(
                  new CallContext(tenantId, "", language, 0),
                  new DocumentRenderer.Document(title, metadata, page, List.copyOf(blocks)),
                  mediaType);
            });
      }
    };
  }

  /** Raised for both refusals, so that one answer covers them. */
  private static final class NotAllowed extends RuntimeException {

    private static final long serialVersionUID = 1L;

    NotAllowed() {
      super(null, null, false, false);
    }
  }
}
