/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import de.greluc.homeinv.platform.CurrentTrace;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Unconfigured means off, and off means no observation becomes a span (REQ-NFR-044).
 *
 <h2>What "off" is, exactly</h2>
 *
 * <p>Spring Boot disabled is not Spring Boot absent. It installs a {@code disabledOpenTelemetrySdk}
 * — a context with <b>no span processor and no exporter</b>, whose spans are never recorded and
 * never leave the process — and it goes on minting valid identifiers, because its own
 * documentation says that disabling OpenTelemetry leaves the <i>propagators</i> configured.
 *
 * <p>So the assertions here are about the two things that matter and can be stated truthfully:
 * nothing records a span, nothing sends one, and the trace parent a plugin would be handed is the
 * empty string its contract promises rather than a well-formed pointer to a trace that exists
 * nowhere.
 *
 * <p>This is the default context — the one every other integration test shares — which is the
 * point: tracing being off is the state the whole suite runs in, and that state is what a
 * deployment gets by doing nothing.
 */
@DisplayName("With no collector configured, tracing")
class TracingOffIT extends AbstractIntegrationTest {

  @Autowired private ApplicationContext context;
  @Autowired private Environment environment;
  @Autowired private ObservationRegistry observations;
  @Autowired private CurrentTrace trace;

  @Test
  @DisplayName("has nothing wired that could record or send a span")
  void nothingIsWired() {
    assertThat(environment.getProperty("management.opentelemetry.enabled"))
        .as("derived from the empty endpoint by TracingEnvironment, not written in any file")
        .isEqualTo("false");

    assertThat(context.getBeanNamesForType(TracingObservationHandler.class))
        .as("nothing turns an observation into a span, which is what the excludes are for")
        .isEmpty();
    assertThat(context.getBeanNamesForType(SdkTracerProvider.class))
        .as("no tracer provider")
        .isEmpty();
    assertThat(context.getBeanNamesForType(SpanProcessor.class))
        .as("nothing records a span")
        .isEmpty();
    assertThat(context.getBeanNamesForType(SpanExporter.class))
        .as("and nothing would send one anywhere")
        .isEmpty();
    assertThat(context.getBean(Tracer.class))
        .as("the tracer that is left is the no-op one, which is what the bridge being absent means")
        .isSameAs(Tracer.NOOP);
  }

  @Test
  @DisplayName("hands a plugin the empty trace parent its contract promises")
  void anObservationProducesNoTraceParent() {
    AtomicReference<String> parent = new AtomicReference<>();

    Observation.createNotStarted("test.work", observations)
        .observe(() -> parent.set(trace.traceparent()));

    // `CallContext.trace_id` is documented as "empty when tracing is off", and
    // this is the state that sentence describes. A plugin reading it gets an
    // absence rather than a well-formed parent pointing at a span nobody kept.
    assertThat(parent.get()).isEmpty();
  }

  @Test
  @DisplayName("takes nothing away: the error document still names the request (REQ-NFR-042)")
  void theTraceIdSurvivesWithoutATracer() throws Exception {
    MvcResult result = mockMvc.perform(get("/api/v1/items/" + UUID.randomUUID())).andReturn();

    String traceId = JsonPath.read(result.getResponse().getContentAsString(), "$.traceId");
    // TraceIdFilter, exactly as at stage 0. A self-hoster who runs no collector
    // -- which is most of them -- loses nothing they had.
    assertThat(traceId).matches("[0-9a-f]{32}");
  }
}
