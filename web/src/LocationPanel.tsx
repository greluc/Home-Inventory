/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import type { TFunction } from "i18next";
import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { ApiError, api, type Location, type LocationCategory } from "./api";

/**
 * The places things are kept.
 *
 * A flat list rather than a collapsible tree, and the readable path in each row.
 * The tree is what the data is; the path is what a person reads, and a household
 * with twenty locations is scanned faster as twenty lines than as four levels of
 * disclosure. The indent carries the shape without hiding anything.
 *
 * A shipped category comes from the server as a key and is translated here: those names are
 * interface text, and interface text lives in the resource bundle (REQ-NFR-032). A category the
 * tenant named carries its own labels and they win — that name is tenant data, not interface text,
 * and no bundle can contain it.
 */
export function LocationPanel({
  locations,
  categories,
  onCreated,
  onError,
}: {
  locations: Location[];
  categories: LocationCategory[];
  onCreated: () => void;
  onError: (message: string) => void;
}): React.JSX.Element {
  const { t, i18n } = useTranslation();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [chosenCategory, setChosenCategory] = useState("");
  const [parentId, setParentId] = useState("");
  const [busy, setBusy] = useState(false);

  const language = i18n.resolvedLanguage ?? i18n.language;
  const offered = useMemo(
    () => sorted(categories, t, language),
    [categories, t, language],
  );

  // Derived rather than stored, so that the categories arriving after the first
  // render do not need a second one to become selectable. A select whose value
  // is not one of its options shows an empty row, and the first submit then
  // fails on a field the user never touched.
  const categoryId = chosenCategory === "" ? (offered[0]?.id ?? "") : chosenCategory;

  async function submit(event: React.FormEvent): Promise<void> {
    event.preventDefault();
    setBusy(true);
    try {
      await api.createLocation({
        name,
        categoryId,
        // Omitted rather than undefined: with `exactOptionalPropertyTypes` the
        // two differ, and on the wire an absent field and a null one mean
        // different things.
        ...(parentId === "" ? {} : { parentId }),
      });
      setName("");
      setParentId("");
      setOpen(false);
      onCreated();
    } catch (cause) {
      onError(cause instanceof ApiError ? cause.detail : t("location.createFailed"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="locations">
      <div className="section-head">
        <h2>{t("location.heading")}</h2>
        {!open && (
          <button type="button" className="primary" onClick={() => setOpen(true)}>
            {t("location.new")}
          </button>
        )}
      </div>

      {open && (
        <form className="new-location" onSubmit={(event) => void submit(event)}>
          <label>
            {t("location.name")}
            <input
              value={name}
              onChange={(event) => setName(event.target.value)}
              maxLength={300}
              required
              autoFocus
            />
          </label>

          <label>
            {t("location.category")}
            <select
              value={categoryId}
              onChange={(event) => setChosenCategory(event.target.value)}
              required
            >
              {offered.map((category) => (
                <option key={category.id} value={category.id}>
                  {categoryName(category, t, language)}
                </option>
              ))}
            </select>
          </label>

          <label>
            {t("location.parent")}
            <select value={parentId} onChange={(event) => setParentId(event.target.value)}>
              <option value="">{t("location.root")}</option>
              {locations.map((location) => (
                <option key={location.id} value={location.id}>
                  {location.ancestors.join(" › ")}
                </option>
              ))}
            </select>
          </label>

          <div className="row">
            <button type="submit" className="primary" disabled={busy}>
              {t("action.create")}
            </button>
            <button type="button" className="ghost" onClick={() => setOpen(false)}>
              {t("action.cancel")}
            </button>
          </div>
        </form>
      )}

      {locations.length === 0 ? (
        <p className="muted">{t("location.none")}</p>
      ) : (
        <ul className="location-tree">
          {locations.map((location) => (
            <li key={location.id} style={{ paddingInlineStart: `${location.depth * 1.25}rem` }}>
              <span className="name">{location.name}</span>
              <span className="muted">{categoryNameOf(categories, location.categoryId, t, language)}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/**
 * The categories in the reader's alphabetical order.
 *
 * The server sends them in no meaningful order on purpose: thirteen translated
 * words sort differently in every language, and only this side knows which one is
 * being read.
 *
 * @param categories what the server sent
 * @param t the translator
 * @param language the reader's language tag
 * @returns a new array, sorted by the name actually shown
 */
function sorted(
  categories: LocationCategory[],
  t: TFunction,
  language: string,
): LocationCategory[] {
  const collator = new Intl.Collator(undefined, { sensitivity: "base" });
  return categories.toSorted((left, right) =>
    collator.compare(categoryName(left, t, language), categoryName(right, t, language)),
  );
}

/**
 * What to call a category.
 *
 * The tenant's own label for the reader's language if there is one, then its label for the bare
 * language (`de` for `de-AT`), then any label it has at all — a category named in one language is
 * better shown under that name than under a key nobody chose — and only then the translation of the
 * shipped key. A category the tenant never named has no labels, which is where every shipped one
 * starts, so this reduces to the translation for all thirteen of them.
 *
 * @param category the category
 * @param t the translator
 * @param language the reader's language tag
 * @returns the name to show
 */
function categoryName(category: LocationCategory, t: TFunction, language: string): string {
  const labels = category.labels ?? {};
  const primary = language.split("-")[0] ?? language;
  const own = labels[language] ?? labels[primary] ?? Object.values(labels)[0];
  return own ?? t(`location.categories.${category.key}`, category.key);
}

/**
 * What to call the category a location is of.
 *
 * @param categories the known categories
 * @param id the one a location references
 * @param t the translator
 * @param language the reader's language tag
 * @returns the name, or the id when the category is not on this page
 */
function categoryNameOf(
  categories: LocationCategory[],
  id: string,
  t: TFunction,
  language: string,
): string {
  const category = categories.find((candidate) => candidate.id === id);
  return category === undefined ? id : categoryName(category, t, language);
}
