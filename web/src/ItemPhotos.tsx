/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { ApiError, api, type Media } from "./api";
import { UploadFailed, upload as resumableUpload } from "./upload";

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
/**
 * How long to wait for a verdict before letting the placeholder stand.
 *
 * Sixty half-second polls. ClamAV is the slow part — a cold container loads a
 * gigabyte of signatures before it answers anything — and after thirty seconds it
 * is friendlier to give the user their page back than to keep a spinner running;
 * the photograph appears the next time they open the item.
 */
const SCAN_POLLS = 60;
const SCAN_POLL_MS = 500;

/**
 * Waits until the malware scan has reached a verdict, or stops waiting.
 *
 * Resolves when the file is retrievable, and also when the polls run out — in
 * that case nothing is wrong, the answer has just not arrived yet. Rejects with
 * the `ApiError` when the scanner refused the file (`422`), which is the one
 * outcome the user has to be told about: an infected upload is detached from the
 * item and would otherwise simply not appear.
 *
 * Written as a chain rather than a loop with `await` in it, which is what a poll
 * is and what the linter rightly objects to seeing in a loop.
 *
 * @param mediaObjectId the file the upload accepted
 * @param attemptsLeft how many polls remain
 */
function settled(mediaObjectId: string, attemptsLeft: number = SCAN_POLLS): Promise<void> {
  return api.mediaObject(mediaObjectId).then(
    () => undefined,
    (cause: unknown) => {
      if (!(cause instanceof ApiError) || cause.status !== 503) {
        throw cause;
      }
      if (attemptsLeft <= 0) {
        return undefined;
      }
      return new Promise<void>((resume) => setTimeout(resume, SCAN_POLL_MS)).then(() =>
        settled(mediaObjectId, attemptsLeft - 1),
      );
    },
  );
}

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
      .then((page) => setPhotos(page.data))
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
      // Resumable (REQ-MED-008): the file goes in pieces, and a chunk that
      // fails is retried from where the server says the upload actually is
      // rather than from the beginning. On the connection a phone has while
      // standing in front of a shelf, that is the difference between a
      // photograph arriving and a photograph never arriving.
      const location = await resumableUpload(file, { targetKind: "ITEM", targetId: itemId });
      const accepted = { id: location.slice(location.lastIndexOf("/") + 1) };
      // The upload is answered before the malware scan has run, so this waits for
      // the verdict rather than showing a permanent placeholder: the scan happens
      // in the worker and the file is not retrievable until it has cleared it.
      // A refusal is told to the user here, because an infected file is detached
      // from the item and would otherwise simply not appear.
      await settled(accepted.id);
      await reload();
    } catch (cause) {
      if (cause instanceof ApiError) {
        onError(cause.detail);
      } else if (cause instanceof UploadFailed) {
        onError(t("media.uploadFailed"));
      } else {
        onError(t("media.uploadFailed"));
      }
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
