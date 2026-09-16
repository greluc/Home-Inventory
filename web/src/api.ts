/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * The typed client for `/api/v1`.
 *
 * Hand-written for stage 0. `REQ-API-002` generates clients from the OpenAPI
 * specification, and that is stage 1 — writing a generator now would mean
 * maintaining one for the six endpoints this stage has.
 *
 * Three things it does that a bare `fetch` would not:
 *
 * - It sends the CSRF token on every mutating request. Spring writes the token
 *   into a readable cookie and expects it back in a header, and a header is the
 *   point: a form posted from another origin can carry a body but not a custom
 *   header, so the token cannot be replayed by a cross-site form.
 * - It turns an RFC 9457 problem document into a typed error. Every error in this
 *   application has that shape (REQ-API-003), so unwrapping it once here means no
 *   component has to.
 * - It sends `credentials: "same-origin"` and never `include`. The session cookie
 *   is `SameSite=Strict` and `__Host-`-prefixed; `include` would be an invitation
 *   to send it somewhere it was never meant to go.
 */

/** An error carrying the problem document the server returned. */
export class ApiError extends Error {
  /**
   * @param status the HTTP status
   * @param type the stable problem type URI clients branch on, never the prose
   * @param detail the sentence meant for a person
   * @param traceId the id that appears in the server log for this request
   */
  constructor(
    readonly status: number,
    readonly type: string,
    readonly detail: string,
    readonly traceId: string | undefined,
  ) {
    super(detail);
    this.name = "ApiError";
  }
}

/** Who the current session belongs to. */
export interface Session {
  userId: string;
  tenantId: string;
  email: string;
  /** The language on the user's profile, which the interface starts in (REQ-NFR-033). */
  locale: string;
}

/** An item as the API returns it. */
export interface Item {
  id: string;
  name: string;
  description: string | null;
  kind: "PHYSICAL" | "DIGITAL";
  locationId: string | null;
  quantity: string;
  quantityUnit: string | null;
  lifecycleState: string;
  createdAt: string;
  updatedAt: string;
  version: number;
}

/** What an account holds besides its password (REQ-AUTH-002). */
export interface SecondFactorEnrolment {
  totpConfirmed: boolean;
  enrolledAt: string | null;
  recoveryCodesLeft: number;
  passkeys: Passkey[];
}

/** One registered passkey. */
export interface Passkey {
  id: string;
  label: string;
  registeredAt: string;
  lastUsedAt: string | null;
}

/** The options one side of a WebAuthn ceremony needs, as the specification's own JSON. */
export interface Ceremony {
  options: string;
}

/** A secret that has just been generated, readable this once. */
export interface TotpEnrolment {
  /** The shared secret in base32, for somebody typing it into an app by hand. */
  secret: string;
  /** The `otpauth://` URI an authenticator app reads from a QR code or a link. */
  provisioningUri: string;
}

/** The recovery codes, readable this once. */
export interface RecoveryCodes {
  codes: string[];
}

/**
 * The envelope every collection answers with (08 §8.2).
 *
 * `data` are the rows. `page` says where the next ones are; the cursor is opaque and signed, and
 * there is no offset anywhere in this API because offsets skip and duplicate rows on data that
 * changes under them. `meta.degraded` is how the server says a derived store was unavailable and
 * something less capable answered — a field and not a header, because `Warning: 199` was
 * obsoleted by RFC 9111 §5.5.
 */
export interface Page<T> {
  data: T[];
  page: {
    nextCursor: string | null;
    hasMore: boolean;
    /** Absent where counting is not cheap; explicitly an estimate where present. */
    estimatedTotal?: number | null;
  };
  meta: {
    degraded: boolean;
    /** A stable token from `docs/reference/degraded-reasons.yaml`; branch on this, never on prose. */
    degradedReason?: string | null;
    took?: number | null;
  };
}

