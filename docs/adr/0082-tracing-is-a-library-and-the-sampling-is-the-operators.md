<!--
SPDX-FileCopyrightText: Lucas Greuloch
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR-0082 — Tracing is a library, and the sampling decision is the operator's

**Status:** Accepted · **Date:** 2026-09-21

## Context

`REQ-NFR-044` asks for *"distributed tracing through OpenTelemetry, configurable and inactive
when unconfigured"*, with the acceptance criterion *"a trace visible across core, broker,
worker and plugin"*. [`13 §13.4`](../architecture/13-operations-and-observability.md#134-distributed-tracing)
had written down how, years before anything existed: **"OpenTelemetry (Java agent,
automatic)"**, and a sampling row reading **"100 % for errors and slow requests, 5 %
otherwise"**.

Implementing it showed that both sentences were wrong in the same way — each described a
component this project does not control as though it were ours.

## Options

### How the spans are produced

| Option | What it means | Why not |
|---|---|---|
| **Micrometer Tracing + the OTLP exporter, as libraries** | three dependencies in the version catalogue; Spring Boot already creates observations for the web layer, JDBC, scheduling and AMQP, and the bridge turns those into spans | Chosen |
| The OpenTelemetry Java agent | a `-javaagent` jar fetched at image build time, instrumenting bytecode at startup | It is a **second supply chain**: a binary that is not in the version catalogue, not in the SBOM `REQ-NFR-062` publishes, and not gated by the licence check. It instruments whether or not anything is exported, so "inactive when unconfigured" becomes "active and discarding". And it would have to be in the image for the deployments that never trace — which is all of them by default |
| Instrument by hand | a span where we decide | The HTTP, database and broker legs are the ones worth having and the ones already produced for free |

### Where the sampling decision is taken

| Option | Why not |
|---|---|
| **Tail sampling in the operator's collector; the application samples everything** | Chosen. Keeping "what failed or was slow" is a decision about a **finished** trace, and the application has no finished trace — it has a span, at the moment the span starts |
| Head sampling in the application, at 5 % | What 13 §13.4 said, and it does not do what the same row asks for: a 5 % head sample discards 95 % of the errors *before* anything can observe that they were errors |
| Both | The head sample bounds what the tail policy can ever see. Two policies, one of which silently limits the other |

### Who runs the collector

| Option | Why not |
|---|---|
| **The operator. We publish an example configuration** | Chosen. [`docs/reference/otel-collector.yaml`](../reference/otel-collector.yaml) |
| A collector container in `deploy/` | Retention, redaction and where telemetry is forwarded are exactly the decisions a self-hoster makes for themselves, and a shipped collector would make some of them by default. It is also the component most likely to already exist |

## Decision

**Libraries, one knob, and the policy lives where the whole trace does.**

- `spring-boot-micrometer-tracing-opentelemetry`, `micrometer-tracing-bridge-otel` and
  `opentelemetry-exporter-otlp` — the three modules and **not**
  `spring-boot-starter-opentelemetry`, which also brings `micrometer-registry-otlp`: that
  registry pushes metrics to `http://localhost:4318/v1/metrics` on a schedule, by default,
  with nobody having asked. A core container that opens a connection nobody configured is
  what [ADR-0026](0026-core-outbound-via-plugins.md) exists to prevent.
- **`HOMEINV_TRACING_ENDPOINT` is the whole configuration.** Empty — the default — means
  `management.opentelemetry.enabled=false`: no span processor, no exporter, nothing recorded
  and nothing sent. The translation from "empty" to "absent" is
  `TracingEnvironment`, an `EnvironmentPostProcessor`, because a container runtime cannot
  pass an unset variable and Spring Boot counts the empty string as a value that is present.
  Written as two lines of YAML, every default deployment would have built an exporter for the
  address `""` and failed to start.
- **The collector is inside the deployment.** `api` and `worker` still have no outbound route;
  they reach a collector on `internal`, and where it forwards to afterwards is outside this
  topology. Tracing changes nothing about ADR-0026.
- **`management.tracing.sampling.probability: 1.0`**, and the tail policy of 13 §13.4 moves
  into the collector's configuration, where it is written out.
- **The plugin hop is ours to carry**, because nothing instruments it for us: `PluginResilience`
  starts a span per call (`homeinv.plugin.call`) and fills `CallContext.trace_id` with its W3C
  `traceparent`; `PluginChannels` injects the same context into every call's gRPC metadata.
  The contract already promised both — *"carried in the payload AND as metadata"* — and until
  now the field was the empty string on every call.
- **Spans carry `tenantId` and `actorId` and nothing else from the domain**, as
  high-cardinality key values so that they reach spans and never become meter tags.

## Consequences

- **13 §13.4 is corrected in the same change**, in both places it was wrong. A row that
  describes a component we do not ship is not a design; it is a plan somebody will later read
  as a statement of fact.
- **The `traceId` of `REQ-NFR-042` now has two possible sources and one value.** When a
  collector is configured the tracer owns the id; `TraceIdFilter` moved behind Spring's
  observation filter and fills the MDC only when it finds nothing there. `TracingIT` asserts
  the id in the error document is the id of the trace the request produced — not merely that
  both exist, because two mechanisms would each produce a plausible one and a user report
  quoting the wrong one finds nothing.
- **Unconfigured is unchanged, and that is the point.** `TracingOffIT` runs in the default
  context — the one the whole suite shares — and proves there is no span processor, no
  exporter, and that a plugin is handed the empty trace parent its contract promises.
- **`management.opentelemetry.enabled` and not `management.tracing.enabled`**, which Spring
  Boot 4 removed (deprecation level `error`). The first attempt used the removed one and the
  test caught it, which is the second time a property rename has been found by asserting on
  behaviour rather than on configuration.
- **Boot disabled is not Boot absent.** It installs a `disabledOpenTelemetrySdk` that still
  mints valid identifiers, because disabling OpenTelemetry deliberately leaves the propagators
  in place. So `CurrentTrace` reads the flag rather than inferring it from the presence of a
  tracer: handing a plugin a well-formed `traceparent` for a span nothing recorded would be a
  contract violation of the quiet kind, and an afternoon of somebody's life.
- **The AMQP legs are on in both roles** (`spring.rabbitmq.template.observation-enabled`,
  `spring.rabbitmq.listener.simple.observation-enabled`), which is what makes the trace cross
  the broker. It also produces two new timers on every deployment, traced or not.
- **An operator who wants traces has to run a collector.** That is a real cost and it is the
  honest one: the alternative was this project choosing how long their telemetry is kept.
