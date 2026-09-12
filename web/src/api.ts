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

/** A page of search results. */
export interface SearchResult {
  items: Item[];
  nextCursor: string | null;
}

/** A storage location with its readable path. */
export interface Location {
  id: string;
  name: string;
  categoryId: string;
  parentId: string | null;
  depth: number;
  ancestors: string[];
}

/** A page of locations. */
export interface LocationPage {
  items: Location[];
  nextCursor: string | null;
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
  mobile: boolean;
}

/** A page of location categories. */
export interface LocationCategoryPage {
  items: LocationCategory[];
  nextCursor: string | null;
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

/** A page of attachments. */
export interface MediaPage {
  items: Media[];
  nextCursor: string | null;
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
export const api = {
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
  search: (query: string, language: string, cursor?: string): Promise<SearchResult> => {
    const params = new URLSearchParams({ q: query, language, limit: "50" });
    if (cursor) {
      params.set("cursor", cursor);
    }
    return request<SearchResult>(`/api/v1/search?${params.toString()}`);
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

  /** Deletes an item. */
  deleteItem: (id: string): Promise<void> =>
    request<void>(`/api/v1/items/${id}`, { method: "DELETE" }),

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
  locations: (cursor?: string): Promise<LocationPage> => {
    const params = new URLSearchParams({ limit: "200" });
    if (cursor) {
      params.set("cursor", cursor);
    }
    return request<LocationPage>(`/api/v1/locations?${params.toString()}`);
  },

  /** The kinds of place a location can be. */
  locationCategories: (): Promise<LocationCategoryPage> =>
    request<LocationCategoryPage>("/api/v1/locations/categories?limit=200"),

  /**
   * The files attached to one thing.
   *
   * @param targetKind `ITEM` or `LOCATION`
   * @param targetId what they hang on
   */
  media: (targetKind: "ITEM" | "LOCATION", targetId: string): Promise<MediaPage> => {
    const params = new URLSearchParams({ targetKind, targetId, limit: "200" });
    return request<MediaPage>(`/api/v1/media?${params.toString()}`);
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
