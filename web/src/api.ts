/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * The typed client for `/api/v1`.
 *
 * **Generated, plus the three things the document cannot describe** (`REQ-API-002`).
 * Every path, parameter and response shape below comes from
 * [`src/generated/api.d.ts`](./generated/api.d.ts), which `scripts/client.mjs`
 * produces from `api/openapi.yaml` — itself generated from the running
 * application (ADR-0049). A path this file mistypes, a parameter it invents and a
 * field it reads that the server does not send are all compile errors now, which
 * is what `scripts/contract.mjs` used to check by comparing strings.
 *
 * What stays hand-written is transport policy, because none of it is in the
 * document:
 *
 * - It sends the CSRF token on every mutating request. Spring writes the token
 *   into a readable cookie and expects it back in a header, and a header is the
 *   point: a form posted from another origin can carry a body but not a custom
 *   header, so the token cannot be replayed by a cross-site form.
 * - It turns an RFC 9457 problem document into a typed error. Every error in this
 *   application has that shape (REQ-API-003), so unwrapping it once here means no
 *   component has to — and it is why the methods below throw rather than
 *   returning `openapi-fetch`'s `{ data, error }`, which would push a branch into
 *   every call site.
 * - It sends `credentials: "same-origin"` and never `include`. The session cookie
 *   is `SameSite=Strict` and `__Host-`-prefixed; `include` would be an invitation
 *   to send it somewhere it was never meant to go.
 */

import createClient, { type Middleware } from "openapi-fetch";
import type { components, paths } from "./generated/api";

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

/**
 * The shapes, straight from the document.
 *
 * Aliases rather than copies: a field added to `ItemView` on the server appears
 * here when the client is regenerated, and a field removed from it stops
 * compiling wherever a component still reads it. That is the whole point of
 * `REQ-API-002`, and it is why these are one line each.
 */
export type Session = components["schemas"]["SessionView"];
/** An item as the API returns it. */
export type Item = components["schemas"]["ItemView"];
/** What the account holds besides its password. */
export type SecondFactorEnrolment = components["schemas"]["EnrolmentView"];
/** One registered authenticator. */
export type Passkey = components["schemas"]["PasskeyView"];
/** A WebAuthn ceremony, as the browser's API takes it. */
export type Ceremony = components["schemas"]["CeremonyView"];
/** A fresh TOTP secret, readable once. */
export type TotpEnrolment = components["schemas"]["TotpEnrolmentView"];
/** The recovery codes, readable once. */
export type RecoveryCodes = components["schemas"]["RecoveryCodesView"];
/** A place. */
export type Location = components["schemas"]["LocationView"];
/** A kind of place. */
export type LocationCategory = components["schemas"]["LocationCategoryView"];
/** A stored file. */
export type Media = components["schemas"]["MediaView"];
/** What this instance is running (REQ-CON-009). */
export type Version = components["schemas"]["BuildVersion"];
/** Everything third-party an artifact carries, with the licence text it is under. */
export type ThirdPartyNotices = components["schemas"]["ThirdPartyNoticesView"];

/** One page of items. */
export type ItemPage = components["schemas"]["PageItemView"];
/** One page of places. */
export type LocationPage = components["schemas"]["PageLocationView"];
/** One page of kinds of place. */
export type LocationCategoryPage = components["schemas"]["PageLocationCategoryView"];
/** One page of files. */
export type MediaPage = components["schemas"]["PageMediaView"];

/**
 * A page of anything, for a component that does not care what is in it.
 *
 * The document types each page concretely — `PageItemView`, `PageLocationView` —
 * because that is what the server returns. This keeps the one generic name the
 * components already use.
 */
export interface Page<T> {
  readonly data: T[];
  readonly page: components["schemas"]["PageInfo"];
  readonly meta?: components["schemas"]["Meta"];
}

/**
 * Reads one cookie.
 *
 * @param name the cookie's name
 * @returns its value, still encoded, or undefined
 */
function cookie(name: string): string | undefined {
  return document.cookie
    .split("; ")
    .find((entry) => entry.startsWith(`${name}=`))
    ?.slice(name.length + 1);
}

/**
 * What this client calls itself to the server (REQ-API-009).
 *
 * A header and not `User-Agent`: a browser sets that one itself and a page cannot override it on
 * `fetch`, which is why ADR-0011's "every client sends a User-Agent with product and version" could
 * not be honoured here. The shape is the one that ADR asked for -- product, then version -- and the
 * server tags its usage metric with the product alone, so releases do not each become their own
 * time series.
 */
const CLIENT = `web/${APP_VERSION}`;

/**
 * The three transport rules, applied to every request the generated client makes.
 *
 * A middleware rather than a wrapper per call: `openapi-fetch` runs it for every
 * method, so a request added later cannot be the one that forgets the CSRF
 * token.
 */