/** A storage location with its readable path. */
export interface Location {
  id: string;
  name: string;
  categoryId: string;
  parentId: string | null;
  depth: number;
  ancestors: string[];
  /** The concurrency token: send it back as `If-Match` to change or delete this place. */
  version: number;
}

/**
 * A kind of place a location can be.
 *
 * The server sends the shipped key and no label: the names are interface text and
 * live in the resource bundles, because only this side knows what language the
 * reader wants them in (REQ-NFR-032).
 */
export interface LocationCategory {
  id: string;
  key: string;
  /**
   * The tenant's own name per language tag, empty when it has given the category none.
   *
   * A shipped category starts nameless and is translated from its `key`; a tenant may rename it or
   * define one of its own, and then this wins. See `categoryName` in `LocationPanel.tsx`.
   */
  labels: Record<string, string>;
  icon: string | null;
  mobile: boolean;
}

/** A file attached to an item or a location. */
export interface Media {
  id: string;
  mediaType: string;
  byteSize: number;
  widthPx: number | null;
  heightPx: number | null;
  scanState: string;
  primaryImage: boolean;
  /** Signed, short-lived URLs per variant; empty until the malware scan says clean. */
  urls: Record<string, string>;
}

/**
 * Reads a cookie by name.
 *
 * Only the CSRF cookie is ever read this way. The session cookie is `HttpOnly`
 * and deliberately unreadable — if this function could see it, so could an XSS.
 *
 * @param name the cookie name
 * @returns its value, or undefined
 */
function cookie(name: string): string | undefined {
  return document.cookie
    .split("; ")
    .find((entry) => entry.startsWith(`${name}=`))
    ?.slice(name.length + 1);
}

/**
 * Performs a request and unwraps the result.
 *
 * @param path the path below the origin
 * @param init the request options
 * @returns the parsed body
 * @throws ApiError when the server answers with a problem document
 */
