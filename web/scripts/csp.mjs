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
  // The theme bootstrap is the one inline script and it must be there: without it
  // a light-preferring user sees a dark flash on every load (ADR-0033). Its
  // absence means the build dropped it, which is worth failing over.
  console.error("No inline script found in the built index.html. The theme bootstrap is missing.");
  process.exit(1);
}

const policy = [
  // Nothing is allowed that is not named. `default-src 'none'` rather than
  // `'self'`, so a directive forgotten below fails closed instead of inheriting
  // a permissive default.
  "default-src 'none'",
  `script-src 'self' ${scriptHashes.join(" ")}`,
  `style-src 'self'${styleHashes.length > 0 ? " " + styleHashes.join(" ") : ""}`,
  // The media hostname is a separate origin, and signed URLs point at it.
  "img-src 'self' data: blob:",
  "font-src 'self'",
  // Same origin only. REQ-PRIV-015 allows no third-party host at all, and this is
  // where a violation of it would actually be stopped rather than merely
  // discouraged.
  "connect-src 'self'",
  "manifest-src 'self'",
  "worker-src 'self'",
  // Nothing embeds us and we embed nothing.
  "frame-ancestors 'none'",
  "frame-src 'none'",
  "object-src 'none'",
  "base-uri 'none'",
  "form-action 'self'",
  "require-trusted-types-for 'script'",
  "trusted-types default",
  "upgrade-insecure-requests",
].join("; ");

// The limits of the `web` -> `api` hop (06 §6.7, REQ-NFR-078). Each is stated
// here rather than left to a default, because each one wrong is quiet:
//
//   * a body limit BELOW the API's means nginx rejects an oversized upload with
//     an HTML error page, and a client promised `application/problem+json` gets
//     markup it cannot parse. 64 MB against the API's 25 MB upload ceiling, so
//     the rejection always comes from the application;
//   * a read timeout below the API's 30 s ceiling (08 §8.2) means `web` emits its
//     own 504 instead of the handled response;
//   * a read timeout below the SSE heartbeat (`homeinv.events.heartbeat-seconds`,
//     30 s) closes every live stream on a quiet tenant — and quiet is the normal
//     state of a home inventory, so it would look like a flaky connection rather
//     than like a misconfiguration (REQ-API-011).
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
  return `        # ADR-0038: the shell and every security header come from this container, so
        # the policy is in the repository and can be compared against an expected
        # string in CI (REQ-SEC-060).
        add_header Content-Security-Policy "${policy}" always;

        # HSTS without preload: \`max-age\` and \`includeSubDomains\` carry the
        # protection and cover the media and plugin subdomains this design creates.
        # Preload is a one-way door on a domain an operator may also use for other
        # things (O20).
        add_header Strict-Transport-Security "max-age=63072000; includeSubDomains" always;
        add_header X-Content-Type-Options "nosniff" always;
        add_header Referrer-Policy "strict-origin-when-cross-origin" always;
        add_header Cross-Origin-Opener-Policy "same-origin" always;
        add_header Cross-Origin-Resource-Policy "same-origin" always;
        # No COEP: ADR-0040 drops it, and REQ-SEC-061 makes CI fail if it reappears.
        add_header Permissions-Policy "camera=(self), geolocation=(), microphone=(), payment=(), usb=()" always;
${extra}`;
}

