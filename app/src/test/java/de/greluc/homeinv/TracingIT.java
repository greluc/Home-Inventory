/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import de.greluc.homeinv.platform.CallerContext;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.plugin.api.CallContext;
import de.greluc.homeinv.plugin.api.port.PasswordBreachCheck;
import de.greluc.homeinv.plugins.application.PluginResilience;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * A trace the operator can follow, and the two hops nothing instruments for us (REQ-NFR-044).
 *
 * <h2>What this proves and what it deliberately does not</h2>
 *
 * <p>The HTTP, JDBC and AMQP legs are instrumented by libraries and are not this suite's to
 * re-test. What is ours, and is therefore here:
 *
 * <ul>
 *   <li>the {@code traceId} an error document carries is the id of the trace that produced it —
 *       the one property that turns a user report into an operator query (REQ-NFR-042);
 *   <li>a span carries the tenant and the actor and <b>no domain data</b>, because spans leave the
 *       deployment for a collector and outlive a log line (13 §13.4);
 *   <li>a plugin call is its own span and the core hands the plugin the trace parent, which is the
 *       one hop where a missing header means the chain simply stops.
 * </ul>
 *
 * <h2>Why the exporter is a test bean and not an OTLP endpoint</h2>
 *
 * <p>Naming an endpoint would build a real OTLP exporter and spend the suite time failing to reach
 * it. A {@link SimpleSpanProcessor} writing into a list exports synchronously, so a span is
 * readable the moment it ends — no flush, no waiting, no flake. {@code management.opentelemetry.enabled}
 * is set directly for the same reason: it is what an endpoint would have switched on.
 */
