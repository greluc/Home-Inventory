/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";

import { version } from "./package.json";

/**
 * Which third-party packages end up **in** the bundle (`REQ-CON-013`).
 *
 * The licence notice this client ships has to list what the bundle carries, and
 * the dependency tree is a different list: `npm ls --omit=dev` resolves
 * `typescript` here, because `i18next` and `react-i18next` declare it as a peer
 * dependency, and no line of it reaches a browser. A notice that named it would
 * be describing a build machine rather than the artifact.
 *
 * So the answer comes from Rollup, which knows exactly which modules it put
 * into the chunks it wrote. `tools/notices.py` reads this file and takes the
 * licences from the SBOM beside it.
 */
function bundledPackages(): Plugin {
  const packages = new Set<string>();
  return {
    name: "home-inv-bundled-packages",
    generateBundle(_options, bundle) {
      for (const chunk of Object.values(bundle)) {
        if (chunk.type !== "chunk") {
          continue;
        }
        for (const id of Object.keys(chunk.modules)) {
          // The LAST `node_modules` in the path: a package that vendors another
          // one nests them, and the innermost is the one the module belongs to.
          const marker = id.lastIndexOf("node_modules/");
          if (marker < 0) {
            continue;
          }
          const [first, second] = id.slice(marker + "node_modules/".length).split("/");
          if (first === undefined) {
            continue;
          }
          packages.add(first.startsWith("@") && second !== undefined ? `${first}/${second}` : first);
        }
      }
      this.emitFile({
        type: "asset",
        fileName: "bundled-packages.json",
        source: `${JSON.stringify([...packages].toSorted(), null, 2)}\n`,
      });
    },
  };
}

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
  plugins: [react(), bundledPackages()],
  // The client names itself to the server in `X-Home-Inv-Client` (REQ-API-009),
  // and the version has to come from somewhere the build controls: a browser
  // will not let a page set `User-Agent`, which is why ADR-0011's original
  // arrangement could not be honoured here.
  define: {
    APP_VERSION: JSON.stringify(version),
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