const conf = `# GENERATED BY scripts/csp.mjs — DO NOT EDIT.
#
# The hashes below are computed from the built bundle. Change the inline
# bootstrap in index.html and this file must be regenerated; \`npm run csp:check\`
# fails until it is, which is the intended coupling (ADR-0038).

# The application. ADR-0042 put \`web\` on the request path of EVERY API call, not
# only of the static shell, so this container is the ingress and the upstream is
# the only other member of the \`frontend\` segment (ADR-0044).
upstream homeinv_api {
    # The service name from deploy/services.yaml, resolved by the container
    # runtime's DNS on the two-member \`frontend\` segment. Literal rather than a
    # variable, exactly as \`blobstore:8100\` is literal in the matrix: it is a
    # deployment fact, and a variable here would need a \`resolver\` directive
    # whose address differs between Podman and Docker.
    server api:8080;
    keepalive 16;
}

server {
    listen 8080;
    server_name _;

    root /usr/share/nginx/html;
    index index.html;

    # \`web\` terminates no TLS. The operator's reverse proxy does, in front of it;
    # two terminations for one hostname would mean two certificate lifecycles
    # (06 §6.7, REQ-NFR-061).
    client_max_body_size ${BODY_LIMIT};

    # The health command the service matrix names. Answered by nginx itself and
    # not proxied: a liveness check that consults the upstream reports \`web\` as
    # unhealthy when \`api\` is down, and the runtime then restarts the one
    # container that was working.
    location = /healthz {
        access_log off;
        # default_type and not add_header: a return directive builds its own
        # response, and add_header cannot set its Content-Type.
        default_type text/plain;
        return 200 "ok\\n";
    }

    # ---------------------------------------------------------------------
    # The API, and the media path that shares this ingress
    # ---------------------------------------------------------------------
    location /api/ {
        proxy_pass http://homeinv_api;
        proxy_http_version 1.1;

        # Both hops have to reach \`api\`: the operator's reverse proxy set the
        # first, and this APPENDS the second. Overwriting it would make every
        # client appear to be this container — per-IP rate limiting would then
        # throttle all tenants as one and every audit entry would record the same
        # source address (REQ-SEC-103, 06 §6.7).
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header Host $host;

        # Above the API's own 30 s ceiling, so a slow response is answered by the
        # application rather than replaced by this proxy's 504.
        #
        # It is also what the SSE heartbeat is measured against: the stream sends
        # one every 30 s (\`homeinv.events.heartbeat-seconds\`), and a quiet stream
        # is closed by this timeout if the heartbeat is ever made slower than it.
        # A home inventory is quiet for hours, so the quiet case is the normal one.
        proxy_read_timeout ${READ_TIMEOUT};
        proxy_send_timeout ${READ_TIMEOUT};

        # Off, for the SSE stream of REQ-API-011. A buffering proxy holds events
        # until the buffer fills, so live updating appears to work in development
        # and silently stops working in the deployment.
        proxy_buffering off;
        proxy_cache off;

        # NOTHING is hidden or rewritten. ETag, Retry-After, RateLimit-*,
        # Deprecation, Sunset, Link and Content-Disposition each carry a
        # documented contract, and a proxy that drops RateLimit-* breaks
        # REQ-SEC-064's acceptance criterion without breaking anything visible.
        # nginx passes response headers through by default; the point of saying so
        # is that none of them may ever be suppressed here.
    }

    # The read-only GraphQL surface (REQ-API-006). \`= /graphql\` and not a prefix:
    # it is exactly one endpoint, and a prefix would also proxy \`/graphqlfoo\` —
    # which the SPA fallback below would otherwise have answered with the shell,
    # so the mistake would look like a working page rather than a 404.
    #
    # Its own location rather than a line in \`/api/\`, because it is not under
    # \`/api\`: 08 §8.1 puts it at the root beside it, and Spring for GraphQL's
    # default path is what a client library expects to find.
    location = /graphql {
        proxy_pass http://homeinv_api;
        proxy_http_version 1.1;

        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header Host $host;

        proxy_read_timeout ${READ_TIMEOUT};
        proxy_send_timeout ${READ_TIMEOUT};

        # A GraphQL response is one document and is not streamed. Buffering is
        # therefore left at nginx's default, unlike \`/api/\` — the difference is
        # the SSE stream, which lives there.
    }

    # Media answers on its own hostname (REQ-MED-010) and reaches the same
    # upstream. The separation that matters is the browser's: a response from
    # \`media.<host>\` runs in a different origin from the application, whatever
    # this proxy does. The application sets the headers for this path — the
    # attachment disposition, the sandbox and the resource policy — so this
    # location deliberately adds none of its own.
    location /media/ {
        proxy_pass http://homeinv_api;
        proxy_http_version 1.1;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Host $host;
        proxy_read_timeout ${READ_TIMEOUT};
    }

    # ---------------------------------------------------------------------
    # The shell
    # ---------------------------------------------------------------------
    # Hashed filenames, so a year is safe and a release invalidates by name.
    location /assets/ {
${headers('        add_header Cache-Control "public, max-age=31536000, immutable" always;')}
        try_files $uri =404;
    }

    # The shell itself is never cached: it names the hashed assets, and a stale
    # copy points at files that no longer exist.
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
