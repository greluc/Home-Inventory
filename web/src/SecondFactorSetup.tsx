/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ApiError, api, type TotpEnrolment } from "./api";
import { createPasskey, passkeysAvailable } from "./passkeys";

/**
 * Setting up the second factor an OWNER or ADMIN cannot work without (REQ-AUTH-003).
 *
 * The role is granted and locked: the membership exists, and every request in the tenant is
 * refused until an authenticator does too. That is what the operator meets on a fresh deployment,
 * because the one-shot that creates the first owner has nobody to ask for a code — so this screen
 * is the first thing they see, and it is the way out rather than a message about one.
 *
 * The secret is shown as text and as an `otpauth://` link. No QR code: rendering one needs a
 * dependency this client does not have, and every authenticator app takes a typed secret. The link
 * is what a phone browser opens directly into the app.
 *
 * The recovery codes are shown once, here, because that is the only moment anything can show them —
 * they are stored as hashes.
 */
export function SecondFactorSetup({
  onEnrolled,
}: {
  onEnrolled: () => void;
}): React.JSX.Element {
  const { t } = useTranslation();
  const [enrolment, setEnrolment] = useState<TotpEnrolment | null>(null);
  const [code, setCode] = useState("");
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function registerPasskey(): Promise<void> {
    setBusy(true);
    setError(null);
    try {
      const ceremony = await api.beginPasskey();
      await api.confirmPasskey(await createPasskey(ceremony.options), t("mfa.passkeyLabel"));
      onEnrolled();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.detail : t("mfa.passkeyFailed"));
    } finally {
      setBusy(false);
    }
  }

  async function begin(): Promise<void> {
    setBusy(true);
    setError(null);
    try {
      setEnrolment(await api.beginTotpEnrolment());
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.detail : t("error.network"));
    } finally {
      setBusy(false);
    }
  }

  async function confirm(event: React.FormEvent): Promise<void> {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      setRecoveryCodes((await api.confirmTotpEnrolment(code)).codes);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.detail : t("error.network"));
      setCode("");
    } finally {
      setBusy(false);
    }
  }

  if (recoveryCodes !== null) {
    return (
      <main className="login">
        <div className="login-card">
          <h1>{t("mfa.recoveryTitle")}</h1>
          <p>{t("mfa.recoveryHint")}</p>
          <ul className="recovery-codes">
            {recoveryCodes.map((recoveryCode) => (
              <li key={recoveryCode}>
                <code>{recoveryCode}</code>
              </li>
            ))}
          </ul>
          <button type="button" onClick={onEnrolled}>
            {t("mfa.continue")}
          </button>
        </div>
      </main>
    );
  }

  return (
    <main className="login">
      <div className="login-card">
        <h1>{t("mfa.title")}</h1>
        <p>{t("mfa.why")}</p>

        {enrolment === null ? (
          <>
            {error !== null && <p role="alert">{error}</p>}
            <button type="button" disabled={busy} onClick={() => void begin()}>
              {busy ? t("mfa.working") : t("mfa.begin")}
            </button>
            {passkeysAvailable() && (
              <button type="button" disabled={busy} onClick={() => void registerPasskey()}>
                {t("mfa.usePasskey")}
              </button>
            )}
          </>
        ) : (
          <>
            <p>{t("mfa.secretHint")}</p>
            <p>
              <code>{enrolment.secret}</code>
            </p>
            <p>
              <a href={enrolment.provisioningUri}>{t("mfa.openInApp")}</a>
            </p>

            <form onSubmit={(event) => void confirm(event)}>
              <label>
                {t("mfa.code")}
                <input
                  type="text"
                  value={code}
                  onChange={(event) => setCode(event.target.value)}
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  required
                />
              </label>

              {error !== null && <p role="alert">{error}</p>}

              <button type="submit" disabled={busy}>
                {busy ? t("mfa.working") : t("mfa.confirm")}
              </button>
            </form>
          </>
        )}
      </div>
    </main>
  );
}
