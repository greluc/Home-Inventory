/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
// Before the first render: i18next has to be initialised when a component asks
// for a string, and every component does.
import "./i18n";
import "./styles.css";

/**
 * Installs the Trusted Types policy before anything can need it.
 *
 * `require-trusted-types-for 'script'` makes the browser refuse a string handed to
 * a DOM XSS sink, and the CSP names exactly one policy - `default` - so nothing
 * that achieves script execution can mint its own and pass the check (ADR-0038).
 *
 * The policy below creates no HTML at all: it exists so that a library reaching
 * for `innerHTML` fails loudly here rather than being silently allowed. React sets
 * text through the DOM API and never needs it, which is why the body throws.
 */
function installTrustedTypes(): void {
  const tt = (window as unknown as { trustedTypes?: { createPolicy: (n: string, r: object) => void } })
    .trustedTypes;
  if (!tt) {
    return; // Not every browser has it; the CSP is then one defence short, not broken.
  }
  try {
    tt.createPolicy("default", {
      createHTML: (): string => {
        throw new TypeError(
          "This application assigns no HTML. Something tried to, which is either a bug or an attack.",
        );
      },
      createScriptURL: (): string => {
        throw new TypeError("This application loads no script URLs at runtime.");
      },
      createScript: (): string => {
        throw new TypeError("This application evaluates no script at runtime.");
      },
    });
  } catch {
    // A second call throws; a hot reload in development is the usual cause.
  }
}

installTrustedTypes();

const container = document.getElementById("root");
if (!container) {
  throw new Error("The root element is missing from index.html");
}

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
