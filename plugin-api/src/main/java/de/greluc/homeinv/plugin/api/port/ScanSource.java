/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api.port;

import de.greluc.homeinv.plugin.api.CallContext;
import java.time.Instant;

/**
 * Where a scan comes from (09 §9.2).
 *
 * <p>The browser camera is in the core image; app cameras, HID handheld scanners, Bluetooth ring
 * scanners and clipboard readers are what this port is for. A source pushes rather than answers:
 * nobody polls a scanner, and a scan that arrived two seconds ago is not worth returning to a
 * caller who asked a second ago.
 *
 * <p>Stage 2, written at stage 1 with the rest of the contract (REQ-PLG-001).
 */
public interface ScanSource {

  /**
   * What this source is.
   *
   * @return its description, which the core shows in the list of places a scan can come from
   */
  Descriptor describe();

  /**
   * Opens the source and delivers scans until the subscription is closed.
   *
   * <p>The listener is called from the transport's own thread and must not block: a source that
   * takes a second to hand over a scan delays every scan behind it. The core's implementation puts
   * the scan on a queue and returns.
   *
   * <p>A source that fails after opening reports it through {@link Listener#closed} rather than by
   * throwing, because by then the caller is no longer inside this method.
   *
   * @param context who the scans are for
   * @param listener what receives them
   * @return the handle that stops the delivery
   * @throws de.greluc.homeinv.plugin.api.PluginException when the source cannot be opened at all
   */
  Subscription open(CallContext context, Listener listener);

  /** What a caller does with the scans as they arrive. */
  interface Listener {

    /**
     * One scan.
     *
     * @param scan what was read
     */
    void scanned(Scan scan);

    /**
     * The source stopped delivering.
     *
     * <p>Called exactly once, whether the end was asked for or not. A caller tells the two apart by
     * whether it closed the subscription itself.
     *
     * @param reason why, in English and fit for a log line. Empty when the close was requested
     */
    void closed(String reason);
  }

  /**
   * The handle that stops a delivery.
   *
   * <p>Closing one twice is allowed and does nothing the second time.
   */
  interface Subscription extends AutoCloseable {

    /**
     * Stops the delivery.
     *
     * <p>Declared without a checked exception, unlike {@link AutoCloseable#close()}: there is
     * nothing a caller could do about a failure here, and a try-with-resources that has to catch
     * one is a try-with-resources nobody writes.
     */
    @Override
    void close();
  }

  /**
   * What a source is.
   *
   * @param sourceKey the stable key, for example {@code hid-keyboard-wedge}
   * @param name what a person sees
   * @param continuous whether it delivers until stopped ({@code true}: a connected scanner) or a
   *     bounded number of scans and then closes ({@code false}: an uploaded image). The core shows
   *     a stop button only for the first kind
   */
  record Descriptor(String sourceKey, String name, boolean continuous) {}

  /**
   * One scan.
   *
   * @param raw exactly what was read, undecoded. Which format it is in is a {@link CodeFormat}'s
   *     question and not this port's
   * @param scannedAt when it was read, as the source knows it. The core does not order by this — a
   *     device clock is a device's — but records it beside its own timestamp
   * @param deviceId which physical device, when the source can tell several apart. Empty otherwise
   */
  record Scan(String raw, Instant scannedAt, String deviceId) {}
}