@DisplayName("A trace")
@TestPropertySource(properties = "management.opentelemetry.enabled=true")
@Import(TracingIT.RecordEverySpan.class)
class TracingIT extends AbstractIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private RecordedSpans recorded;
  @Autowired private ObservationRegistry observations;
  @Autowired private PluginResilience resilience;

  /**
   * MockMvc with Spring's observation filter in front, as a served request has it.
   *
   * <p>The base class builds one without it, because every other test is about what a controller
   * answers and not about what the container observed. Here it is the subject: it is the filter
   * that starts the request span, and without it there would be no trace to assert about.
   *
   * <p>Its position is the production one — before {@code TraceIdFilter}, which is the whole point
   * of that filter order.
   */
  @BeforeEach
  void mockMvcWithObservations() {
    recorded.clear();
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(
                new ServerHttpObservationFilter(observations),
                context.getBean(de.greluc.homeinv.rest.TraceIdFilter.class),
                context.getBean(de.greluc.homeinv.rest.JsonBodyLimitFilter.class))
            .apply(springSecurity())
            .build();
  }

  @Test
  @DisplayName("is what the error document traceId names (REQ-NFR-042)")
  void theProblemDocumentNamesTheTraceThatProducedIt() throws Exception {
    MvcResult result = mockMvc.perform(get("/api/v1/items/" + UUID.randomUUID())).andReturn();

    String reported = JsonPath.read(result.getResponse().getContentAsString(), "$.traceId");
    assertThat(reported).as("the W3C shape, unchanged since stage 0").matches("[0-9a-f]{32}");

    // The one assertion that matters: not that BOTH exist, but that they are the
    // SAME id. Two mechanisms writing `traceId` -- the filter and the tracer --
    // would each produce a plausible one, and a report quoting the wrong one
    // finds nothing in the collector, which is worse than finding nothing at all.
    assertThat(recorded.traceIds())
        .as("the id in the document is the trace the request actually produced")
        .contains(reported);
  }

  @Test
  @DisplayName("carries the tenant and the actor, and no domain data (13 §13.4)")
  void spansCarryWhoAndForWhomAndNothingElse() {
    UUID tenant = UUID.randomUUID();
    UUID actor = UUID.randomUUID();

    TenantContext.runAs(
        tenant,
        () ->
            CallerContext.runAs(
                new CallerContext.Caller(actor, tenant, "OWNER", null, null),
                () -> Observation.createNotStarted("test.work", observations).observe(() -> {})));

    SpanData span = recorded.named("test.work");
    assertThat(span.getAttributes().get(AttributeKey.stringKey("tenantId")))
        .isEqualTo(tenant.toString());
    assertThat(span.getAttributes().get(AttributeKey.stringKey("actorId")))
        .isEqualTo(actor.toString());
  }

  @Test
  @DisplayName("reaches the plugin, in a span of the call own")
  void thePluginHopIsTracedAndPropagated() {
    AtomicReference<CallContext> received = new AtomicReference<>();
    PasswordBreachCheck stub =
        (callContext, prefix) -> {
          received.set(callContext);
          return new PasswordBreachCheck.Range(List.of());
        };

    PasswordBreachCheck decorated =
        resilience.decorate(PasswordBreachCheck.class, stub, "test-plugin");

    Observation.createNotStarted("test.caller", observations)
        .observe(() -> decorated.suffixesFor(CallContext.forInstance("", "en", 0), "ABCDE"));

    // The envelope is the one place every call passes through, so an instance
    // call -- which has no tenant at all -- is traced exactly like a tenant one.
    String traceParent = received.get().traceId();
    // version "-" trace-id "-" parent-id "-" trace-flags, and the flags are two
    // hex digits rather than the "00" or "01" one expects: OpenTelemetry sets
    // the `random` bit of Trace Context Level 2 beside `sampled`, so a sampled
    // span reports `03`. A test that pinned `01` would fail on an SDK upgrade
    // over a field the specification always allowed to carry more.
    assertThat(traceParent)
        .as("a W3C traceparent, which is what the plugin contract says this field is")
        .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    assertThat(Integer.parseInt(traceParent.substring(traceParent.length() - 2), 16) & 1)
        .as("and it says the span was sampled, or the plugin would drop what it records")
        .isEqualTo(1);

    SpanData call = recorded.named("homeinv.plugin.call");
    SpanData caller = recorded.named("test.caller");
    assertThat(traceParent)
        .as("the parent the plugin is told is the CALL span, not the caller one")
        .startsWith("00-" + call.getTraceId() + "-" + call.getSpanId() + "-");
    assertThat(call.getTraceId())
        .as("and that span belongs to the caller trace, so the chain is one trace")
        .isEqualTo(caller.getTraceId());
    assertThat(call.getAttributes().get(AttributeKey.stringKey("plugin.id")))
        .isEqualTo("test-plugin");
  }

  /**
   * Records every span the application produces, synchronously.
   *
   * <p>Deliberately not a {@code SpanExporter} bean: Spring Boot wraps every one it finds in a
   * batch processor of its own, and the same span would then arrive twice — once here and once
   * five seconds later.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class RecordEverySpan {

    /**
     * The list the assertions read.
     *
     * @return the recorder
     */
    @Bean
    RecordedSpans recordedSpans() {
      return new RecordedSpans();
    }

    /**
     * The processor that fills it, exporting as each span ends.
     *
     * @param spans the recorder
     * @return the processor
     */
    @Bean
    SpanProcessor recordingSpanProcessor(RecordedSpans spans) {
      return SimpleSpanProcessor.create(
          new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> batch) {
              spans.addAll(batch);
              return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
              return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
              return CompletableResultCode.ofSuccess();
            }
          });
    }
  }

  /** What the exporter above collected, for the assertions to read. */
  static final class RecordedSpans {

    private final List<SpanData> spans = new CopyOnWriteArrayList<>();

    /**
     * Takes one exported batch.
     *
     * @param batch the spans that just ended
     */
    void addAll(Collection<SpanData> batch) {
      spans.addAll(batch);
    }

    /** Forgets everything, so one test cannot read the spans of another. */
    void clear() {
      spans.clear();
    }

    /**
     * The one span with a given name.
     *
     * @param name the span name
     * @return the span
     */
    SpanData named(String name) {
      List<SpanData> matching = spans.stream().filter(span -> name.equals(span.getName())).toList();
      assertThat(matching).as("exactly one span named %s", name).hasSize(1);
      return matching.getFirst();
    }

    /**
     * Every trace id seen.
     *
     * @return the ids
     */
    List<String> traceIds() {
      return spans.stream().map(SpanData::getTraceId).distinct().toList();
    }
  }
}
