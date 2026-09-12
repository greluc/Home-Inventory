/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { useCallback, useEffect, useState } from "react";
import { ApiError, api, type Item, type Session } from "./api";
import { LoginForm } from "./LoginForm";
import { ItemList } from "./ItemList";
import { NewItemForm } from "./NewItemForm";
import { ThemeToggle } from "./ThemeToggle";

/**
 * The application shell.
 *
 * No router at stage 0. There are two screens behind the login and one of them is
 * a dialog; a router would be a dependency carrying its own history handling for
 * a navigation that does not exist yet.
 *
 * Whether anybody is logged in is asked of the server on start-up rather than
 * remembered in storage. The session lives on the server, and only the server
 * knows whether it is still valid — a remembered flag would show the application
 * to somebody whose session expired an hour ago, and every request would then
 * fail with a 401 nobody expected.
 */
export function App(): React.JSX.Element {
  const [session, setSession] = useState<Session | null>(null);
  const [checking, setChecking] = useState(true);
  const [items, setItems] = useState<Item[]>([]);
  const [query, setQuery] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .me()
      .then(setSession)
      .catch(() => setSession(null))
      .finally(() => setChecking(false));
  }, []);

  const reload = useCallback(
    async (text: string): Promise<void> => {
      try {
        const result = await api.search(text);
        setItems(result.items);
        setError(null);
      } catch (cause) {
        setError(describe(cause));
      }
    },
    [],
  );

  useEffect(() => {
    if (!session) {
      return;
    }
    // Debounced, and not for the user's benefit. This runs on every keystroke,
    // and each run is a full-text query against PostgreSQL on a machine that may
    // be a Raspberry Pi — typing "Bohrmaschine" unthrottled is twelve searches
    // for one answer. 250 ms is below the point where the list feels delayed and
    // above the interval between keystrokes.
    const timer = setTimeout(() => void reload(query), 250);
    return () => clearTimeout(timer);
  }, [session, query, reload]);

  if (checking) {
    // Nothing, not a spinner: the answer usually arrives in a few milliseconds,
    // and a spinner that appears and vanishes is worse than a moment of nothing.
    return <main className="shell" aria-busy="true" />;
  }

  if (!session) {
    return <LoginForm onAuthenticated={setSession} />;
  }

  return (
    <div className="shell">
      <header className="bar">
        <h1>Inventar</h1>
        <div className="bar-actions">
          <ThemeToggle />
          <span className="muted">{session.email}</span>
          <button
            type="button"
            onClick={() => {
              void api.logout().finally(() => setSession(null));
            }}
          >
            Abmelden
          </button>
        </div>
      </header>

      <main>
        <label className="search">
          <span className="visually-hidden">Suchen</span>
          <input
            type="search"
            value={query}
            placeholder="Suchen…"
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
          onCreated={() => {
            void reload(query);
          }}
          onError={setError}
        />

        <ItemList
          items={items}
          onDeleted={() => {
            void reload(query);
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
 * @returns the sentence to show
 */
function describe(cause: unknown): string {
  if (cause instanceof ApiError) {
    return cause.traceId ? `${cause.detail} (${cause.traceId})` : cause.detail;
  }
  return "Die Anfrage ist fehlgeschlagen. Besteht eine Verbindung?";
}
