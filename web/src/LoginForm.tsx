/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ApiError, api, type Session } from "./api";
import { LanguageToggle } from "./LanguageToggle";
import { passkeysAvailable, provePasskey } from "./passkeys";
import { ThemeToggle } from "./ThemeToggle";

/**
 * The login screen.
 *
 * The error message is the same for every failure, because the server answers the
 * same way for every failure: unknown address, wrong password, locked account
 * (REQ-SEC-110). Showing a more specific message here would defeat the point of
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
  const { t } = useTranslation();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [needsCode, setNeedsCode] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: React.FormEvent): Promise<void> {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onAuthenticated(await api.login(email, password));
    } catch (cause) {
      if (cause instanceof ApiError && cause.type.endsWith("/second-factor-required")) {
        // Not a failure: the password was right and the account is protected.
        // The pending login lives on the server for five minutes (REQ-AUTH-002).
        setNeedsCode(true);
      } else if (cause instanceof ApiError && cause.status === 429) {
        setError(t("login.throttled"));
      } else {
        setError(t("login.failed"));
      }
    } finally {
      setBusy(false);
    }
  }

  async function submitCode(event: React.FormEvent): Promise<void> {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onAuthenticated(await api.completeSecondFactor(code));
    } catch (cause) {
      // One message for a wrong code, a spent one and a window that ran out: the
      // server answers them the same way and so does this.
      setError(t("login.codeFailed"));
      setCode("");
      if (cause instanceof ApiError && cause.status !== 401) {
        setNeedsCode(false);
      }
    } finally {
      setBusy(false);
    }
  }

  async function submitPasskey(): Promise<void> {
    setBusy(true);
    setError(null);
    try {
      const ceremony = await api.passkeyChallenge();
      onAuthenticated(await api.completeWithPasskey(await provePasskey(ceremony.options)));
    } catch {
      // Whether the authenticator refused, the browser cancelled or the server
      // rejected the assertion, the answer here is the same: try again, or use a
      // code. Telling them apart would say whether this account has passkeys.
      setError(t("login.passkeyFailed"));
    } finally {
      setBusy(false);
    }
  }

  if (needsCode) {
    return (
      <main className="login">
        <div className="login-card">
          <div className="login-head">
            <h1>{t("app.title")}</h1>
            <div className="bar-actions">
              <LanguageToggle />
              <ThemeToggle />
            </div>
          </div>

          <p>{t("login.codeHint")}</p>

          <form onSubmit={(event) => void submitCode(event)}>
            <label>
              {t("login.code")}
              <input
                type="text"
                value={code}
                onChange={(event) => setCode(event.target.value)}
                inputMode="numeric"
                autoComplete="one-time-code"
                autoFocus
                required
              />
            </label>

            {error !== null && <p role="alert">{error}</p>}

            <button type="submit" disabled={busy}>
              {busy ? t("login.working") : t("login.submitCode")}
            </button>
          </form>

          {passkeysAvailable() && (
            <button type="button" disabled={busy} onClick={() => void submitPasskey()}>
              {t("login.usePasskey")}
            </button>
          )}
        </div>
      </main>
    );
  }

  return (
    <main className="login">
      <div className="login-card">
        <div className="login-head">
          <h1>{t("app.title")}</h1>
          <div className="bar-actions">
            <LanguageToggle />
            <ThemeToggle />
          </div>
        </div>

        <form onSubmit={(event) => void submit(event)}>
          <label>
            {t("login.email")}
            <input
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              autoComplete="username"
              required
            />
          </label>

          <label>
            {t("login.password")}
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
            {busy ? t("login.working") : t("login.submit")}
          </button>
        </form>
      </div>
    </main>
  );
}
