/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

import { version } from "./package.json";

/**
 * The build.
 *
 * Two settings are security decisions rather than preferences:
 *
 * - `cssCodeSplit: false` puts every rule in one stylesheet. With splitting, Vite
 *   injects `<style>` elements at runtime, and a CSP without `unsafe-inline`
 *   blocks them — the page then renders unstyled, which looks like a layout bug
 *   and is a policy working correctly (REQ-SEC-060, ADR-0038).
 * - No CDN, anywhere. Fonts and icons are bundled from `design-system/`, never
 *   fetched (REQ-PRIV-015). There is nothing here that would load one, and the
 *   CSP would block it if there were.
 */
export default defineConfig({
  plugins: [react()],
  // The client names itself to the server in `X-Home-Inv-Client` (REQ-API-009),
  // and the version has to come from somewhere the build controls: a browser
  // will not let a page set `User-Agent`, which is why ADR-0011's original
  // arrangement could not be honoured here.
  define: {
    __APP_VERSION__: JSON.stringify(version),
  },
  build: {
    cssCodeSplit: false,
    // Named, hashed files so a deployment can cache them for a year and a new
    // release invalidates only what changed.
    rollupOptions: {
      output: {
        entryFileNames: "assets/[name]-[hash].js",
        chunkFileNames: "assets/[name]-[hash].js",
        assetFileNames: "assets/[name]-[hash][extname]",
      },
    },
  },
  server: {
    proxy: {
      // Development only. In production `web` and `api` are separate containers
      // behind one hostname, and this proxy does not exist.
      "/api": { target: "http://localhost:8080", changeOrigin: false },
    },
  },
});
