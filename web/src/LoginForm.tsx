/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { useState } from "react";
import { ApiError, api, type Session } from "./api";
import { ThemeToggle } from "./ThemeToggle";

/**
 * The login screen.
 *
 * The error message is the same for every failure, because the server answers the
 * same way for every failure: unknown address, wrong password, locked account
 * (REQ-SEC-016). Showing a more specific message here would defeat the point of
 * the server being careful about it.
 *
 * A 429 is the one that is told apart, and it has to be: the caller needs to know
 * that waiting helps. A client that cannot tell "wrong password" from "too fast"
 * retries immediately and makes the delay grow.
 */
export function LoginForm({
  onAuthenticated,
}: {
  onAuthenticated: (session: Session) => void;
}): React.JSX.Element {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: React.FormEvent): Promise<void> {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onAuthenticated(await api.login(email, password));
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 429) {
        setError("Zu viele Versuche. Bitte kurz warten.");
      } else {
        setError("E-Mail-Adresse oder Passwort stimmen nicht.");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login">
      <div className="login-card">
        <div className="login-head">
          <h1>Home Inventory</h1>
          <ThemeToggle />
        </div>

        <form onSubmit={(event) => void submit(event)}>
          <label>
            E-Mail
            <input
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              autoComplete="username"
              required
            />
          </label>

          <label>
            Passwort
            <input
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="current-password"
              required
            />
          </label>

          {error !== null && (
            <p className="error" role="alert">
              {error}
            </p>
          )}

          <button type="submit" disabled={busy}>
            {busy ? "Anmelden…" : "Anmelden"}
          </button>
        </form>
      </div>
    </main>
  );
}
