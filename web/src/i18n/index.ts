/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import i18next, { changeLanguage, init, use } from "i18next";
import { initReactI18next } from "react-i18next";
import de from "./de.json";
import en from "./en.json";

/**
 * The two languages this application ships (REQ-NFR-033).
 *
 * English is first because it is the fallback: a key missing from the German
 * bundle falls back to the English text rather than showing the key, which is the
 * difference between an untranslated screen and a broken one.
 */
export const LANGUAGES = ["en", "de"] as const;

/** A language this application has a bundle for. */
export type Language = (typeof LANGUAGES)[number];

/** Where a manual choice is remembered between visits. */
const STORED = "homeinv.language";

/**
 * Whether a string names a language this application ships.
 *
 * @param value anything
 * @returns true when there is a bundle for it
 */
export function isLanguage(value: unknown): value is Language {
  return typeof value === "string" && (LANGUAGES as readonly string[]).includes(value);
}

/**
 * The language to start in, before the session is known.
 *
 * A manual choice wins over the browser, because it was made here and on purpose.
 * The browser's preference is read as a language tag — `de-AT` is German — and
 * anything else falls back to English, which is what REQ-NFR-033 calls for.
 *
 * @returns the language to initialise with
 */
function initialLanguage(): Language {
  try {
    const stored = localStorage.getItem(STORED);
    if (isLanguage(stored)) {
      return stored;
    }
  } catch {
    // A private window may refuse storage. The browser's preference still
    // applies; only the memory of a manual choice is lost.
  }

  for (const preferred of navigator.languages ?? [navigator.language]) {
    const base = preferred.split("-")[0];
    if (isLanguage(base)) {
      return base;
    }
  }
  return "en";
}

/**
 * Switches the language and remembers the choice.
 *
 * @param language the language to switch to
 */
export function chooseLanguage(language: Language): void {
  void changeLanguage(language);
  document.documentElement.setAttribute("lang", language);
  try {
    localStorage.setItem(STORED, language);
  } catch {
    // As above: the choice applies to this page and is not remembered.
  }
}

/**
 * Adopts the language stored on the user's profile.
 *
 * Called once when the session arrives. A choice made in this browser wins: the
 * profile says what the user prefers in general, and the switch in the bar says
 * what they want right now, on this device. Changing the profile is stage 1.
 *
 * @param locale the `locale` of the signed-in user
 */
export function adoptProfileLanguage(locale: string): void {
  try {
    if (isLanguage(localStorage.getItem(STORED))) {
      return;
    }
  } catch {
    // Storage refused; fall through and use the profile.
  }
  const base = locale.split("-")[0];
  if (isLanguage(base)) {
    void changeLanguage(base);
    document.documentElement.setAttribute("lang", base);
  }
}

use(initReactI18next);
void init({
  resources: {
    en: { translation: en },
    de: { translation: de },
  },
  lng: initialLanguage(),
  fallbackLng: "en",
  // The bundles are a plain nested object, and a key is a path into it. Without
  // this, i18next would treat a colon in a key as a namespace separator.
  nsSeparator: false,
  interpolation: {
    // React escapes what it renders, and escaping twice turns an apostrophe into
    // `&#39;` on screen.
    escapeValue: false,
  },
  // The bundles ship with the application; there is nothing to fetch and nothing
  // to wait for, which is what keeps the first paint free of a flash of keys.
  initImmediate: false,
});

document.documentElement.setAttribute("lang", i18next.language);

export default i18next;
