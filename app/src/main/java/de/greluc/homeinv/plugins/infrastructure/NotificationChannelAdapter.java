/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.infrastructure;

import com.google.protobuf.ByteString;
import de.greluc.homeinv.plugin.api.CallContext;
import de.greluc.homeinv.plugin.api.port.NotificationChannel;
import de.greluc.homeinv.plugin.v1.NotificationAttachment;
import de.greluc.homeinv.plugin.v1.NotificationChannelGrpc;
import de.greluc.homeinv.plugin.v1.NotificationChannelDescriptor;
import de.greluc.homeinv.plugin.v1.NotificationDeliverRequest;
import de.greluc.homeinv.plugin.v1.NotificationDeliverResponse;
import de.greluc.homeinv.plugin.v1.NotificationDescribeRequest;
import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Speaks {@code NotificationChannel} to a plugin (REQ-NOTI-001, ADR-0026).
 *
 * <p>The first adapter of the runtime, and the one stage 1 cannot do without: every invitation and
 * every password reset leaves through a channel, and there is no in-core channel because the core
 * has no outbound route.
 */
@Component
public class NotificationChannelAdapter implements PortAdapter<NotificationChannel> {

  @Override
  public Class<NotificationChannel> port() {
    return NotificationChannel.class;
  }

  @Override
  public NotificationChannel adapt(Channel channel, int deadlineMillis) {
    return new Grpc(channel, deadlineMillis);
  }

  /**
   * One plugin's notification channel, over gRPC.
   *
   * @param channel the plugin's channel
   * @param deadlineMillis how long a call may take
   */
  private record Grpc(Channel channel, int deadlineMillis) implements NotificationChannel {

    @Override
    public Descriptor describe(CallContext context) {
      try {
        NotificationChannelDescriptor answer =
            stub()
                .describe(
                    NotificationDescribeRequest.newBuilder()
                        .setContext(PluginWire.contextOf(context))
                        .build());
        return new Descriptor(
            answer.getChannelKey(),
            answer.getName(),
            addressSchemes(answer),
            answer.getSupportsHtml());
      } catch (StatusRuntimeException failed) {
        throw PluginWire.failureOf(failed, channel.authority());
      }
    }

    @Override
    public Delivery deliver(CallContext context, Message message) {
      NotificationDeliverRequest.Builder request =
          NotificationDeliverRequest.newBuilder()
              .setContext(PluginWire.contextOf(context))
              .setRecipient(message.recipient())
              .setSubject(nullToEmpty(message.subject()))
              .setText(nullToEmpty(message.text()))
              .setHtml(nullToEmpty(message.html()))
              .setLanguage(nullToEmpty(message.language()))
              .setIdempotencyKey(nullToEmpty(message.idempotencyKey()));
      if (message.headers() != null) {
        request.putAllHeaders(message.headers());
      }
      for (Attachment attachment : orEmpty(message.attachments())) {
        request.addAttachments(
            NotificationAttachment.newBuilder()
                .setFileName(attachment.fileName())
                .setMediaType(attachment.mediaType())
                .setContent(ByteString.copyFrom(attachment.content()))
                .build());
      }

      try {
        NotificationDeliverResponse answer = stub().deliver(request.build());
        return new Delivery(
            answer.getProviderMessageId(), answer.getDetail(), answer.getDeduplicated());
      } catch (StatusRuntimeException failed) {
        throw PluginWire.failureOf(failed, channel.authority());
      }
    }

    /**
     * A stub with this call's deadline on it.
     *
     * <p>Per call, because a deadline set once on a stub counts from when the stub was made, and a
     * stub kept for the life of a channel would hand every call a deadline that had already passed.
     *
     * @return the stub
     */
    private NotificationChannelGrpc.NotificationChannelBlockingStub stub() {
      return NotificationChannelGrpc.newBlockingStub(channel)
          .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS);
    }

    private static Set<String> addressSchemes(NotificationChannelDescriptor answer) {
      return new LinkedHashSet<>(answer.getAddressSchemesList());
    }

    private static String nullToEmpty(String value) {
      return value == null ? "" : value;
    }

    private static List<Attachment> orEmpty(List<Attachment> attachments) {
      return attachments == null ? List.of() : attachments;
    }
  }
}
