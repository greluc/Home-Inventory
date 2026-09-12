/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The log is JSON lines carrying {@code traceId}, {@code tenantId} and {@code actorId}
 * (REQ-NFR-041).
 *
 * <h2>Why the encoder and not the console</h2>
 *
 * <p>Logback binds the console appender to {@code System.out} when it starts, so a test that
 * replaced the stream afterwards would capture nothing and pass for the wrong reason. Asking the
 * appender for its encoder and encoding an event is the same code path a real line takes, minus the
 * stream.
 *
 * <p>This verifies the requirement's own gate — "The format verified" — which until 2026-09-12
 * nothing did: {@code application.yaml} carried a pattern naming the three MDC fields and a comment
 * saying the structured encoder was "configured per profile", and no profile configured one. Every
 * line was plain text.
 */
@DisplayName("The log")
class LogFormatIT extends AbstractIntegrationTest {

  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("is JSON lines with traceId, tenantId and actorId (REQ-NFR-041)")
  void everyLineIsJsonWithTheCorrelationFields() {
    LoggingEvent event = new LoggingEvent();
    event.setLoggerName("de.greluc.homeinv.Example");
    event.setLevel(Level.WARN);
    event.setMessage("A line worth correlating");
    event.setTimeStamp(System.currentTimeMillis());
    event.setMDCPropertyMap(
        Map.of(
            "traceId", "4bf92f3577b34da6a3ce929d0e0e4736",
            "tenantId", "6f2a1f52-0a1e-4f3a-9a1b-0d3f2c5b7e91",
            "actorId", "1c9f8d7e-6b5a-4c3d-8e2f-0a1b2c3d4e5f"));

    String line = new String(encoder().encode(event), StandardCharsets.UTF_8);

    JsonNode parsed = json.readTree(line);
    assertThat(parsed.get("message").asString()).isEqualTo("A line worth correlating");
    assertThat(parsed.get("log").get("level").asString()).isEqualTo("WARN");
    assertThat(parsed.get("log").get("logger").asString()).isEqualTo("de.greluc.homeinv.Example");
    assertThat(parsed.get("@timestamp").asString()).isNotEmpty();

    // The three fields REQ-NFR-041 names, as queryable members rather than as
    // text inside the message. ECS puts the MDC at the top level.
    assertThat(parsed.get("traceId").asString()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    assertThat(parsed.get("tenantId").asString())
        .isEqualTo("6f2a1f52-0a1e-4f3a-9a1b-0d3f2c5b7e91");
    assertThat(parsed.get("actorId").asString()).isEqualTo("1c9f8d7e-6b5a-4c3d-8e2f-0a1b2c3d4e5f");
  }

  /**
   * The encoder the console appender writes every line through.
   *
   * @return the encoder
   */
  @SuppressWarnings("unchecked")
  private static Encoder<ILoggingEvent> encoder() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    Appender<ILoggingEvent> console =
        context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE");
    assertThat(console)
        .as("the root logger writes to a console appender")
        .isInstanceOf(OutputStreamAppender.class);
    return ((OutputStreamAppender<ILoggingEvent>) console).getEncoder();
  }
}
