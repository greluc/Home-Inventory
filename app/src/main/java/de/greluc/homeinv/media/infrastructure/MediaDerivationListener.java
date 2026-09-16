/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.infrastructure;

import de.greluc.homeinv.media.api.MediaObjectStored;
import de.greluc.homeinv.media.api.ScannerUnavailableException;
import de.greluc.homeinv.media.application.DerivativeGenerator;
import de.greluc.homeinv.media.application.MediaScanRunner;
import de.greluc.homeinv.media.domain.ScanState;
import de.greluc.homeinv.platform.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The {@code worker}'s consumer for {@link MediaObjectStored}: it scans the object, and derives its
 * variants when the scan came back clean.
 *
 * <h2>Why the binding is declared here and not in a configuration class</h2>
 *
 * <p>Because the queue belongs to the consumer. {@code api} publishes to an exchange and knows
 * nothing about who listens; a queue declared centrally would be a queue that exists whether or not
 * anything reads it, and a queue nobody reads fills up.
 *
 * <h2>The tenant context is established from the message</h2>
 *
 * <p>A consumer has no session and no request, so {@code TenantContext} is empty when a message
 * arrives — and with it empty, every query this makes returns zero rows rather than failing, which
 * is row-level security working and looks exactly like a missing object. The tenant therefore
 * travels in the payload and is pushed here, around the whole unit of work.
 *
 * <p>It is <b>not</b> read from a header a publisher could set independently of the body: the
 * tenant and the object have to come from the same place, or a message could name one tenant in its
 * header and another's object in its body.
 *
 * <h2>Scan first, derive second, and never the other way round</h2>
 *
 * <p>Deriving produces new blobs from the uploaded one. Doing that before a verdict would mean an
 * infected file had thumbnails made of it and stored under their own content addresses, which the
 * deletion on a finding would not reach — the finding deletes the object's blob, and those two are
 * different addresses. So a non-clean verdict returns without deriving anything.
 *
 * <h2>Only in the worker</h2>
 *
 * <p>{@code api} runs the same image and must not consume this queue. It would work — the code is
 * the same — and it would put the scan and the encodes back on the process that serves requests,
 * which is what moving them here was for. It could not work in the generated deployment anyway:
 * {@code clamd} is on the {@code scanner} segment, which {@code api} is not a member of (06 §6.4).
 */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
public class MediaDerivationListener {

  /**
   * How many times a scan that reached no verdict is tried before it is left alone.
   *
   * <p>Twelve, at the five-minute delay {@code MediaMessagingConfiguration} gives the retry queue:
   * an hour of a scanner being down is recovered from without anybody doing anything, and a scanner
   * that is down for longer is an operational problem that retrying forever would hide rather than
   * solve. The object stays {@code SCAN_FAILED} and unretrievable either way.
   */
  private static final int MAX_SCAN_ATTEMPTS = 12;

  /** Not part of the event schema: attempts belong to this delivery, not to what happened. */
  private static final String ATTEMPTS_HEADER = "x-scan-attempts";

  private final MediaScanRunner scanner;
  private final DerivativeGenerator generator;
  private final RabbitTemplate rabbit;

  /**
   * Scans one stored media object and derives its variants when it is clean.
   *
   * @param event what was stored
   * @param message the delivery, for the attempt counter a retry carries
   */
  @RabbitListener(
      bindings =
          @QueueBinding(
              // Durable and named, so a worker that is restarted finds the work
              // that arrived while it was down. An anonymous exclusive queue
              // would lose every message published during a deployment.
              value = @Queue(name = "homeinv.media.derivation", durable = "true"),
              exchange = @Exchange(name = "homeinv.media", type = "topic", durable = "true"),
              key = "media-object-stored.v1"))
  public void onMediaObjectStored(MediaObjectStored event, Message message) {
    TenantContext.runAs(
        event.tenantId(),
        () -> {
          ScanState state;
          try {
            state = scanner.scan(event.tenantId(), event.mediaObjectId());
          } catch (ScannerUnavailableException noVerdict) {
            // Acknowledged and recorded rather than rethrown. Rethrowing returns
            // the message to the broker immediately and it comes straight back,
            // which is a hot loop against a scanner that is down — the delay
            // queue is what makes the retry a retry (ADR-0054).
            scanner.recordScanFailed(event.tenantId(), event.mediaObjectId());
            scheduleRetry(event, message, noVerdict);
            return;
          }

          if (state != ScanState.CLEAN) {
            // INFECTED, or the object was gone before this ran. Either way there
            // is nothing to derive and nothing to retry.
            return;
          }

          generator.derive(event.tenantId(), event.mediaObjectId());
        });
  }

  /**
   * Puts the event back on the delay queue, or gives up and says so.
   *
   * @param event the event to try again
   * @param message the delivery it arrived on, carrying the attempts so far
   * @param noVerdict why this attempt reached no verdict
   */
  private void scheduleRetry(MediaObjectStored event, Message message, RuntimeException noVerdict) {
    int attempts = attemptsOf(message) + 1;
    if (attempts >= MAX_SCAN_ATTEMPTS) {
      log.error(
          "Media object {} of tenant {} has reached no verdict in {} attempts and will not be "
              + "tried again; it stays unretrievable. The scanner is the thing to look at: {}",
          event.mediaObjectId(),
          event.tenantId(),
          attempts,
          noVerdict.getMessage());
      return;
    }

    rabbit.convertAndSend(
        "",
        MediaMessagingConfiguration.SCAN_RETRY_QUEUE,
        event,
        outgoing -> {
          outgoing.getMessageProperties().setHeader(ATTEMPTS_HEADER, attempts);
          return outgoing;
        });
    log.info(
        "No verdict for media object {} of tenant {}; attempt {} of {} follows in {} ms.",
        event.mediaObjectId(),
        event.tenantId(),
        attempts + 1,
        MAX_SCAN_ATTEMPTS,
        MediaMessagingConfiguration.RETRY_DELAY_MS);
  }

  /**
   * Reads the attempt counter a retry carries.
   *
   * @param message the delivery
   * @return how many attempts this message has already had, or zero on its first delivery
   */
  private static int attemptsOf(Message message) {
    Object header = message.getMessageProperties().getHeader(ATTEMPTS_HEADER);
    return header instanceof Number counted ? counted.intValue() : 0;
  }
}
