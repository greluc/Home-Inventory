/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * An upload that survives a broken connection (`REQ-MED-008`).
 *
 * **Why this is hand-written.** `tus-js-client` is the obvious answer and it is a dependency, and a
 * dependency here is a line in nine licence notices (`REQ-CON-013`), an entry in the bundle's SBOM
 * and a package to keep current. What it would buy is parallel uploads, storage back-ends and a
 * retry policy this application does not want: the ceiling is 25 MB (`REQ-SEC-037`), the server is
 * ours, and the whole protocol used here is four requests.
 *
 * **What it does.** Creates the upload, sends the file in chunks, and — when a chunk fails — asks
 * the server where it actually got to and continues from there. That last sentence is the feature;
 * everything else is bookkeeping.
 *
 * **What it deliberately does not do.** It does not retry for ever and it does not remember an
 * upload across page loads. A person who navigates away has made a decision, and an upload that
 * resumed itself the next morning would be a surprise rather than a convenience.
 */

/** The protocol version the server speaks, sent on every request. */
const TUS_VERSION = "1.0.0";

/** How much goes in one request. */
const CHUNK_BYTES = 4 * 1024 * 1024;

/** How many times one chunk is retried before the upload gives up. */
const ATTEMPTS = 3;

/** What the caller must say about the file being uploaded. */
export interface UploadTarget {
  /** `ITEM` or `LOCATION`. */
  targetKind: "ITEM" | "LOCATION";
  /** What the finished file hangs on. */
  targetId: string;
  /** Whether it becomes the image lists show. */
  primary?: boolean;
  /** What the attachment is for. */
  role?: "PHOTO" | "RECEIPT" | "WARRANTY_PROOF" | "OTHER";
}

/** How far an upload has got, for a progress bar. */
export type UploadProgress = (sent: number, total: number) => void;

/** Thrown when an upload cannot be completed. */
export class UploadFailed extends Error {
  /**
   * @param status the status the server answered with, or 0 for a transport failure
   * @param message what to say
   */
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "UploadFailed";
  }
}

/**
 * The CSRF token, from the cookie Spring writes.
 *
 * Read per request rather than once: the token is rotated on login, and an upload begun before a
 * session change would otherwise send a token the server has stopped accepting.
 *
 * @returns the token, or an empty string when there is none
 */
function csrfToken(): string {
  const cookie = document.cookie.split("; ").find((entry) => entry.startsWith("XSRF-TOKEN="));
  return cookie === undefined ? "" : decodeURIComponent(cookie.slice("XSRF-TOKEN=".length));
}

/**
 * tus's `Upload-Metadata`: comma-separated `key base64value` pairs.
 *
 * @param target what the file will be attached to
 * @returns the header value
 */
function metadata(target: UploadTarget): string {
  const pairs: Record<string, string> = {
    targetKind: target.targetKind,
    targetId: target.targetId,
    primary: String(target.primary ?? false),
    role: target.role ?? "PHOTO",
  };
  return Object.entries(pairs)
    .map(([key, value]) => `${key} ${btoa(value)}`)
    .join(",");
}

/**
 * One request, with the headers every tus request carries.
 *
 * @param url where
 * @param method which verb
 * @param headers what else to send
 * @param body the bytes, for a `PATCH`
 * @returns the response
 */
async function send(
  url: string,
  method: string,
  headers: Record<string, string>,
  body?: BodyInit,
): Promise<Response> {
  return fetch(url, {
    method,
    // `same-origin` and never `include`: the session cookie is `SameSite=Strict`
    // and `__Host-`-prefixed, and `include` would be an invitation to send it
    // somewhere it was never meant to go — the same rule `api.ts` follows.
    credentials: "same-origin",
    headers: { "Tus-Resumable": TUS_VERSION, "X-XSRF-TOKEN": csrfToken(), ...headers },
    // `null` and not `undefined`: `exactOptionalPropertyTypes` is on, and
    // `RequestInit.body` admits the first and not the second.
    body: body ?? null,
  });
}

/**
 * Asks the server how much of an upload arrived.
 *
 * <p>The one request this whole feature exists for: after a failure neither side knows how much got
 * through, and the server is the only one that can say.
 *
 * @param url the upload's URL
 * @returns the offset
 * @throws UploadFailed when the upload is gone — expired, or never there
 */
