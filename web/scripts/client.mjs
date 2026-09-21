/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Generates the typed client from the OpenAPI document (`REQ-API-002`).
 *
 * `api/openapi.yaml` is generated from the running application and committed
 * ([ADR-0049](../../docs/adr/0049-openapi-generated-from-the-implementation.md)),
 * so it is the one description of the HTTP surface that cannot disagree with the
 * code. This turns it into TypeScript: `paths`, `components` and `operations`,
 * types only, no runtime.
 *
 * WHY THE GENERATOR IS RUN THROUGH `npx` AND IS NOT A DEPENDENCY
 *
 * `openapi-typescript` declares a peer dependency on `typescript@^5.x` and this
 * client is on **7**. It works — the output below was produced with it and
 * compiles under 7 — but `npm ci` refuses to install the tree, and a lockfile
 * built with `--legacy-peer-deps` would make every future install carry that
 * decision silently. The same substitution `REQ-SEC-076` records for `oxlint`
 * against `typescript-eslint`, for the same reason.
 *
 * A pinned `npx` is what CI already does for `@stoplight/spectral-cli`, and it
 * costs nothing here: the OUTPUT is committed, so the application builds without
 * the generator. Only regenerating and the drift check need it.
 *
 * WHY THE OUTPUT IS COMMITTED
 *
 * The same reason `api/openapi.yaml`, `deploy/generated/`, `nginx/default.conf`
 * and the persisted-query register are: a contract change is then a diff in a
 * pull request, beside the code that caused it, rather than something that
 * happens inside a build nobody reads.
 *
 * `--check` regenerates and compares instead of writing. CI runs it.
 */

import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync, existsSync, mkdirSync, rmSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { tmpdir } from "node:os";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..");
const document = join(root, "..", "api", "openapi.yaml");
const client = join(root, "src", "generated", "api.d.ts");

/**
 * The generator, pinned.
 *
 * Exactly, not a range: the output is committed, so a generator that changed its
 * formatting between two contributors' machines would show up as a diff nobody
 * made. The version moves when somebody moves it and sees the diff.
 */
const GENERATOR = "openapi-typescript@7.13.0";

const HEADER = `/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/* GENERATED FROM ../../../api/openapi.yaml BY scripts/client.mjs - DO NOT EDIT.
 *
 * \`npm run client:generate\` rewrites it; \`npm run client:check\` fails while this
 * file and the document disagree (REQ-API-002). What uses it is src/api.ts, which
 * adds the three things the document does not describe: the CSRF header, the
 * RFC 9457 unwrapping, and \`credentials: "same-origin"\`.
 */

`;

/**
 * Runs the generator and returns what it produced.
 *
 * @returns {string} the TypeScript source, with this project's header on it
 */
function generate() {
  const scratch = join(tmpdir(), `home-inv-client-${process.pid}.d.ts`);
  try {
    // `shell` on Windows, because Node refuses to spawn a `.cmd` without one
    // (EINVAL since Node 20), and every argument quoted because a shell splits
    // on spaces and somebody's checkout lives under "My Documents".
    const windows = process.platform === "win32";
    const quote = (argument) => (windows ? `"${argument}"` : argument);
    execFileSync(
      windows ? "npx.cmd" : "npx",
      ["--yes", GENERATOR, document, "-o", scratch].map(quote),
      { stdio: ["ignore", "ignore", "inherit"], shell: windows },
    );
    return HEADER + readFileSync(scratch, "utf8");
  } finally {
    rmSync(scratch, { force: true });
  }
}

const expected = generate();
const checking = process.argv.includes("--check");

if (checking) {
  const actual = existsSync(client) ? readFileSync(client, "utf8") : "";
  if (actual !== expected) {
    console.error("The generated client does not match api/openapi.yaml.");
    console.error("Run `npm run client:generate` and commit src/generated/api.d.ts.");
    process.exit(1);
  }
  console.log("The generated client matches the API document.");
} else {
  mkdirSync(dirname(client), { recursive: true });
  writeFileSync(client, expected, "utf8");
  console.log(`Wrote ${client}`);
}
