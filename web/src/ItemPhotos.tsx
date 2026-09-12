/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { ApiError, api, type Media } from "./api";

/**
 * The photographs of one item.
 *
 * `capture="environment"` on the file input: on a phone this opens the camera
 * directly, which is the whole point of photographing an item where it is stored.
 * On a desktop the attribute is ignored and the file picker appears, so one
 * control serves both.
 *
 * A picture whose scan has not finished has no URLs at all — the server offers
 * none until the malware scan says clean (REQ-MED-013) — so it is shown as a
 * placeholder rather than as a broken image.
 */
export function ItemPhotos({
  itemId,
  itemName,
  onError,
}: {
  itemId: string;
  itemName: string;
  onError: (message: string) => void;
}): React.JSX.Element {
  const { t } = useTranslation();
  const [photos, setPhotos] = useState<Media[]>([]);
  const [busy, setBusy] = useState(false);

  const reload = useCallback((): Promise<void> => {
    return api
      .media("ITEM", itemId)
      .then((page) => setPhotos(page.items))
      .catch((cause: unknown) => {
        onError(cause instanceof ApiError ? cause.detail : t("error.network"));
      });
  }, [itemId, onError, t]);

  useEffect(() => {
    // The request is the synchronisation with the outside world; the state is
    // set when it answers, which is a render later and not a cascading one.
    void reload();
  }, [reload]);

  async function upload(file: File): Promise<void> {
    setBusy(true);
    try {
      await api.uploadMedia(file, "ITEM", itemId);
      await reload();
    } catch (cause) {
      onError(cause instanceof ApiError ? cause.detail : t("media.uploadFailed"));
    } finally {
      setBusy(false);
    }
  }

  async function remove(photo: Media): Promise<void> {
    try {
      await api.deleteMedia(photo.id, "ITEM", itemId);
      await reload();
    } catch (cause) {
      onError(cause instanceof ApiError ? cause.detail : t("media.removeFailed"));
    }
  }

  return (
    <div className="photos">
      <h3 className="visually-hidden">{t("media.heading")}</h3>
      {photos.length === 0 ? (
        <p className="muted">{t("media.none")}</p>
      ) : (
        <ul className="photo-strip">
          {photos.map((photo) => (
            <li key={photo.id} className={photo.primaryImage ? "primary" : undefined}>
              {/* Named, not only outlined: which photograph lists show is
                  carried by a border colour, and colour alone is not a
                  distinction everybody can see (WCAG 2.2 AA, REQ-NFR-072). */}
              {photo.primaryImage && <span className="visually-hidden">{t("media.primary")}</span>}
              {photo.urls.thumb !== undefined ? (
                <img
                  src={photo.urls.thumb}
                  alt={t("media.alt", { name: itemName })}
                  width={200}
                  height={200}
                  loading="lazy"
                />
              ) : (
                <span className="pending muted">{t("media.pending")}</span>
              )}
              <button type="button" className="ghost" onClick={() => void remove(photo)}>
                {t("media.remove")}
              </button>
            </li>
          ))}
        </ul>
      )}

      <label className="upload">
        <span>{busy ? t("media.uploading") : t("media.add")}</span>
        <input
          type="file"
          accept="image/jpeg,image/png,image/webp,image/avif,image/heic,application/pdf"
          capture="environment"
          disabled={busy}
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) {
              void upload(file);
            }
            // Cleared so that choosing the same file twice fires a change again.
            event.target.value = "";
          }}
        />
      </label>
    </div>
  );
}