async function offsetOf(url: string): Promise<number> {
  const answer = await send(url, "HEAD", {});
  if (!answer.ok) {
    throw new UploadFailed(answer.status, "The upload is no longer available");
  }
  return Number(answer.headers.get("Upload-Offset") ?? 0);
}

/**
 * Sends one chunk and then the next, continuing wherever the server says the upload is.
 *
 * Written as a chain rather than a loop with `await` in it — the idiom `ItemPhotos.tsx` uses for
 * its scan poll, and for the same reason: a sequential loop of awaits is what the linter rightly
 * objects to, and what this is. The depth is bounded by the ceiling: 25 MB in 4 MB pieces is seven
 * calls.
 *
 * @param url the upload's URL
 * @param file what to upload
 * @param offset where to continue from
 * @param attempts how many times this chunk has already failed
 * @param onProgress called after each chunk
 * @returns the URL of the media object that was created
 * @throws UploadFailed when the server refuses the file, or the connection cannot be recovered
 */
async function sendFrom(
  url: string,
  file: Blob,
  offset: number,
  attempts: number,
  onProgress?: UploadProgress,
): Promise<string> {
  if (offset >= file.size) {
    // Every byte was sent and no response carried a `Location`, which means the
    // response that completed the upload was the one that went missing. The
    // server remembers what it became, so one more question answers it.
    const finished = await send(url, "HEAD", {});
    const named = finished.headers.get("Location");
    if (named !== null) {
      return named;
    }
    throw new UploadFailed(finished.status, "The upload finished without producing a file");
  }

  const end = Math.min(offset + CHUNK_BYTES, file.size);
  // `null` for a connection that went away mid-chunk. Not a `Response` with
  // status 0, which was the first spelling and is not constructible: the
  // constructor admits 200 to 599 and throws on anything else, so the sentinel
  // for a failed request would itself have failed — in a browser as well as in
  // the test that found it.
  let answer: Response | null = null;
  try {
    answer = await send(
      url,
      "PATCH",
      { "Content-Type": "application/offset+octet-stream", "Upload-Offset": String(offset) },
      file.slice(offset, end),
    );
  } catch {
    // Whether any of the chunk arrived is exactly what nobody here knows, so
    // the next thing to do is ask.
    answer = null;
  }

  if (answer !== null && answer.status === 204) {
    const next = Number(answer.headers.get("Upload-Offset") ?? end);
    onProgress?.(next, file.size);
    const created = answer.headers.get("Location");
    return created ?? sendFrom(url, file, next, 0, onProgress);
  }

  // 409 is the server saying "you are not where you think you are" and it
  // carries the answer; 423 is another request writing to this upload; `null` is
  // a connection that vanished and carries nothing. All three are recovered the
  // same way, which is why none of them is a failure here.
  if (answer === null || answer.status === 409 || answer.status === 423) {
    const status = answer?.status ?? 0;
    if (attempts + 1 >= ATTEMPTS) {
      throw new UploadFailed(status, "The upload could not be continued");
    }
    const actual = await offsetOf(url);
    onProgress?.(actual, file.size);
    return sendFrom(url, file, actual, attempts + 1, onProgress);
  }

  throw new UploadFailed(answer.status, "The upload was refused");
}

/**
 * Uploads a file, continuing after a broken connection rather than starting again.
 *
 * @param file what to upload
 * @param target what to attach it to
 * @param onProgress called after each chunk, for a progress bar
 * @returns the URL of the media object that was created
 * @throws UploadFailed when the server refuses the file, or the connection cannot be recovered
 */
export async function upload(
  file: Blob,
  target: UploadTarget,
  onProgress?: UploadProgress,
): Promise<string> {
  const created = await send("/api/v1/media/uploads", "POST", {
    "Upload-Length": String(file.size),
    "Upload-Metadata": metadata(target),
  });
  if (created.status !== 201) {
    throw new UploadFailed(created.status, "The upload was refused before it began");
  }
  const url = created.headers.get("Location");
  if (url === null) {
    throw new UploadFailed(created.status, "The server did not say where to send the file");
  }
  return sendFrom(url, file, 0, 0, onProgress);
}
