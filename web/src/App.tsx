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
import { AboutFooter } from "./AboutFooter";
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
        setItems(result.data);
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
        setLocations(tree.data);
        setCategories(kinds.data);
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
    void reloadPlaces();
  }, [session, reloadPlaces]);

  useEffect(() => {
    if (!session) {
      return;
    }
    const language = i18n.resolvedLanguage ?? "en";
    const timer = setTimeout(() => void reload(query, language), 250);
    return () => clearTimeout(timer);
  }, [session, query, reload, i18n.resolvedLanguage]);

  if (checking) {
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

      <AboutFooter />
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
