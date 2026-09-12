import { createInstance } from "i18next";
import { describe, expect, it } from "vitest";

import en from "./en.json";

/**
 * The bundles ship with the application, so initialisation has nothing to fetch and must not
 * behave as if it did.
 *
 * This existed as `initImmediate: false` in `index.ts` until i18next 26 removed the option —
 * synchronous initialisation is the default for a bundled resource set now. That is a behaviour
 * the application depends on and an upstream default it does not control, which is exactly the
 * kind of pair worth a test: if a later version makes `init` asynchronous again, the first paint
 * shows translation keys instead of words, and nothing else here would notice.
 */
describe("i18n initialisation", () => {
  it("resolves synchronously, so the first paint has words rather than keys", () => {
    const i18n = createInstance();

    // Deliberately not awaited. The assertions below run on the next line, which
    // is the whole point — an implementation that needed the promise would fail
    // here rather than in a browser.
    void i18n.init({
      resources: { en: { translation: en } },
      lng: "en",
      fallbackLng: "en",
      nsSeparator: false,
      interpolation: { escapeValue: false },
    });

    expect(i18n.language).toBe("en");
    expect(i18n.t("app.title")).toBe("Home Inventory");
  });

  it("reads a nested key as a path, not as a namespace", () => {
    const i18n = createInstance();
    void i18n.init({
      resources: { en: { translation: en } },
      lng: "en",
      fallbackLng: "en",
      // Without this, i18next reads the colon in a key as a namespace separator.
      // The bundles are one plain nested object and a key is a path into it.
      nsSeparator: false,
      interpolation: { escapeValue: false },
    });

    expect(i18n.t("language.label")).toBe("Language");
  });
});
