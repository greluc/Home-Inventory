/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import type { TFunction } from "i18next";
import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  ApiError,
  api,
  type Item,
  type Location,
  type LocationCategory,
  type Session,
} from "./api";
import { adoptProfileLanguage } from "./i18n";
import { LanguageToggle } from "./LanguageToggle";
import { LocationPanel } from "./LocationPanel";
import { LoginForm } from "./LoginForm";
import { ItemList } from "./ItemList";
import { NewItemForm } from "./NewItemForm";
import { SecondFactorSetup } from "./SecondFactorSetup";
import { ThemeToggle } from "./ThemeToggle";

/**
 * The application shell.
 *
 * No router at stage 0. There are two panels behind the login and the forms are
 * inline; a router would be a dependency carrying its own history handling for a
 * navigation that does not exist yet.
 *
 * Whether anybody is logged in is asked of the server on start-up rather than
 * remembered in storage. The session lives on the server, and only the server
 * knows whether it is still valid — a remembered flag would show the application
 * to somebody whose session expired an hour ago, and every request would then
 * fail with a 401 nobody expected.
 */
export function App(): React.JSX.Element {
  const { t, i18n } = useTranslation();
  const [session, setSession] = useState<Session | null>(null);
  const [checking, setChecking] = useState(true);
  const [items, setItems] = useState<Item[]>([]);
  const [locations, setLocations] = useState<Location[]>([]);
  const [categories, setCategories] = useState<LocationCategory[]>([]);
  const [query, setQuery] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [locked, setLocked] = useState(false);

  const signedIn = useCallback((who: Session): void => {
    setSession(who);
    adoptProfileLanguage(who.locale);
  }, []);

  useEffect(() => {
    api
      .me()
      .then(signedIn)
      .catch(() => setSession(null))
      .finally(() => setChecking(false));
  }, [signedIn]);

  // REQ-AUTH-003: an OWNER or ADMIN with no second factor holds the role and may
  // not use it. Every request answers `second-factor-missing` until they enrol,
  // so the shell watches for that one type and shows the way out rather than an
  // error message repeated on every panel.
  const noteLock = useCallback((cause: unknown): boolean => {
    if (cause instanceof ApiError && cause.type.endsWith("/second-factor-missing")) {
      setLocked(true);
      return true;
    }
    return false;
  }, []);

  const reload = useCallback(
    async (text: string, language: string): Promise<void> => {
      try {
        const result = await api.search(text, language);
        setItems(result.items);
        setError(null);
      } catch (cause) {
        if (!noteLock(cause)) {
          setError(describe(cause, t));
        }
      }
    },
    [t, noteLock],
  );

  const reloadPlaces = useCallback((): Promise<void> => {
    return Promise.all([api.locations(), api.locationCategories()])
      .then(([tree, kinds]) => {
        setLocations(tree.items);
        setCategories(kinds.items);
        return undefined;
      })
      .catch((cause: unknown) => {
        if (!noteLock(cause)) {
          setError(describe(cause, t));
        }
      });
  }, [t, noteLock]);

  useEffect(() => {
    if (!session) {
      return;
    }
    // The request is the synchronisation with the outside world; the state is
    // set when it answers, which is a render later and not a cascading one.
    void reloadPlaces();
  }, [session, reloadPlaces]);

  useEffect(() => {
    if (!session) {
      return;
    }
    // Debounced, and not for the user's benefit. This runs on every keystroke,
    // and each run is a full-text query against PostgreSQL on a machine that may
    // be a Raspberry Pi — typing "Bohrmaschine" unthrottled is twelve searches
    // for one answer. 250 ms is below the point where the list feels delayed and
    // above the interval between keystrokes.
    //
    // The language goes with the query: the item's text is indexed in both, and
    // which stemmer is used decides whether "drills" finds "drill" (ADR-0047).
    const language = i18n.resolvedLanguage ?? "en";
    const timer = setTimeout(() => void reload(query, language), 250);
    return () => clearTimeout(timer);
  }, [session, query, reload, i18n.resolvedLanguage]);

  if (checking) {
    // Nothing, not a spinner: the answer usually arrives in a few milliseconds,
    // and a spinner that appears and vanishes is worse than a moment of nothing.
    return <main className="shell" aria-busy="true" />;
  }

  if (!session) {
    return <LoginForm onAuthenticated={signedIn} />;
  }

  if (locked) {
    return (
      <SecondFactorSetup
        onEnrolled={() => {
          setLocked(false);
          setError(null);
          void reloadPlaces();
          void reload(query, i18n.resolvedLanguage ?? "en");
        }}
      />
    );
  }

  return (
    <div className="shell">
      <header className="bar">
        <h1>{t("app.inventory")}</h1>
        <div className="bar-actions">
          <LanguageToggle />
          <ThemeToggle />
          <span className="muted">{session.email}</span>
          <button
            type="button"
            onClick={() => {
              void api.logout().finally(() => setSession(null));
            }}
          >
            {t("session.signOut")}
          </button>
        </div>
      </header>

      <main>
        <label className="search">
          <span className="visually-hidden">{t("search.label")}</span>
          <input
            type="search"
            value={query}
            placeholder={t("search.placeholder")}
            onChange={(event) => setQuery(event.target.value)}
            autoComplete="off"
          />
        </label>

        {error !== null && (
          <p className="error" role="alert">
            {error}
          </p>
        )}

        <NewItemForm
          locations={locations}
          onCreated={() => {
            void reload(query, i18n.resolvedLanguage ?? "en");
          }}
          onError={setError}
        />

        <ItemList
          items={items}
          locations={locations}
          onDeleted={() => {
            void reload(query, i18n.resolvedLanguage ?? "en");
          }}
          onError={setError}
        />

        <LocationPanel
          locations={locations}
          categories={categories}
          onCreated={() => {
            void reloadPlaces();
          }}
          onError={setError}
        />
      </main>
    </div>
  );
}

/**
 * Turns a failure into a sentence a person can read.
 *
 * An {@link ApiError} already carries one written by the server, and it is used as
 * it stands — the server knows why it refused and the client does not. Anything
 * else gets a generic sentence, because an exception message from a browser is
 * not written for users.
 *
 * @param cause whatever was thrown
 * @param t the translator
 * @returns the sentence to show
 */
function describe(cause: unknown, t: TFunction): string {
  if (cause instanceof ApiError) {
    return cause.traceId
      ? t("error.withTrace", { detail: cause.detail, traceId: cause.traceId })
      : cause.detail;
  }
  return t("error.network");
}
