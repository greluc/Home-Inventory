/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.api;

import java.util.Map;
import java.util.UUID;

/**
 * What a client learns about a stored file.
 *
 * <p>Deliberately no bytes and no unsigned path. {@code urls} carries signed, short-lived links per
 * variant and nothing else (REQ-MED-010); a field with a raw path would be a field that bypasses the
 * signature, the expiry and the dedicated hostname at once.
 *
 * <p>When the scan has not finished, {@code urls} is empty rather than absent. A client showing a
 * placeholder needs to know the upload exists and is not yet retrievable, which is a different state
 * from "no such file" (REQ-MED-013).
 *
 * @param id the media object
 * @param mediaType the detected type, never the declared one
 * @param byteSize the size of the original
 * @param widthPx the width for an image, or {@code null}
 * @param heightPx the height for an image, or {@code null}
 * @param scanState where the scan stands; only {@code CLEAN} yields URLs
 * @param primaryImage whether this is the image lists show for the thing it hangs on
 *     (REQ-MED-002). Carried per attachment rather than implied by position, because the list is
 *     paged and the primary is not guaranteed to be on the page a client is looking at
 * @param role what the attachment is for — {@code PHOTO}, {@code RECEIPT}, {@code
 *     WARRANTY_PROOF} or {@code OTHER} (REQ-LIFE-016). On the <b>attachment</b> rather than on the
 *     file, because the same scan of a receipt may be the purchase proof of one item and an
 *     ordinary document on another, and the file is stored once by content address either way
 * @param urls variant name to signed URL, empty until the scan says clean
 */
public record MediaView(
    UUID id,
    String mediaType,
    long byteSize,
    Integer widthPx,
    Integer heightPx,
    String scanState,
    boolean primaryImage,
    String role,
    Map<String, String> urls) {}
