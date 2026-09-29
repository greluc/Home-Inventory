/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Computes the Content-Security-Policy from the built bundle and writes the
 * server configuration that emits it.
 *
 * ADR-0038 decides three things this script implements:
 *
 *  1. **`web` serves the headers**, not the operator's reverse proxy. A header an
 *     operator writes into their own config cannot be tested here, and a policy
 *     nobody tests drifts until the first `unsafe-inline` appears to make
 *     something work.
 *  2. **Hashes, not nonces.** The inline content is fixed at build time, so the
 *     policy can name exactly what is authorised rather than authorising whatever
 *     arrives with the right nonce.
 *  3. **`trusted-types default` beside `require-trusted-types-for 'script'`.** On
 *     its own the requirement leaves policy *creation* unrestricted, so anything
 *     achieving script execution can mint its own policy and satisfy the check.
 *     Naming the one policy this bundle creates closes that.
 *
 * `--check` recomputes and compares instead of writing. CI runs it, so a policy
 * that no longer matches its bundle fails the build rather than failing a user's
 * browser — the same drift mechanism as `deploy/services.yaml`.
 */

import { createHash } from "node:crypto";
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..");
const builtIndex = join(root, "dist", "index.html");
const nginxConf = join(root, "nginx", "default.conf");

/**
 * The base64 SHA-256 of a string, in the form a CSP hash source takes.
 *
 * The hash covers the element's content exactly as it appears between the tags —
 * every space and newline included. That is why a single character changed in the
 * bootstrap changes the hash, which is the coupling this whole mechanism relies
 * on.
 *
 * **Newlines are normalised first, because the browser's are.** An HTML parser
 * turns every CRLF and CR into a single LF before the script element has any text
 * content, and the hash the browser checks is taken from that text — not from the
 * bytes on the wire. Hashing the file as it lies on disk therefore produces a
 * policy that rejects its own bundle on any checkout where the file has CRLF,
 * with `--check` passing all the while because both halves read the same bytes.
 *
 * `.gitattributes` asks for LF everywhere, which is why nothing had seen this;
 * a worktree that predates that line still holds CRLF, and there the application
 * served a blank page — the theme bootstrap is the one inline script, and
 * `html[data-theme-pending] body` stays hidden until it has run. A build should
 * not depend on a checkout's line endings to produce a working page.
 *
 * @param {string} content the element's text content
 * @returns {string} the `sha256-…` source expression
 */
function hashOf(content) {
  const parsed = content.replace(/\r\n?/g, "\n");
  return `'sha256-${createHash("sha256").update(parsed, "utf8").digest("base64")}'`;
}

/**
 * Extracts the content of every inline element of one kind.
 *
 * @param {string} html the built document
 * @param {"script"|"style"} tag which element
 * @returns {string[]} the contents, in document order
 */
function inlineContents(html, tag) {
  const pattern = new RegExp(`<${tag}(?![^>]*\\bsrc=)[^>]*>([\\s\\S]*?)</${tag}>`, "g");
  return [...html.matchAll(pattern)].map((match) => match[1]);
}

if (!existsSync(builtIndex)) {
  console.error(`${builtIndex} is missing. Run the build first.`);
  process.exit(1);
}

const html = readFileSync(builtIndex, "utf8");
const scriptHashes = inlineContents(html, "script").map(hashOf);
const styleHashes = inlineContents(html, "style").map(hashOf);

if (scriptHashes.length === 0) {
  console.error("No inline script found in the built index.html. The theme bootstrap is missing.");
  process.exit(1);
}

const policy = [
  "default-src 'none'",
  `script-src 'self' ${scriptHashes.join(" ")}`,
  `style-src 'self'${styleHashes.length > 0 ? " " + styleHashes.join(" ") : ""}`,
  "img-src 'self' data: blob:",
  "font-src 'self'",
  "connect-src 'self'",
  "manifest-src 'self'",
  "worker-src 'self'",
  "frame-ancestors 'none'",
  "frame-src 'none'",
  "object-src 'none'",
  "base-uri 'none'",
  "form-action 'self'",
  "require-trusted-types-for 'script'",
  "trusted-types default",
  "upgrade-insecure-requests",
].join("; ");

const BODY_LIMIT = "64m";
const READ_TIMEOUT = "60s";

/**
 * The security headers the application shell is served with.
 *
 * Emitted per location rather than once at server level, and that is not a
 * style choice: nginx inherits `add_header` from the enclosing level ONLY when
 * the current level defines none. A server-level block would therefore be
 * inherited by `/api/` and `/media/` — which already send their own headers from
 * the application — and the client would receive two Content-Security-Policy
 * headers, both enforced, with the media sandbox and the application policy
 * fighting each other.
 *
 * @param {string} extra additional directives for this location
 * @returns {string} the `add_header` lines, indented for a location block
 */
function headers(extra = "") {
  return `
        add_header Content-Security-Policy "${policy}" always;

        add_header Strict-Transport-Security "max-age=63072000; includeSubDomains" always;
        add_header X-Content-Type-Options "nosniff" always;
        add_header Referrer-Policy "strict-origin-when-cross-origin" always;
        add_header Cross-Origin-Opener-Policy "same-origin" always;
        add_header Cross-Origin-Resource-Policy "same-origin" always;
        add_header Permissions-Policy "camera=(self), geolocation=(), microphone=(), payment=(), usb=()" always;
${extra}`;
}

const conf = `# GENERATED BY scripts/csp.mjs — DO NOT EDIT.

upstream homeinv_api {
    server api:8080;
    keepalive 16;
}

server {
    listen 8080;
    server_name _;

    root /usr/share/nginx/html;
    index index.html;

    client_max_body_size ${BODY_LIMIT};

    location = /healthz {
        access_log off;
        default_type text/plain;
        return 200 "ok\\n";
    }

    location /api/ {
        proxy_pass http://homeinv_api;
        proxy_http_version 1.1;

        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header Host $host;

        proxy_read_timeout ${READ_TIMEOUT};
        proxy_send_timeout ${READ_TIMEOUT};

        proxy_buffering off;
        proxy_cache off;

    }

    location = /graphql {
        proxy_pass http://homeinv_api;
        proxy_http_version 1.1;

        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header Host $host;

        proxy_read_timeout ${READ_TIMEOUT};
        proxy_send_timeout ${READ_TIMEOUT};

    }

    location /media/ {
        proxy_pass http://homeinv_api;
        proxy_http_version 1.1;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Host $host;
        proxy_read_timeout ${READ_TIMEOUT};
    }

    location /assets/ {
${headers('        add_header Cache-Control "public, max-age=31536000, immutable" always;')}
        try_files $uri =404;
    }

    location = /index.html {
${headers('        add_header Cache-Control "no-cache" always;')}
    }

    location / {
${headers()}
        try_files $uri $uri/ /index.html;
    }
}
`;

const check = process.argv.includes("--check");
if (check) {
  const current = existsSync(nginxConf) ? readFileSync(nginxConf, "utf8") : "";
  if (current !== conf) {
    console.error("The served CSP does not match the built bundle.");
    console.error("Run `npm run build` and commit nginx/default.conf.");
    process.exit(1);
  }
  console.log("The served CSP matches the built bundle.");
} else {
  writeFileSync(nginxConf, conf, "utf8");
  console.log(`Wrote ${nginxConf}`);
  console.log(`  script hashes: ${scriptHashes.length}, style hashes: ${styleHashes.length}`);
}
