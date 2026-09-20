/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.application;

import de.greluc.homeinv.plugin.api.CallContext;
import de.greluc.homeinv.plugin.api.PluginException;
import de.greluc.homeinv.plugins.api.PluginCircuitOpened;
import de.greluc.homeinv.plugins.api.PluginSettings;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The envelope every plugin call goes through (REQ-PLG-007, 09 §9.5).
 *
 * <h2>Why a proxy and not an interceptor</h2>
 *
 * <p>Two of the four protections are gRPC's own and are set on the stub: the deadline and the
 * message size limit. The other two are not about bytes but about <i>calls</i> — how many of this
 * plugin's may be in flight, and whether to make another one at all — and a gRPC interceptor sees
 * an asynchronous call lifecycle where those questions are awkward to ask. Wrapping the port
 * interface asks them once, in one place, for every port and for an in-process plugin too, which an
 * interceptor could not cover at all.
 *
 * <p>The wrapping is not optional: {@code ExtensionRegistry} hands out nothing else, so a caller
 * cannot forget it.
 *
 * <h2>What counts as a failure</h2>
 *
 * <p>Only what says the plugin is broken. A resolver that answers "I do not know this code" and a
 * channel that answers "that is not an address" have both worked perfectly, and a breaker that
 * counted them would open on a plugin doing its job (a metadata resolver declines most codes it is
 * offered). {@link PluginException.Kind#UNAVAILABLE}, {@link
 * PluginException.Kind#DEADLINE_EXCEEDED} and {@link PluginException.Kind#INTERNAL} count;
 * everything else is an answer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginResilience {

  private final PluginRuntimeProperties properties;
  private final MeterRegistry meters;
  private final ApplicationEventPublisher events;

  /**
   * What the tenant configured, filled into the envelope of every call (ADR-0073).
   *
   * <p>Here rather than in each adapter for the reason everything else in this class is here: a
   * cross-cutting concern applied in sixteen places is applied in fifteen of them.
   */
  private final PluginSettings settings;

  /** One breaker per plugin, created on first use and kept. */
  private CircuitBreakerRegistry breakers;

  /** One bounded pool per plugin. */
  private BulkheadRegistry pools;

  /**
   * Builds the two registries and binds them to the metrics endpoint.
   *
   * <p>In {@code @PostConstruct} rather than in the constructor because the configuration decides
   * their shape, and because binding metrics from a constructor publishes a half-built object.
   */
  @PostConstruct
  void configure() {
    breakers =
        CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom()
                .failureRateThreshold(properties.getFailureRatePercent())
                .slidingWindowSize(properties.getSlidingWindowSize())
                .minimumNumberOfCalls(properties.getSlidingWindowSize())
                .waitDurationInOpenState(Duration.ofSeconds(properties.getOpenStateSeconds()))
                .permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(PluginResilience::countsAsBroken)
                .build());

    pools =
        BulkheadRegistry.of(
            BulkheadConfig.custom()
                .maxConcurrentCalls(properties.getConcurrentCalls())
                .maxWaitDuration(Duration.ofMillis(properties.getBulkheadWaitMillis()))
                .build());

    // 09 §9.5 asks for duration, result and errors per call as metrics. These
    // two bind the state and the counts an operator reads in Grafana; without
    // them a breaker's state would be visible only in a log line, which is not
    // where anybody looks for it (13 §13.7).
    TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(meters);
    TaggedBulkheadMetrics.ofBulkheadRegistry(pools).bindTo(meters);
  }

  /**
   * Wraps one port implementation in the envelope.
   *
   * @param port the port interface, which is what the proxy implements
   * @param target what actually does the work — a gRPC adapter, or a loaded class for an in-process
   *     plugin
   * @param pluginId whose breaker and pool to use. Per plugin and not per port: a plugin is one
   *     process, and a plugin that is down is down for every port it serves
   * @param <T> the port
   * @return an implementation of {@code port} that applies the pool and the breaker to every call
   */
  public <T> T decorate(Class<T> port, T target, String pluginId) {
    CircuitBreaker breaker = breakerFor(pluginId, port.getSimpleName());
    Bulkhead pool = pools.bulkhead(pluginId);
    return port.cast(
        Proxy.newProxyInstance(
            port.getClassLoader(),
            new Class<?>[] {port},
            new Envelope(target, breaker, pool, pluginId, port.getSimpleName(), settings)));
  }

  /**
   * The breaker for a plugin, with the event that says it opened.
   *
   * @param pluginId the plugin
   * @param port which port the call that opened it was going through, for the event
   * @return the breaker
   */
  private CircuitBreaker breakerFor(String pluginId, String port) {
    boolean isNew = breakers.find(pluginId).isEmpty();
    CircuitBreaker breaker = breakers.circuitBreaker(pluginId);
    if (isNew) {
      // On the transition and not on every failed call: one event per outage is
      // what an operator can act on, and a failing plugin must not fill the
      // event log with the evidence of its own failure.
      breaker
          .getEventPublisher()
          .onStateTransition(
              transition -> {
                if (transition.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                  int rate = Math.round(breaker.getMetrics().getFailureRate());
                  log.warn(
                      "The circuit to plugin {} opened at a failure rate of {}%; calls now fail"
                          + " immediately for {}s",
                      pluginId, rate, properties.getOpenStateSeconds());
                  events.publishEvent(
                      new PluginCircuitOpened(pluginId, port, rate, Instant.now()));
                }
              });
    }
    return breaker;
  }

  /**
   * Whether a failure says the plugin is broken, rather than that it answered.
   *
   * @param failure what came out of the call
   * @return {@code true} when the breaker should count it
   */
  private static boolean countsAsBroken(Throwable failure) {
    if (failure instanceof PluginException plugin) {
      return switch (plugin.kind()) {
        case UNAVAILABLE, DEADLINE_EXCEEDED, INTERNAL -> true;
        case UNSUPPORTED, NOT_FOUND, INVALID_ARGUMENT, DENIED -> false;
      };
    }
    // Anything the adapter did not classify. Counted, because an unclassified
    // failure is one nobody has established the meaning of, and assuming it is
    // harmless is how a breaker never opens.
    return true;
  }

  /**
   * Runs one call inside the pool and the breaker.
   *
   * @param target what does the work
   * @param breaker the plugin's breaker
   * @param pool the plugin's bounded pool
   * @param pluginId for the messages
   * @param port for the messages
   */
  private record Envelope(
      Object target,
      CircuitBreaker breaker,
      Bulkhead pool,
      String pluginId,
      String port,
      PluginSettings settings)
      implements InvocationHandler {

    // Deliberately narrower than InvocationHandler's own `throws Throwable`.
    // Everything a port can raise is unchecked, so a caller that had to catch
    // Throwable would be catching the reflection machinery rather than the
    // plugin. What comes out of here is a PluginException or nothing.
    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) {
      // equals, hashCode and toString are the proxy's own business. Sending them
      // through a circuit breaker would make a log line that prints a port open
      // one.
      if (method.getDeclaringClass() == Object.class) {
        return call(method, arguments);
      }

      // Before the pool and before the breaker, deliberately: reading what the
      // tenant configured is a query against our own database, and it must
      // neither occupy one of this plugin's concurrent slots nor count towards
      // opening its circuit if it fails. Only the call to the plugin does.
      // A no-argument port method arrives with null arguments, which is the one
      // case with nothing to fill; it is answered here so the helper can take
      // and return an array rather than something nullable.
      Object[] withSettings = arguments == null ? null : carryingSettings(arguments);

      try {
        return Bulkhead.decorateCheckedSupplier(
                pool,
                CircuitBreaker.decorateCheckedSupplier(breaker, () -> call(method, withSettings)))
            .get();
      } catch (CallNotPermittedException open) {
        throw new PluginException(
            PluginException.Kind.UNAVAILABLE,
            "The circuit to plugin "
                + pluginId
                + " is open: it failed often enough that the core stopped calling it. The call was"
                + " not made.",
            open);
      } catch (BulkheadFullException full) {
        throw new PluginException(
            PluginException.Kind.UNAVAILABLE,
            "Plugin "
                + pluginId
                + " already has as many calls in flight as it is allowed. The call was not made, so"
                + " that a slow plugin cannot take the core with it (REQ-PLG-007).",
            full);
      } catch (PluginException plugin) {
        throw plugin;
      } catch (Throwable unexpected) {
        // `decorateCheckedSupplier` declares Throwable, so this is where the
        // declaration stops. Anything arriving here is unclassified, which
        // `countsAsBroken` already treats as the plugin's fault.
        throw new PluginException(
            PluginException.Kind.INTERNAL, "The call to plugin " + pluginId + " failed", unexpected);
      }
    }

    /**
     * Calls the target and lets a {@link PluginException} through unchanged.
     *
     * <p>Reflection wraps everything it catches. Unwrapping it here is what lets the breaker see a
     * {@link PluginException} and judge it by its kind, rather than counting every answer a plugin
     * gives as a failure.
     *
     * @param method which method
     * @param arguments its arguments
     * @return whatever it returned
     */
    /**
     * The same arguments, with what this tenant configured filled into the envelope.
     *
     * <p>Every port method takes a {@link de.greluc.homeinv.plugin.api.CallContext} and none takes
     * two, so this finds it by type rather than by position: a port added later gets the settings
     * without anybody remembering that it should.
     *
     * <p>An <b>instance</b> call carries none. There is no tenant whose settings they would be, and
     * filling them from somewhere would be exactly the confusion ADR-0066 exists to prevent.
     *
     * @param arguments what the caller passed, never {@code null}
     * @return the arguments to send, the same array when there was nothing to fill
     */
    private Object[] carryingSettings(Object[] arguments) {
      Object[] filled = null;
      for (int index = 0; index < arguments.length; index++) {
        if (!(arguments[index] instanceof CallContext context) || context.tenantId() == null) {
          continue;
        }
        Map<String, String> configured = settings.effective(pluginId, context.tenantId());
        if (configured.isEmpty()) {
          continue;
        }
        if (filled == null) {
          filled = arguments.clone();
        }
        filled[index] = context.withSettings(configured);
      }
      return filled == null ? arguments : filled;
    }

    private Object call(Method method, Object[] arguments) {
      try {
        return method.invoke(target, arguments);
      } catch (InvocationTargetException wrapped) {
        Throwable cause = wrapped.getCause();
        if (cause instanceof PluginException plugin) {
          throw plugin;
        }
        if (cause instanceof Error fatal) {
          // Not this class's to classify. An adapter that ran out of memory has
          // not told us anything about the plugin.
          throw fatal;
        }
        // Everything else becomes a PluginException, so that what comes out of
        // the envelope is one thing a caller can branch on. Unclassified, which
        // `countsAsBroken` already treats as the plugin's fault.
        throw new PluginException(
            PluginException.Kind.INTERNAL,
            "Plugin " + pluginId + " failed in a way its adapter did not classify",
            cause == null ? wrapped : cause);
      } catch (IllegalAccessException impossible) {
        // The port is a public interface and the proxy implements it. If this
        // happens, the adapter is not what this class was handed.
        throw new IllegalStateException(
            "A port implementation for " + pluginId + " could not be called", impossible);
      }
    }
  }
}
