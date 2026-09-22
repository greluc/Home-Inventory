/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { describe, expect, it } from "vitest";

import { dependencies } from "../package.json";
import document from "../public/third-party-notices.json";

/**
 * The bundle carries the licence notice of everything in it (`REQ-CON-013`).
 *
 * A permissive licence — MIT, BSD, ISC — permits the copying on one condition: that its notice
 * appears **in all copies**, and a minified bundle is a copy. `tools/notices.py` generates the
 * file from what Rollup actually put into the chunks, and CI regenerates it and fails on a
 * difference. What that check cannot see is whether the document is *complete*: a generator that
 * emitted an empty list would produce a file that matches itself perfectly.
 *
 * The direct dependencies are the part worth pinning down by name. They are in the bundle by
 * construction — the application imports each of them — so a notice missing one is a broken
 * bundle report rather than a tree-shaken package, and that is the failure this catches.
 */
describe("the third-party notice of this bundle", () => {
  // Imported rather than read with `node:fs`: the type checker covers the test
  // files too, and a `node:` import would mean `@types/node` in a project whose
  // only runtime is a browser.
  const notice: {
    artifact: string;
    components: { name: string; version: string; licences: string[]; notices: { text: string }[] }[];
    licences: { id: string; text: string }[];
  } = document;

  it("names a licence and its words for every component", () => {
    const covered = new Set(notice.licences.map((licence) => licence.id));

    expect(notice.components.length).toBeGreaterThan(0);
    for (const component of notice.components) {
      expect(component.licences, component.name).not.toHaveLength(0);
      const carriesItsOwn = component.notices.some((entry) => entry.text.length > 100);
      const inTheAppendix = component.licences.some((licence) => covered.has(licence));
      expect(carriesItsOwn || inTheAppendix, `${component.name} is unattributed`).toBe(true);
    }
  });

  it("lists every direct dependency, which is in the bundle by construction", () => {
    const listed = new Set(notice.components.map((component) => component.name));

    expect(Object.keys(dependencies).filter((name) => !listed.has(name))).toEqual([]);
  });
});
