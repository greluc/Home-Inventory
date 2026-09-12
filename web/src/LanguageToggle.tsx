/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { useTranslation } from "react-i18next";
import { LANGUAGES, chooseLanguage, isLanguage } from "./i18n";

/**
 * Switches between the shipped languages (REQ-NFR-033).
 *
 * A select and not a pair of buttons: two languages today, and REQ-NFR-032 makes
 * this an application that expects more. A control that has to be rebuilt when a
 * third arrives is a control built for the wrong number.
 *
 * The choice is remembered in this browser and wins over the language on the
 * profile — the profile says what the user generally prefers, this says what they
 * want on this device right now. Changing the profile is stage 1.
 */
export function LanguageToggle(): React.JSX.Element {
  const { t, i18n } = useTranslation();

  return (
    <label className="language">
      <span className="visually-hidden">{t("language.label")}</span>
      <select
        value={i18n.resolvedLanguage ?? "en"}
        onChange={(event) => {
          const chosen = event.target.value;
          if (isLanguage(chosen)) {
            chooseLanguage(chosen);
          }
        }}
      >
        {LANGUAGES.map((language) => (
          <option key={language} value={language}>
            {t(`language.${language}`)}
          </option>
        ))}
      </select>
    </label>
  );
}