const transport: Middleware = {
  onRequest({ request }) {
    // On every request, including the reads: an API version is retired when
    // nobody is calling it, and "nobody" has to include the people only reading.
    request.headers.set("X-Home-Inv-Client", CLIENT);

    if (request.method !== "GET" && request.method !== "HEAD") {
      const token = cookie("XSRF-TOKEN");
      if (token) {
        request.headers.set("X-XSRF-TOKEN", decodeURIComponent(token));
      }
    }
    return request;
  },
};

const client = createClient<paths>({ credentials: "same-origin" });
client.use(transport);

/**
 * Unwraps what `openapi-fetch` returned, or throws the problem document.
 *
 * @param result what the generated client answered
 * @returns the body, typed
 * @throws ApiError when the server answered with a problem document
 */
function unwrap<T>(result: {
  data?: T;
  error?: unknown;
  response: Response;
}): T {
  if (result.error !== undefined) {
    const problem = (result.error ?? {}) as Record<string, unknown>;
    throw new ApiError(
      result.response.status,
      typeof problem.type === "string" ? problem.type : "about:blank",
      typeof problem.detail === "string" ? problem.detail : result.response.statusText,
      typeof problem.traceId === "string" ? problem.traceId : undefined,
    );
  }
  // A 204 carries no body, and the generated types say so: the call sites that
  // reach this expect `void`, and `undefined` is what they get.
  return result.data as T;
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
  version: async (): Promise<Version> =>
    unwrap(await client.GET("/api/v1/version")),

  /**
   * What the server is built from, and under which licences (REQ-CON-013).
   *
   * Public for the same reason the version is: an attribution only signed-in
   * people could read would be one owed to whoever has an account.
   *
   * This is the **server's** notice. The client's own is a static file in this
   * bundle — two artifacts, two dependency sets — and `ThirdPartyNotices.tsx`
   * shows them side by side rather than pretending either covers the other.
   *
   * @returns every third-party component in the API image
   */
  notices: async (): Promise<ThirdPartyNotices> =>
    unwrap(await client.GET("/api/v1/version/notices")),

  /**
   * Logs in and establishes a session.
   *
   * @param email the address
   * @param password the password
   * @returns who the caller now is
   */
  login: async (email: string, password: string): Promise<Session> =>
    unwrap(await client.POST("/api/v1/auth/login", { body: { email, password } })),

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
  completeSecondFactor: async (code: string): Promise<Session> =>
    unwrap(await client.POST("/api/v1/auth/mfa", { body: { code } })),

  /** What the account holds besides its password. */
  secondFactor: async (): Promise<SecondFactorEnrolment> =>
    unwrap(await client.GET("/api/v1/auth/mfa/enrolment")),

  /**
   * Answers a second factor with a passkey instead of a code.
   *
   * @param credential what the browser produced
   * @returns who the caller now is
   */
  completeWithPasskey: async (credential: string): Promise<Session> =>
    unwrap(await client.POST("/api/v1/auth/mfa", { body: { credential } })),

  /** The options for proving a passkey, whether in a login or in a re-confirmation. */
  passkeyChallenge: async (): Promise<Ceremony> =>
    unwrap(await client.POST("/api/v1/auth/mfa/passkeys/challenge", {})),

  /** The options for registering a passkey. */
  beginPasskey: async (): Promise<Ceremony> =>
    unwrap(await client.POST("/api/v1/auth/mfa/passkeys", {})),

  /**
   * Finishes registering a passkey.
   *
   * @param credential what the browser produced
   * @param label what to call this authenticator
   */
  confirmPasskey: async (credential: string, label: string): Promise<void> => {
    unwrap(
      await client.POST("/api/v1/auth/mfa/passkeys/confirmation", {
        body: { credential, label },
      }),
    );
  },

  /**
   * Begins an enrolment; the secret comes back once and is never readable again.
   *
   * @returns the secret and the provisioning URI
   */
  beginTotpEnrolment: async (): Promise<TotpEnrolment> =>
    unwrap(await client.POST("/api/v1/auth/mfa/totp", {})),

  /**
   * Confirms an enrolment with a code from the new secret, and takes the recovery codes.
   *
   * @param code the six digits the app shows
   * @returns the recovery codes, readable this once
   */
  confirmTotpEnrolment: async (code: string): Promise<RecoveryCodes> =>
    unwrap(await client.POST("/api/v1/auth/mfa/totp/confirmation", { body: { code } })),

  /** Ends the session. */
  logout: async (): Promise<void> => {
    unwrap(await client.POST("/api/v1/auth/logout", {}));
  },

  /**
   * Who the caller is, or a 401 when nobody.
   *
   * Called on start-up to decide between the application and the login form,
   * rather than remembering a flag in storage: the session lives on the server
   * and only the server knows whether it is still valid.
   */
  me: async (): Promise<Session> => unwrap(await client.GET("/api/v1/auth/me")),

  /**
   * Searches items.
   *
   * @param query the text; empty lists everything
   * @param language which generated vector to search — the interface language, so
   *   that a German user's search is stemmed with German rules (ADR-0047)
   * @param cursor the opaque cursor from a previous page
   * @returns one page of items
   */
  search: async (query: string, language: string, cursor?: string): Promise<ItemPage> =>
    unwrap(
      await client.GET("/api/v1/items", {
        // The cursor is spread in rather than set to `undefined`: the document
        // says the parameter may be ABSENT, and `exactOptionalPropertyTypes`
        // holds the client to the difference between absent and empty.
        params: { query: { q: query, language, limit: 50, ...(cursor ? { cursor } : {}) } },
      }),
    ),

  /**
   * Creates an item.
   *
   * No type reference is sent: stage 0 has no type system to choose from and the
   * server fills in the tenant's built-in type (O27).
   *
   * @param item what to create
   * @returns the stored item
   */
  createItem: async (item: {
    name: string;
    description?: string;
    kind: "PHYSICAL" | "DIGITAL";
    locationId?: string;
    quantity?: string;
    quantityUnit?: string;
  }): Promise<Item> =>
    unwrap(
      await client.POST("/api/v1/items", {
        // `quantity` arrives from a form as text and the document says the field
        // is a NUMBER. This client sent the text until 2026-09-21 and nothing
        // complained, because Jackson parses a JSON string into a BigDecimal --
        // so the request worked while disagreeing with the contract every other
        // consumer generates from. The conversion is here, at the edge, where the
        // form's shape meets the document's.
        body: {
          name: item.name,
          kind: item.kind,
          ...(item.description ? { description: item.description } : {}),
          ...(item.locationId ? { locationId: item.locationId } : {}),
          ...(item.quantity ? { quantity: Number(item.quantity) } : {}),
          ...(item.quantityUnit ? { quantityUnit: item.quantityUnit } : {}),
        },
      }),
    ),

  /**
   * Reads one item.
   *
   * @param id the item
   * @returns it
   */
  item: async (id: string): Promise<Item> =>
    unwrap(await client.GET("/api/v1/items/{id}", { params: { path: { id } } })),

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
  deleteItem: async (id: string, version: number): Promise<void> => {
    unwrap(
      await client.DELETE("/api/v1/items/{id}", {
        params: { path: { id } },
        headers: { "If-Match": `"${version}"` },
      }),
    );
  },

  /**
   * Creates a location.
   *
   * @param location what to create
   * @returns the stored place
   */
  createLocation: async (location: {
    name: string;
    categoryId: string;
    parentId?: string;
  }): Promise<Location> => unwrap(await client.POST("/api/v1/locations", { body: location })),

  /**
   * Reads one location with its readable path.
   *
   * @param id the place
   * @returns it
   */
  location: async (id: string): Promise<Location> =>
    unwrap(await client.GET("/api/v1/locations/{id}", { params: { path: { id } } })),

  /**
   * The tenant's locations, as a page.
   *
   * The whole tree rather than one level: each row carries its parent and its
   * readable path, which is what the picker assembles a tree from.
   *
   * @param cursor the opaque cursor from a previous page
   * @returns one page of places
   */
  locations: async (cursor?: string): Promise<LocationPage> =>
    unwrap(
      await client.GET("/api/v1/locations", {
        params: { query: { limit: 200, ...(cursor ? { cursor } : {}) } },
      }),
    ),

  /**
   * The kinds of place a location can be.
   *
   * @returns one page of categories
   */
  locationCategories: async (): Promise<LocationCategoryPage> =>
    unwrap(
      await client.GET("/api/v1/locations/categories", { params: { query: { limit: 200 } } }),
    ),

  /**
   * The files attached to one thing.
   *
   * @param targetKind `ITEM` or `LOCATION`
   * @param targetId what they hang on
   * @returns one page of files
   */
  media: async (targetKind: "ITEM" | "LOCATION", targetId: string): Promise<MediaPage> =>
    unwrap(
      await client.GET("/api/v1/media", {
        params: { query: { targetKind, targetId, limit: 200 } },
      }),
    ),

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
   * @returns the stored file
   */
  uploadMedia: async (
    file: File,
    targetKind: "ITEM" | "LOCATION",
    targetId: string,
  ): Promise<Media> => {
    const body = new FormData();
    body.append("file", file);
    return unwrap(
      await client.POST("/api/v1/media", {
        params: { query: { targetKind, targetId } },
        body: body as never,
        bodySerializer: (form: unknown) => form as BodyInit,
      }),
    );
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
   * @returns it
   */
  mediaObject: async (mediaObjectId: string): Promise<Media> =>
    unwrap(
      await client.GET("/api/v1/media/{mediaObjectId}", {
        params: { path: { mediaObjectId } },
      }),
    ),

  /**
   * Detaches a file.
   *
   * @param mediaObjectId the file
   * @param targetKind what it hangs on
   * @param targetId which one
   */
  deleteMedia: async (
    mediaObjectId: string,
    targetKind: "ITEM" | "LOCATION",
    targetId: string,
  ): Promise<void> => {
    unwrap(
      await client.DELETE("/api/v1/media/{mediaObjectId}", {
        params: { path: { mediaObjectId }, query: { targetKind, targetId } },
      }),
    );
  },
};