async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  const method = (init.method ?? "GET").toUpperCase();

  if (method !== "GET" && method !== "HEAD") {
    const token = cookie("XSRF-TOKEN");
    if (token) {
      headers.set("X-XSRF-TOKEN", decodeURIComponent(token));
    }
  }
  if (init.body !== undefined && !(init.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(path, { ...init, headers, credentials: "same-origin" });

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  const body: unknown = text.length > 0 ? JSON.parse(text) : undefined;

  if (!response.ok) {
    const problem = (body ?? {}) as Record<string, unknown>;
    throw new ApiError(
      response.status,
      typeof problem.type === "string" ? problem.type : "about:blank",
      typeof problem.detail === "string" ? problem.detail : response.statusText,
      typeof problem.traceId === "string" ? problem.traceId : undefined,
    );
  }
  return body as T;
}

/** Everything the application asks of the server. */
/**
 * Which build this instance is running, and where its source is.
 *
 * The AGPL's source offer is about THIS instance, so the commit is the part that
 * matters: two builds of the same version can differ (REQ-CON-009).
 */
export interface Version {
  /** The application's own version. */
  readonly version: string;
  /** The exact commit, or `unknown` for a build made without one. */
  readonly commit: string;
  /** When it was built, ISO-8601, or null when the build said nothing. */
  readonly builtAt: string | null;
  /** Where the source is. */
  readonly source: string;
  /** What the source is licensed under. */
  readonly licence: string;
}

export const api = {
  /**
   * Which build this instance is running (REQ-CON-009).
   *
   * Public: the source offer is owed to whoever uses the instance, not only to
   * whoever has an account on it.
   *
   * @returns the build's identity
   */
  version: (): Promise<Version> => request<Version>("/api/v1/version"),

  /**
   * Logs in and establishes a session.
   *
   * @param email the address
   * @param password the password
   * @returns who the caller now is
   */
  login: (email: string, password: string): Promise<Session> =>
    request<Session>("/api/v1/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password }),
    }),

  /**
   * Answers the second factor and finishes a login (REQ-AUTH-002).
   *
   * Called when {@link api.login} failed with `second-factor-required`, which is
   * not an error the person can do anything about by typing the password again:
   * the password was right, and what is missing is the code.
   *
   * @param code the six digits from the authenticator app, or a recovery code
   * @returns who the caller now is
   */
  completeSecondFactor: (code: string): Promise<Session> =>
    request<Session>("/api/v1/auth/mfa", {
      method: "POST",
      body: JSON.stringify({ code }),
    }),

  /** What the account holds besides its password. */
  secondFactor: (): Promise<SecondFactorEnrolment> =>
    request<SecondFactorEnrolment>("/api/v1/auth/mfa/enrolment"),

  /**
   * Answers a second factor with a passkey instead of a code.
   *
   * @param credential what the browser produced
   * @returns who the caller now is
   */
  completeWithPasskey: (credential: string): Promise<Session> =>
    request<Session>("/api/v1/auth/mfa", {
      method: "POST",
      body: JSON.stringify({ credential }),
    }),

  /** The options for proving a passkey, whether in a login or in a re-confirmation. */
  passkeyChallenge: (): Promise<Ceremony> =>
    request<Ceremony>("/api/v1/auth/mfa/passkeys/challenge", { method: "POST" }),

  /** The options for registering a passkey. */
  beginPasskey: (): Promise<Ceremony> =>
    request<Ceremony>("/api/v1/auth/mfa/passkeys", { method: "POST" }),

  /**
   * Finishes registering a passkey.
   *
   * @param credential what the browser produced
   * @param label what to call this authenticator
   */
  confirmPasskey: (credential: string, label: string): Promise<void> =>
    request<void>("/api/v1/auth/mfa/passkeys/confirmation", {
      method: "POST",
      body: JSON.stringify({ credential, label }),
    }),

  /**
   * Begins an enrolment; the secret comes back once and is never readable again.
   *
   * @returns the secret and the provisioning URI
   */
  beginTotpEnrolment: (): Promise<TotpEnrolment> =>
    request<TotpEnrolment>("/api/v1/auth/mfa/totp", { method: "POST" }),

  /**
   * Confirms an enrolment with a code from the new secret, and takes the recovery codes.
   *
   * @param code the six digits the app shows
   * @returns the recovery codes, readable this once
   */
  confirmTotpEnrolment: (code: string): Promise<RecoveryCodes> =>
    request<RecoveryCodes>("/api/v1/auth/mfa/totp/confirmation", {
      method: "POST",
      body: JSON.stringify({ code }),
    }),

  /** Ends the session. */
  logout: (): Promise<void> => request<void>("/api/v1/auth/logout", { method: "POST" }),

  /**
   * Who the caller is, or a 401 when nobody.
   *
   * Called on start-up to decide between the application and the login form,
   * rather than remembering a flag in storage: the session lives on the server
   * and only the server knows whether it is still valid.
   */
  me: (): Promise<Session> => request<Session>("/api/v1/auth/me"),

  /**
   * Searches items.
   *
   * @param query the text; empty lists everything
   * @param language which generated vector to search — the interface language, so
   *   that a German user's search is stemmed with German rules (ADR-0047)
   * @param cursor the opaque cursor from a previous page
   */
  search: (query: string, language: string, cursor?: string): Promise<Page<Item>> => {
    const params = new URLSearchParams({ q: query, language, limit: "50" });
    if (cursor) {
      params.set("cursor", cursor);
    }
    return request<Page<Item>>(`/api/v1/items?${params.toString()}`);
  },

  /**
   * Creates an item.
   *
   * No type reference is sent: stage 0 has no type system to choose from and the
   * server fills in the tenant's built-in type (O27).
   */
  createItem: (item: {
    name: string;
    description?: string;
    kind: "PHYSICAL" | "DIGITAL";
    locationId?: string;
    quantity?: string;
    quantityUnit?: string;
  }): Promise<Item> =>
    request<Item>("/api/v1/items", { method: "POST", body: JSON.stringify(item) }),

  /** Reads one item. */
  item: (id: string): Promise<Item> => request<Item>(`/api/v1/items/${id}`),

  /**
   * Deletes an item.
   *
   * Takes the version it was read at, because every write on a single resource has to say which
   * state it acted on (REQ-API-004). Without it the server answers 428 and nothing is deleted;
   * with a stale one it answers 412, which is the lost update that did not happen.
   *
   * @param id the item
   * @param version the item's `version`, as the last read gave it
   */
  deleteItem: (id: string, version: number): Promise<void> =>
    request<void>(`/api/v1/items/${id}`, {
      method: "DELETE",
      headers: { "If-Match": `"${version}"` },
    }),

  /** Creates a location. */
  createLocation: (location: {
    name: string;
    categoryId: string;
    parentId?: string;
  }): Promise<Location> =>
    request<Location>("/api/v1/locations", { method: "POST", body: JSON.stringify(location) }),

  /** Reads one location with its readable path. */
  location: (id: string): Promise<Location> => request<Location>(`/api/v1/locations/${id}`),

  /**
   * The tenant's locations, as a page.
   *
   * The whole tree rather than one level: each row carries its parent and its
   * readable path, which is what the picker assembles a tree from.
   *
   * @param cursor the opaque cursor from a previous page
   */
  locations: (cursor?: string): Promise<Page<Location>> => {
    const params = new URLSearchParams({ limit: "200" });
    if (cursor) {
      params.set("cursor", cursor);
    }
    return request<Page<Location>>(`/api/v1/locations?${params.toString()}`);
  },

  /** The kinds of place a location can be. */
  locationCategories: (): Promise<Page<LocationCategory>> =>
    request<Page<LocationCategory>>("/api/v1/locations/categories?limit=200"),

  /**
   * The files attached to one thing.
   *
   * @param targetKind `ITEM` or `LOCATION`
   * @param targetId what they hang on
   */
  media: (targetKind: "ITEM" | "LOCATION", targetId: string): Promise<Page<Media>> => {
    const params = new URLSearchParams({ targetKind, targetId, limit: "200" });
    return request<Page<Media>>(`/api/v1/media?${params.toString()}`);
  },

  /**
   * Uploads a file and attaches it.
   *
   * Sent as `multipart/form-data`, which is what the endpoint reads — the
   * `Content-Type` is deliberately not set here, because the browser has to add
   * the boundary and will not if the header is already there.
   *
   * @param file the chosen file
   * @param targetKind `ITEM` or `LOCATION`
   * @param targetId what to attach it to
   */
  uploadMedia: (file: File, targetKind: "ITEM" | "LOCATION", targetId: string): Promise<Media> => {
    const body = new FormData();
    body.append("file", file);
    const params = new URLSearchParams({ targetKind, targetId });
    return request<Media>(`/api/v1/media?${params.toString()}`, { method: "POST", body });
  },

  /**
   * One file, by id — what an upload's `Location` header points at.
   *
   * The upload answers `202` before the malware scan has run, so this is how a
   * client finds out what became of it: `200` with signed URLs once the scan
   * cleared it, `422` when the scanner refused it, `503` while there is no
   * verdict yet. Both failures arrive as an `ApiError` carrying the status.
   *
   * @param mediaObjectId the file
   */
  mediaObject: (mediaObjectId: string): Promise<Media> => {
    return request<Media>(`/api/v1/media/${mediaObjectId}`);
  },

  /**
   * Detaches a file.
   *
   * @param mediaObjectId the file
   * @param targetKind what it hangs on
   * @param targetId which one
   */
  deleteMedia: (
    mediaObjectId: string,
    targetKind: "ITEM" | "LOCATION",
    targetId: string,
  ): Promise<void> => {
    const params = new URLSearchParams({ targetKind, targetId });
    return request<void>(`/api/v1/media/${mediaObjectId}?${params.toString()}`, {
      method: "DELETE",
    });
  },
};
