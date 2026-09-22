/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.eventstream.application;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Says something every thirty seconds, so that saying nothing does not close the stream.
 *
 * <p>A home inventory is quiet for hours at a time, which makes the quiet case the normal one. A
 * proxy with an idle timeout closes a connection that has sent nothing, and the browser reconnects
 * — so without this the feature works in a demonstration, where somebody is changing things, and
 * stops working in a house, where nobody is.
 *
 * <p>It sends the same shape as a change, with the kind {@code heartbeat}: a client that ignores
 * unknown kinds ignores it, and one that refreshes on everything refreshes twice a minute, which
 * is harmless.
 */
@Component
@RequiredArgsConstructor
public class Heartbeat {

  private final LiveChanges live;

  /** Nudges every open stream on this replica. */
  @Scheduled(fixedDelayString = "${homeinv.events.heartbeat-seconds:30}000")
  public void beat() {
    live.heartbeat();
  }
}
