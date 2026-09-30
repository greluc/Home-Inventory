// @vitest-environment jsdom
/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";

import { api } from "./api";
import { ThirdPartyNotices } from "./ThirdPartyNotices";

vi.mock("./api", () => ({ api: { notices: vi.fn() } }));

/** One artifact's notice, as small as it can be and still be one. */
function notice(title: string, component: string) {
  return {
    artifact: title,
    title,
    components: [{ name: component, version: "1.0.0", licences: ["MIT"], notices: [] }],
    licences: [{ id: "MIT", source: "somewhere", text: "Permission is hereby granted" }],
  };
}

/** What jsdom's `<dialog>` is missing: opening it. */
function showModal(this: HTMLDialogElement): void {
  this.open = true;
}

/** And closing it, which the component does from its own button. */
function close(this: HTMLDialogElement): void {
  this.open = false;
  this.dispatchEvent(new Event("close"));
}

/**
 * The notices view shows **both** artifacts (`REQ-CON-013`,
 * [ADR-0083](../../docs/adr/0083-the-notice-travels-inside-the-artifact.md)).
 *
 * The API image and this bundle carry different dependencies, and neither notice covers the other.
 * A view that fetched one and showed it under the heading "third-party notices" would
 * under-attribute whichever half it left out — which is the failure this test exists for, and the
 * one a type checker cannot see, because both halves have the same type.
 *
 * `jsdom` only here, by the docblock at the top: every other test in this project is pure logic
 * and runs in node, and a browser environment for all of them would be slower for no benefit.
 */
describe("the third-party notices view", () => {
  beforeAll(() => {
    HTMLDialogElement.prototype.showModal ??= showModal;
    HTMLDialogElement.prototype.close ??= close;
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("shows the server's components and the bundle's, each under its own heading", async () => {
    vi.mocked(api.notices).mockResolvedValue(notice("app", "logback-classic"));
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response(JSON.stringify(notice("web", "react")), {
            headers: { "content-type": "application/json" },
          }),
      ),
    );

    render(<ThirdPartyNotices onClose={() => undefined} />);

    await waitFor(() => expect(screen.getByText("logback-classic")).toBeDefined());
    expect(screen.getByText("react")).toBeDefined();
    expect(screen.getAllByText(/Permission is hereby granted/)).toHaveLength(2);
  });

  it("says so rather than showing an empty dialog when the notices cannot be loaded", async () => {
    vi.mocked(api.notices).mockRejectedValue(new Error("no"));
    vi.stubGlobal("fetch", vi.fn(async () => new Response("no", { status: 500 })));

    render(<ThirdPartyNotices onClose={() => undefined} />);

    await waitFor(() => expect(screen.getByText("about.noticesUnavailable")).toBeDefined());
  });
});
