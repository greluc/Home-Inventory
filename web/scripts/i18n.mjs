/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Checks that the interface is actually translated (REQ-NFR-032, REQ-NFR-033).
 *
 * Three things, each of which has a way of going wrong silently:
 *
 *  1. **Every key a component asks for exists.** i18next answers a missing key
 *     with the key itself, so a typo ships as `item.quntity` on screen and looks
 *     like a styling problem.
 *  2. **The bundles carry the same keys.** English is the fallback, so a key
 *     missing from German falls back and looks translated to anybody testing in
 *     English. The gate of REQ-NFR-033 is "complete translations for both".
 *  3. **No key is orphaned.** A string nothing asks for any more is a sentence
 *     somebody will keep translating.
 *
 * Run with `npm run i18n:check`. It parses no TypeScript: the keys are matched
 * out of the source with a regular expression, which finds every literal one and
 * deliberately cannot find a computed one. Those are listed below by prefix
 * instead, because a computed key is exactly the case a check like this cannot
 * see and a reader has to be told about.
 */

import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import process from "node:process";

const SOURCE = new URL("../src/", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");

/**
 * Key prefixes whose leaves are chosen at run time, from data.
 *
 * `t(`location.categories.${category.key}`)` cannot be matched literally: the key
 * comes from the server. Every leaf under these prefixes counts as used, and the
 * bundles are still compared against each other, which is what catches a category
 * translated in one language and not the other.
 */
const COMPUTED_PREFIXES = ["location.categories.", "item.kinds.", "language."];

/**
 * Every key in a bundle, as dotted paths.
 *
 * @param {object} node the bundle or a subtree of it
 * @param {string} prefix the path so far
 * @returns {string[]} the leaf paths
 */
function keysOf(node, prefix = "") {
  return Object.entries(node).flatMap(([key, value]) =>
    typeof value === "object" && value !== null
      ? keysOf(value, `${prefix}${key}.`)
      : [`${prefix}${key}`],
  );
}

/**
 * Every source file under a directory.
 *
 * @param {string} directory where to look
 * @returns {string[]} the paths
 */
function sources(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      return sources(path);
    }
    return entry.name.endsWith(".tsx") || entry.name.endsWith(".ts") ? [path] : [];
  });
}

const en = JSON.parse(readFileSync(new URL("../src/i18n/en.json", import.meta.url), "utf8"));
const de = JSON.parse(readFileSync(new URL("../src/i18n/de.json", import.meta.url), "utf8"));

const english = new Set(keysOf(en));
const german = new Set(keysOf(de));

const asked = new Set();
for (const file of sources(SOURCE)) {
  const text = readFileSync(file, "utf8");
  for (const match of text.matchAll(/\bt\(\s*"([A-Za-z0-9_.-]+)"/g)) {
    asked.add(match[1]);
  }
}

const problems = [];

for (const key of asked) {
  if (!english.has(key)) {
    problems.push(`asked for but not in en.json: ${key}`);
  }
}

for (const key of english) {
  if (!german.has(key)) {
    problems.push(`in en.json and not in de.json: ${key}`);
  }
}
for (const key of german) {
  if (!english.has(key)) {
    problems.push(`in de.json and not in en.json: ${key}`);
  }
}

for (const key of english) {
  const computed = COMPUTED_PREFIXES.some((prefix) => key.startsWith(prefix));
  if (!computed && !asked.has(key)) {
    problems.push(`in the bundles and asked for nowhere: ${key}`);
  }
}

if (problems.length > 0) {
  console.error("The translation bundles and the interface disagree:\n");
  for (const problem of problems.toSorted()) {
    console.error(`  ${problem}`);
  }
  console.error(
    "\nEvery key a component asks for is in both bundles, and every key in the bundles is asked",
  );
  console.error("for somewhere (REQ-NFR-032, REQ-NFR-033).");
  process.exit(1);
}

console.log(`${english.size} keys, both languages complete, all of them used.`);
