// @vitest-environment jsdom
/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { afterEach, describe, expect, it, vi } from "vitest";

import { UploadFailed, upload } from "./upload";

/**
 * An upload that survives a broken connection (`REQ-MED-008`).
 *
 * The requirement's whole acceptance criterion is *"an interrupted upload is continued, not
 * restarted"*, and that is one behaviour: when a chunk fails, the client asks the server where the
 * upload actually is and sends the rest from there. What makes it worth a test is that the failure
 * it guards against is invisible in the happy path — an implementation that restarted from zero
 * would pass every test that does not drop a connection, and would waste a person's data allowance
 * every time their train entered a tunnel.
 *
 * `fetch` is stubbed rather than a server started: what is under test is the client's side of the
 * protocol, and `ResumableUploadIT` holds the server's. `jsdom` because the uploader reads the CSRF
 * token from `document.cookie` on every request — a token is rotated on login, and an upload begun
 * before a session change would otherwise send one the server has stopped accepting.
 */
describe("the resumable upload", () => {
  const CREATED = new Response(null, {
    status: 201,
    headers: { Location: "/api/v1/media/uploads/u1" },
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("sends a small file in one piece and returns what it became", async () => {
    const calls: { method: string; offset: string | null }[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (_url: string, init: RequestInit) => {
        calls.push({
          method: init.method ?? "",
          offset: new Headers(init.headers).get("Upload-Offset"),
        });
        if (init.method === "POST") {
          return CREATED;
        }
        return new Response(null, {
          status: 204,
          headers: { "Upload-Offset": "10", Location: "/api/v1/media/m1" },
        });
      }),
    );

    const created = await upload(new Blob(["0123456789"]), {
      targetKind: "ITEM",
      targetId: "11111111-1111-4111-8111-111111111111",
    });

    expect(created).toBe("/api/v1/media/m1");
    expect(calls.map((call) => call.method)).toEqual(["POST", "PATCH"]);
  });

  it("continues from where the server says it is, rather than from the beginning", async () => {
    const sentAt: number[] = [];
    let dropped = false;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (_url: string, init: RequestInit) => {
        const headers = new Headers(init.headers);
        if (init.method === "POST") {
          return CREATED;
        }
        if (init.method === "HEAD") {
          // What actually arrived before the connection went away. The point of
          // the whole feature is that this number comes from the server.
          return new Response(null, { status: 200, headers: { "Upload-Offset": "6" } });
        }
        sentAt.push(Number(headers.get("Upload-Offset")));
        if (!dropped) {
          dropped = true;
          throw new TypeError("network error");
        }
        return new Response(null, {
          status: 204,
          headers: { "Upload-Offset": "10", Location: "/api/v1/media/m2" },
        });
      }),
    );

    const created = await upload(new Blob(["0123456789"]), {
      targetKind: "ITEM",
      targetId: "11111111-1111-4111-8111-111111111111",
    });

    expect(created).toBe("/api/v1/media/m2");
    // The second attempt starts at the server's offset and not at zero, which
    // is the entire requirement.
    expect(sentAt).toEqual([0, 6]);
  });

  it("gives up rather than retrying for ever", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (_url: string, init: RequestInit) => {
        if (init.method === "POST") {
          return CREATED;
        }
        if (init.method === "HEAD") {
          return new Response(null, { status: 200, headers: { "Upload-Offset": "0" } });
        }
        throw new TypeError("network error");
      }),
    );

    await expect(
      upload(new Blob(["0123456789"]), {
        targetKind: "ITEM",
        targetId: "11111111-1111-4111-8111-111111111111",
      }),
    ).rejects.toBeInstanceOf(UploadFailed);
  });

  it("asks what the file became when the last response went missing", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (_url: string, init: RequestInit) => {
        if (init.method === "POST") {
          return CREATED;
        }
        if (init.method === "HEAD") {
          // The upload is complete and the server remembers what it produced —
          // which is what stops a lost response costing a second upload of the
          // whole file.
          return new Response(null, {
            status: 200,
            headers: { "Upload-Offset": "10", Location: "/api/v1/media/m3" },
          });
        }
        return new Response(null, { status: 204, headers: { "Upload-Offset": "10" } });
      }),
    );

    await expect(
      upload(new Blob(["0123456789"]), {
        targetKind: "ITEM",
        targetId: "11111111-1111-4111-8111-111111111111",
      }),
    ).resolves.toBe("/api/v1/media/m3");
  });
});
