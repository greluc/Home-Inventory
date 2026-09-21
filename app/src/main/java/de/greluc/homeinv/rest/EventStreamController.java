/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.PublicEndpoint;
import de.greluc.homeinv.eventstream.api.LiveStreams;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The live stream an open view listens on (REQ-API-011).
 *
 * <h2>What a client gets</h2>
 *
 * <pre>
 * event: item
 * data: {"kind":"item","at":"2026-09-21T11:04:05Z"}
 * </pre>
 *
 * <p>A kind and a moment. <b>No id and no contents</b> — the view re-reads what it is showing
 * through the ordinary API, which applies the ordinary permissions. {@link LiveChanges} says why
 * that is the design rather than a simplification.
 *
 * <h2>What holds the connection open</h2>
 *
 * <p>A comment every thirty seconds. A proxy with an idle timeout closes a stream that says
 * nothing, and a home inventory can be quiet for hours — so the quiet case is the normal case, and
 * a stream that dies in it is a feature that works in a demonstration and not in a house.
 *
 * <p>Response buffering has to be off at the ingress for any of this to arrive, which
 * {@code deploy/} configures and [06 §6.9] explains.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventStreamController {

  /**
   * How long one stream lives before the client is asked to reconnect.
   *
   * <p>Half an hour rather than for ever: a connection that never ends is a connection whose
   * server-side state never gets cleaned up if anything goes wrong, and the browser's own
   * {@code EventSource} reconnects on its own.
   */
  private static final Duration LIFETIME = Duration.ofMinutes(30);

  private final LiveStreams live;

  /**
   * Opens a stream for the caller's tenant.
   *
   * @param user the authenticated principal
   * @return the stream, which the container keeps open
   */
  @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(
      summary = "Live changes in this tenant",
      description =
          "Server-sent events carrying a kind and a moment, so an open view can refresh "
              + "itself. It carries no ids and no contents: a view re-reads what it shows "
              + "through the ordinary API, which applies the ordinary permissions.")
  @PublicEndpoint(
      reason =
          "It says that something of a kind changed in the caller's OWN tenant and nothing "
              + "else — no id, no name, no field. There is no role low enough to be denied "
              + "that, and every re-read it prompts is permitted or refused on its own.")
  public SseEmitter events(@AuthenticationPrincipal AuthenticatedUser user) {
    UUID tenantId = user.tenantId();
    SseEmitter emitter = new SseEmitter(LIFETIME.toMillis());

    if (tenantId == null) {
      // A session that acts for no tenant is a state rather than a failure
      // (somebody between tenants, or an instance operator). There is nothing to
      // stream, so the stream ends at once rather than waiting thirty minutes.
      emitter.complete();
      return emitter;
    }

    LiveStreams.Stream stream =
        (kind, at) -> {
          try {
            emitter.send(
                SseEmitter.event()
                    .name(kind)
                    .data(
                        "{\"kind\":\"" + kind + "\",\"at\":\"" + at + "\"}",
                        MediaType.APPLICATION_JSON));
          } catch (IOException | IllegalStateException gone) {
            // The browser closed the tab. Completing here is what removes it from
            // the list; an exception on a send is the only notice a server gets.
            emitter.complete();
          }
        };

    if (!live.register(tenantId, stream)) {
      // As many as this replica will hold for one tenant. Answered by ending the
      // stream rather than by refusing the request: a client that reconnects is
      // doing the right thing, and an error page in a browser's EventSource is
      // a retry loop nobody sees.
      log.info("Refused a live stream for tenant {}: this replica holds as many as it will", tenantId);
      emitter.complete();
      return emitter;
    }

    emitter.onCompletion(() -> live.forget(tenantId, stream));
    emitter.onTimeout(
        () -> {
          live.forget(tenantId, stream);
          emitter.complete();
        });
    emitter.onError(failure -> live.forget(tenantId, stream));

    try {
      // Immediately, so a client knows the stream is open rather than merely
      // accepted — and so a proxy that buffers is caught in development instead
      // of in somebody's house.
      emitter.send(SseEmitter.event().name("open").data("{\"kind\":\"open\"}", MediaType.APPLICATION_JSON));
    } catch (IOException gone) {
      emitter.complete();
    }
    return emitter;
  }
}
