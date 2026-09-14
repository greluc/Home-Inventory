/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api;

import java.util.List;
import java.util.Map;

/**
 * What a plugin declares about itself (REQ-PLG-004, 09 §9.3).
 *
 * <p>The basis for registration, for granting permissions and for checking compatibility. It is
 * signed, and the signature is over this document as it was written — so it is parsed from the
 * bytes that were signed and never re-serialised on the way.
 *
 * <h2>Capabilities are exhaustive</h2>
 *
 * <p>What is not in the manifest is not possible, <b>not even with consent granted</b>. A plugin
 * that wants one more thing needs a new manifest and new consent, and a manifest change that adds a
 * capability resets the consent it had (REQ-PLG-006). That is the whole mechanism against silent
 * privilege escalation, and it works only because this list is closed.
 *
 * <h2>Apache-2.0, and therefore self-contained</h2>
 *
 * <p>This type is read by the core when it registers a plugin and written by the SDK when it
 * scaffolds one, so it lives in the module both sides may depend on. It refers to nothing in the
 * core (ADR-0018).
 *
 * @param apiVersion the manifest format, {@code home-inv.plugin/v1}. Its own version, separate from
 *     the contract range below: the shape of this document and the shape of the gRPC service change
 *     for different reasons
 * @param metadata who the plugin is
 * @param spec what it does and what it needs
 */
public record PluginManifest(String apiVersion, Metadata metadata, Spec spec) {

  /** The one manifest format there is. A manifest naming another is refused rather than guessed. */
  public static final String API_VERSION = "home-inv.plugin/v1";

  /**
   * Who the plugin is.
   *
   * @param id a reverse-domain identifier, globally unique and stable for the plugin's life. What a
   *     grant is recorded against, so a plugin that changed it would arrive as a stranger
   * @param name what a person sees in a list
   * @param version the plugin's own SemVer, which is not the contract's
   * @param vendor who publishes it
   * @param license its SPDX identifier. A plugin may be licensed however its author likes
   *     (ADR-0018); it says which here so an operator can see it before installing
   * @param homepage where to read about it, or {@code null}
   * @param descriptions one line per language tag, as the author wrote them. Data, not code: a
   *     manifest is a plugin's own multilingual text and is not translated by this project
   */
  public record Metadata(
      String id,
      String name,
      String version,
      String vendor,
      String license,
      String homepage,
      Map<String, String> descriptions) {}

  /**
   * What the plugin does and what it needs to do it.
   *
   * @param contract the range of contract versions it supports, as a SemVer range. A range and not
   *     a point: the core refuses a plugin outside its own range at startup <b>without failing
   *     itself</b> (REQ-PLG-008)
   * @param runtime {@code out-of-process} or {@code in-process}. The first is the default and the
   *     only one a tenant administrator can ever end up with; the second is an operator's
   *     deliberate, signed exception (REQ-PLG-003, ADR-0006)
   * @param implementsPorts which ports it provides, with what it handles and at what priority
   * @param capabilities everything it may do. Exhaustive
   * @param settings what an operator or administrator configures, surfaced in the UI
   * @param health where to ask whether it is alive, or {@code null} for the default
   * @param resources guidance for the operator's container limits, or {@code null}
   */
  public record Spec(
      String contract,
      Runtime runtime,
      List<PortBinding> implementsPorts,
      List<Capability> capabilities,
      List<Setting> settings,
      Health health,
      Resources resources) {}

  /** Where a plugin's code runs. */
  public enum Runtime {
    /**
     * Its own process, reached over gRPC with mTLS. The default and the normal case (REQ-PLG-002).
     */
    OUT_OF_PROCESS,
    /**
     * Inside the core's JVM. Off by default and permitted only for a signed artifact an operator
     * explicitly enabled (REQ-PLG-003).
     *
     * <p>Java 25 has no {@code SecurityManager}, so a classloader separates namespaces and not
     * privileges: in-process code has the core's authority whatever the manifest says (ADR-0006).
     * The capability list still applies to the calls it makes through the core's own ports, which
     * is the only place a check can be made at all.
     */
    IN_PROCESS
  }

  /**
   * One port the plugin implements.
   *
   * @param port the port's name, one of the extension points of REQ-PLG-001
   * @param schemes what it handles within that port — ISBN10 and ISBN13 for a metadata resolver,
   *     DATAMATRIX for a code format. Empty when the port has no such division
   * @param priority which implementation is preferred where several answer; higher wins
   */
  public record PortBinding(String port, List<String> schemes, int priority) {}

  /**
   * One thing the plugin may do, if a tenant grants it.
   *
   * @param id the capability, from the closed set of 09 §9.4
   * @param reason why this plugin needs it, in the author's words. Shown to the administrator who
   *     decides, because "network:outbound" is not a decision anybody can take
   * @param hosts for {@code network:outbound}: the hostnames it may reach over HTTPS. Compiled into
   *     the egress proxy's allowlist, which is where it is enforced — an allowlist that untrusted
   *     code applies to itself is documentation, not a control (ADR-0027)
   * @param tcp for {@code network:outbound} to something that is not HTTP: {@code host:port} pairs
   *     the proxy forwards raw TCP to
   * @param events for {@code core:event:emit} and {@code core:event:subscribe}: the event types,
   *     because "any event" is not a capability anybody can consent to
   */
  public record Capability(
      String id, String reason, List<String> hosts, List<String> tcp, List<String> events) {}

  /**
   * One setting the plugin takes.
   *
   * @param key its name, which is also what the plugin reads it back under
   * @param type {@code string}, {@code secret}, {@code enum}, {@code integer} or {@code boolean}
   * @param required whether the plugin cannot work without it
   * @param values for {@code enum}: the permitted values
   * @param defaultValue what it is when nobody sets it, or {@code null}
   * @param label its name per language tag, as the author wrote it
   */
  public record Setting(
      String key,
      String type,
      boolean required,
      List<String> values,
      String defaultValue,
      Map<String, String> label) {}

  /**
   * Where to ask whether the plugin is alive.
   *
   * @param endpoint the path, {@code /healthz} by convention
   * @param intervalSeconds how often to ask
   */
  public record Health(String endpoint, int intervalSeconds) {}

  /**
   * What the plugin suggests it needs.
   *
   * <p>Guidance and not a promise: the operator sets the container's limits and this says what the
   * author expects them to need. A plugin that asks for more than it gets is a plugin that is
   * killed by the runtime, which is the operator's business and not the core's.
   *
   * @param memoryMiB how much memory
   * @param timeoutSeconds how long its calls take at worst, which an operator compares against the
   *     deadline the core applies anyway (REQ-PLG-007)
   */
  public record Resources(int memoryMiB, int timeoutSeconds) {}
}
