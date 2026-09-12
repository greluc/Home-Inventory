/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { describe, expect, it } from "vitest";
import { formatTimestamp } from "./format";

/**
 * REQ-NFR-035: times are stored in UTC and displayed in the user's time zone.
 *
 * The gate that requirement names is "a test across several time zones", and it
 * earns the plural: a formatter that ignored the zone entirely would pass a test
 * run in one. The instant below is chosen so that the three zones do not agree on
 * the calendar date, which no amount of rounding can produce by accident.
 */
describe("a timestamp from the API", () => {
  // 22:30 UTC, chosen so that the zones do not agree on the date: Berlin and
  // Tokyo are already on the 12th, New York is still on the 11th. A formatter
  // that ignored the zone could not produce both.
  const instant = "2026-09-11T22:30:00Z";

  it("is shown in the reader's zone, and the zone changes the answer", () => {
    const berlin = formatTimestamp(instant, "de", "Europe/Berlin");
    const newYork = formatTimestamp(instant, "en", "America/New_York");
    const tokyo = formatTimestamp(instant, "en", "Asia/Tokyo");

    expect(berlin).toContain("12.09.2026");
    expect(berlin).toContain("00:30");
    expect(newYork).toContain("Sep 11, 2026");
    expect(newYork).toContain("6:30 PM");
    expect(tokyo).toContain("Sep 12, 2026");
    expect(tokyo).toContain("7:30 AM");
  });

  it("is shown in the reader's language", () => {
    // The same instant, the same zone, two languages: German writes the month as
    // a number and the day first, English writes an abbreviated month name.
    expect(formatTimestamp(instant, "de", "UTC")).toContain("11.09.2026");
    expect(formatTimestamp(instant, "en", "UTC")).toContain("Sep 11, 2026");
  });

  it("is left alone when it is not a date", () => {
    // A value this cannot read is a bug somewhere else; "Invalid Date" on screen
    // is how such a bug stays hidden.
    expect(formatTimestamp("not-a-date", "en", "UTC")).toBe("not-a-date");
  });
});
