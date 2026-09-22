/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.application;

import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.LogSafe;
import de.greluc.homeinv.plugin.api.PluginManifest;
import de.greluc.homeinv.plugin.api.PluginManifestReader;
import de.greluc.homeinv.plugins.api.PluginRegistry;
import de.greluc.homeinv.plugins.domain.ManifestSignature;
import de.greluc.homeinv.plugins.infrastructure.PluginRegistryQueries;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What is installed, and what each tenant permits it (REQ-PLG-004…006).
 *
 * <p>The manifest is parsed here and nowhere else. The registry table keeps the bytes, because the
 * signature is over them and because what an administrator consented to is a document rather than a
 * set of columns; the columns that exist beside it are the ones a question is asked of often enough
 * that parsing per call would be silly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultPluginRegistry implements PluginRegistry {

  private final PluginRegistryQueries registry;

  @Override
  @Transactional(readOnly = true)
  public List<Registration> installed(int limit) {
    return registry.installed(limit).stream().map(this::named).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Registration registration(String pluginId) {
    return named(
        registry
            .registration(pluginId)
            .orElseThrow(() -> new NotFoundException("plugin", pluginId)));
  }

  @Override
  @Transactional
  public boolean setDisabled(String pluginId, boolean disabled, UUID actor) {
    boolean changed = registry.setDisabled(pluginId, disabled, actor);
    if (changed) {
      // At WARN and with the actor, because this is an immediate measure and the
      // question afterwards is always "who, and when" (REQ-SEC-068, REQ-SEC-082).
      //
      // Through `LogSafe` because the id came from a caller. It is shape-checked
      // at the boundary and matched against a stored row before this line is
      // reached, so it cannot in fact carry a newline -- and a log call that is
      // safe because of two things somewhere else is one that stops being safe
      // when either of them moves.
      log.warn(
          "Operator {} {} plugin {}",
          actor,
          disabled ? "disabled" : "re-enabled",
          LogSafe.value(pluginId));
    }
    return changed;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Grant> grants(String pluginId) {
    return registry.grants(pluginId);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean permits(String pluginId, String capability) {
    Optional<Registration> installed = registry.registration(pluginId);
    if (installed.isEmpty() || installed.get().disabled()) {
      return false;
    }
    // In the CURRENT manifest, not merely granted once. A grant that outlived
    // the capability it was for is a leftover and not a permission: a plugin
    // that dropped `network:outbound` from its manifest must not keep reaching
    // the network because somebody agreed to an older version (09 §9.3 —
    // capabilities are exhaustive).
    if (!installed.get().capabilities().contains(capability)) {
      return false;
    }
    return registry.granted(pluginId, capability);
  }

  @Override
  @Transactional
  public Grant grant(String pluginId, String capability, UUID actor) {
    Registration installed = registration(pluginId);
    if (!installed.capabilities().contains(capability)) {
      throw new IllegalArgumentException(
          "The plugin "
              + pluginId
              + " does not ask for "
              + capability
              + ". Consent to something it never asked for is consent to nothing, and would be"
              + " waiting as a permission if it asked later.");
    }
    registry.grant(pluginId, capability, installed.manifestDigest(), actor);
    log.info(
        "Capability {} granted to plugin {}", LogSafe.value(capability), LogSafe.value(pluginId));
    return grants(pluginId).stream()
        .filter(grant -> grant.capability().equals(capability))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("The grant was written and is not there"));
  }

  @Override
  @Transactional
  public void revoke(String pluginId, String capability, UUID actor) {
    // The plugin has to exist. Withdrawing a capability nobody granted is
    // harmless and answers as though it worked, because the outcome the caller
    // wants is already true -- but a plugin nobody installed is a resource that
    // is not there, and saying 204 to that would be acting on something that
    // does not exist (REQ-SEC-025).
    registration(pluginId);
    int removed = registry.revoke(pluginId, capability);
    if (removed > 0) {
      log.info(
          "Capability {} withdrawn from plugin {} by {}",
          LogSafe.value(capability),
          LogSafe.value(pluginId),
          actor);
    }
  }

  @Override
  @Transactional(readOnly = true)
  public List<Grant> instanceGrants(String pluginId) {
    return registry.instanceGrants(pluginId);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean permitsForInstance(String pluginId, String capability) {
    Optional<Registration> installed = registry.registration(pluginId);
    if (installed.isEmpty() || installed.get().disabled()) {
      return false;
    }
    // The same "in the current manifest" rule the per-tenant path applies, and
    // for the same reason: a grant that outlived the capability it was for is a
    // leftover rather than a permission.
    if (!installed.get().capabilities().contains(capability)) {
      return false;
    }
    return registry.grantedForInstance(pluginId, capability);
  }

  @Override
  @Transactional
  public Grant grantForInstance(String pluginId, String capability, UUID actor) {
    Registration installed = registration(pluginId);
    if (!installed.capabilities().contains(capability)) {
      throw new IllegalArgumentException(
          "The plugin "
              + pluginId
              + " does not ask for "
              + capability
              + ". Consent to something it never asked for is consent to nothing, and would be"
              + " waiting as a permission if it asked later.");
    }
    registry.grantForInstance(pluginId, capability, installed.manifestDigest(), actor);
    log.info(
        "Capability {} granted to plugin {} for the instance",
        LogSafe.value(capability),
        LogSafe.value(pluginId));
    return instanceGrants(pluginId).stream()
        .filter(grant -> grant.capability().equals(capability))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("The grant was written and is not there"));
  }

  @Override
  @Transactional
  public void revokeForInstance(String pluginId, String capability, UUID actor) {
    // The plugin has to exist, for the reason revoke() gives.
    registration(pluginId);
    int removed = registry.revokeForInstance(pluginId, capability);
    if (removed > 0) {
      log.info(
          "Instance capability {} withdrawn from plugin {} by {}", capability, pluginId, actor);
    }
  }

  /**
   * Registers a plugin an operator installed, or brings its registration up to date.
   *
   * <p>The contract range is checked by the caller before this is reached: a plugin outside it is
   * never registered and the core starts regardless, because foreign code can never prevent startup
   * (REQ-PLG-008).
   *
   * <p>Grants are untouched. A manifest asking for <i>more</i> leaves what was granted granted and
   * the new capability ungranted — the plugin carries on with what it has, and nothing escalates
   * without somebody saying yes (REQ-PLG-006).
   *
   * <h2>What the signature decides</h2>
   *
   * <p>Four outcomes, and the two that look alike are kept apart (REQ-PLG-004, ADR-0085):
   *
   * <ul>
   *   <li><b>verified</b> — registered and callable, {@code signed} true;
   *   <li><b>unsigned, and this deployment permits unsigned plugins</b> — registered and callable
   *       with a standing reason, which is the permanent warning 09 §9.3 asks for;
   *   <li><b>unsigned, and it does not</b> — registered and <b>disabled</b>, because the operator
   *       can then see the plugin and the one setting that would run it;
   *   <li><b>a signature that does not verify</b> — registered and <b>disabled</b>, and no setting
   *       changes that. It means the document was altered after signing or the key is not the one
   *       that signed it, and neither is a thing an operator opts into.
   * </ul>
   *
   * <p>Disabled rather than absent, for the last two: a plugin that simply never appeared leaves an
   * operator looking for a container that is running and doing nothing, with the reason in a log
   * line that has scrolled past. The registration is what the surface can show.
   *
   * @param manifestBytes the manifest as it was read, which is what was signed
   * @param endpoint where the plugin listens, or {@code null} for an in-process one
   * @param fingerprint the certificate that may answer there, or {@code null}
   * @param signature what verifying the manifest against the operator's key found
   * @param unsignedPermitted whether this deployment was told to accept unsigned plugins
   * @return the registration
   * @throws de.greluc.homeinv.plugin.api.InvalidManifestException when the manifest cannot be read
   */
  @Transactional
  public Registration register(
      byte[] manifestBytes,
      String endpoint,
      String fingerprint,
      ManifestSignature.Result signature,
      boolean unsignedPermitted) {
    PluginManifest manifest = PluginManifestReader.read(manifestBytes);
    String document = new String(manifestBytes, StandardCharsets.UTF_8);

    boolean signed = signature.state() == ManifestSignature.State.VERIFIED;
    boolean disabled =
        switch (signature.state()) {
          case VERIFIED -> false;
          case UNSIGNED -> !unsignedPermitted;
          case INVALID -> true;
        };
    String stateReason =
        switch (signature.state()) {
          case VERIFIED -> null;
          case UNSIGNED ->
              unsignedPermitted
                  ? "It is unsigned and this deployment permits unsigned plugins: " + signature.reason()
                  : "It is unsigned and this deployment does not permit unsigned plugins: "
                      + signature.reason()
                      + ". Set HOMEINV_PLUGINS_ALLOW_UNSIGNED=true to run it anyway, or install"
                      + " the publisher's public key for it.";
          case INVALID ->
              "Its manifest signature did not verify: "
                  + signature.reason()
                  + ". No setting runs a plugin in this state.";
        };

    Registration registration =
        new Registration(
            manifest.metadata().id(),
            manifest.metadata().name(),
            manifest.metadata().version(),
            manifest.metadata().vendor(),
            manifest.spec().contract(),
            manifest.spec().runtime() == PluginManifest.Runtime.IN_PROCESS
                ? "in-process"
                : "out-of-process",
            manifest.spec().capabilities().stream()
                .map(PluginManifest.Capability::id)
                .sorted()
                .toList(),
            digestOf(manifestBytes),
            signed,
            disabled,
            stateReason,
            java.time.Instant.now());

    registry.register(registration, document, endpoint, fingerprint);
    // One line per plugin, and it says what was decided rather than that
    // something was. A registration that disabled a plugin is the line an
    // operator goes looking for.
    //
    // Through `LogSafe`, because part of that sentence can come from a
    // stranger: an unreadable signature reaches it as the JCA provider's own
    // message about bytes a publisher chose. That is the shape the log-injection
    // fix of 2026-09-21 was about, and a value is not safe merely because the
    // path it took is long.
    log.info(
        "Plugin {} {} registered, declaring {}{}",
        LogSafe.value(registration.pluginId()),
        registration.version(),
        registration.capabilities(),
        disabled ? LogSafe.value(" -- DISABLED: " + stateReason) : "");
    return registration(registration.pluginId());
  }

  /**
   * Fills in the name and the vendor by reading the stored manifest.
   *
   * <p>Not columns of their own: they are the manifest's to say, and a copy in a column is a second
   * place for them to disagree. Read on the way out because a registration is listed far less often
   * than a capability is checked.
   *
   * @param stored what the table holds
   * @return the same registration with its name and vendor
   */
  private Registration named(Registration stored) {
    return registry
        .manifest(stored.pluginId())
        .map(
            document -> {
              PluginManifest manifest =
                  PluginManifestReader.read(document.getBytes(StandardCharsets.UTF_8));
              return new Registration(
                  stored.pluginId(),
                  manifest.metadata().name(),
                  stored.version(),
                  manifest.metadata().vendor(),
                  stored.contract(),
                  stored.runtime(),
                  stored.capabilities(),
                  stored.manifestDigest(),
                  stored.signed(),
                  stored.disabled(),
                  stored.stateReason(),
                  stored.registeredAt());
            })
        .orElse(stored);
  }

  /**
   * The SHA-256 of a manifest, hex.
   *
   * @param manifest the bytes
   * @return the digest
   */
  private static String digestOf(byte[] manifest) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifest));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is part of every JRE", impossible);
    }
  }
}
