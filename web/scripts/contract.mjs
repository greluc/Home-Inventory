/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Checks that every path this client calls is one the API describes.
 *
 * The client is hand-written through stage 0 — `REQ-API-002` generates it from the
 * OpenAPI document at stage 1 — and a hand-written client drifts silently: a typo
 * in a path is a 404 at run time, on a screen nobody opened during review.
 *
 * `api/openapi.yaml` is itself generated from the running application and
 * drift-checked against it (ADR-0049), so comparing against it compares against
 * the implementation. This is the smaller half of what generation will do: it says
 * the path exists, not that the shape matches.
 *
 * Run with `npm run contract:check`. No YAML parser: the document's paths are two
 * spaces in from the left under `paths:`, which is enough to read them out, and a
 * dependency for this would be a dependency in a build that has very few.
 */

import { readFileSync } from "node:fs";
import process from "node:process";

const document = readFileSync(new URL("../../api/openapi.yaml", import.meta.url), "utf8");
const client = readFileSync(new URL("../src/api.ts", import.meta.url), "utf8");

/** Every path the document describes. */
const described = new Set();
let inPaths = false;
for (const line of document.split("\n")) {
  if (line.startsWith("paths:")) {
    inPaths = true;
    continue;
  }
  if (inPaths && /^\S/.test(line)) {
    break; // A new top-level key: the paths are over.
  }
  const match = /^ {2}(\/\S*):$/.exec(line);
  if (inPaths && match) {
    described.add(match[1]);
  }
}

if (described.size === 0) {
  console.error("api/openapi.yaml describes no paths. Run `./gradlew updateOpenApi`.");
  process.exit(1);
}

/**
 * Every path the client asks for, as a template the document would spell it.
 *
 * A request path in `api.ts` is a string or a template literal: `/api/v1/items`,
 * or `` `/api/v1/items/${id}` ``. The interpolation becomes `{…}` because that is
 * what OpenAPI calls a path parameter, and the name inside does not have to match
 * — the document names it after the handler's parameter and the client after its
 * own.
 */
const called = new Set();
for (const match of client.matchAll(/request<[^>]*>\(\s*([`"])([^`"]+)\1/g)) {
  const path = match[2]
    .replace(/\$\{[^}]+\}/g, "{}") // an interpolated segment
    .replace(/\?.*$/, ""); // the query string is not part of the path
  called.add(path);
}

if (called.size === 0) {
  console.error("No request paths found in src/api.ts. Has the client changed shape?");
  process.exit(1);
}

/**
 * Whether a called path matches a described one, segment by segment.
 *
 * @param {string} path what the client asks for, with `{}` for an interpolation
 * @returns {boolean} true when the document describes it
 */
function isDescribed(path) {
  const wanted = path.split("/");
  for (const candidate of described) {
    const segments = candidate.split("/");
    if (segments.length !== wanted.length) {
      continue;
    }
    const matches = segments.every(
      (segment, index) =>
        segment === wanted[index] || (segment.startsWith("{") && wanted[index] === "{}"),
    );
    if (matches) {
      return true;
    }
  }
  return false;
}

const missing = [...called].filter((path) => !isDescribed(path)).toSorted();
if (missing.length > 0) {
  console.error("This client calls paths the API does not describe:\n");
  for (const path of missing) {
    console.error(`  ${path}`);
  }
  console.error("\nEither the path is wrong, or `./gradlew updateOpenApi` has not been run.");
  process.exit(1);
}

console.log(`${called.size} client paths, every one of them in api/openapi.yaml.`);
