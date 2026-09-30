/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.api;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.UUID;

/**
 * Where an upload stands (REQ-MED-008).
 *
 * <p>The three numbers a resumable client needs and nothing else: how much arrived, how much was
 * promised, and how long the rest may take to follow. They become the {@code Upload-Offset},
 * {@code Upload-Length} and {@code Upload-Expires} headers of the tus protocol.
 *
 * @param id the upload, which is the last segment of its URL
 * @param offset how many bytes have arrived, as the store reports them
 * @param declaredLength how many were promised
 * @param expiresAt when an unfinished upload stops being one worth keeping
 * @param mediaObjectId the file this became, or {@code null} while it is still arriving
 */
public record UploadSessionView(
    UUID id,
    long offset,
    long declaredLength,
    Instant expiresAt,
    @Nullable UUID mediaObjectId) {

  /**
   * Whether the last byte has arrived and the pipeline has run.
   *
   * @return true once there is a file
   */
  public boolean isComplete() {
    return mediaObjectId != null;
  }
}
