/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Rendering the values the API sends in the reader's own terms.
 *
 * The server stores and sends every time as UTC (`timestamptz`, ISO-8601 with a
 * `Z`), and the reader sees it in their zone and their language. That split is
 * REQ-NFR-035, and it is the client's half of it: only the browser knows the zone,
 * and it has better locale data than a JVM ever will
 * ([ADR-0025](../../docs/adr/0025-money-representation.md) makes the same argument
 * about money).
 */

/**
 * A timestamp from the API, in the reader's zone and language.
 *
 * @param iso an ISO-8601 instant as the API sends it, in UTC
 * @param language the interface language
 * @param timeZone an IANA zone, or omitted for the browser's own — which is the
 *   normal case and the whole point. It is a parameter only so that a test can
 *   name a zone, because "the browser's own" is one value on one machine and the
 *   requirement is about several
 * @returns the local date and time, or the input when it is not a date
 */
export function formatTimestamp(iso: string, language: string, timeZone?: string): string {
  const when = new Date(iso);
  if (Number.isNaN(when.getTime())) {
    // Shown as it arrived rather than as "Invalid Date". A value this function
    // cannot read is a bug somewhere else, and hiding it behind a friendly
    // string is how it stays hidden.
    return iso;
  }
  return new Intl.DateTimeFormat(language, {
    dateStyle: "medium",
    timeStyle: "short",
    // Omitted rather than set to undefined: with `exactOptionalPropertyTypes` the
    // two differ, and an explicit `undefined` would be a zone option that is
    // present and empty.
    ...(timeZone === undefined ? {} : { timeZone }),
  }).format(when);
}
