/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ApiError, api, type Item, type Location } from "./api";
import { formatTimestamp } from "./format";
import { ItemPhotos } from "./ItemPhotos";

/**
 * The list of items.
 *
 * Dense on purpose: inventory means long lists, and a screen showing eight rows
 * where it could show twenty is a worse screen (the design brief says so in as
 * many words). The photographs of a row appear when it is expanded, so a list of
 * twenty does not fetch twenty sets of signed URLs to show thumbnails nobody
 * asked for.
 *
 * Text is rendered as text and never as HTML. `dangerouslySetInnerHTML` is
 * forbidden here (REQ-SEC-033) and the Trusted Types policy installed in
 * `main.tsx` would throw if anything tried — so an item named `<img onerror=...>`
 * is a peculiar name and nothing more.
 *
 * Times are stored in UTC and shown in the reader's zone and language, through
 * `Intl.DateTimeFormat`, which reads both from the browser (REQ-NFR-035).
 */
export function ItemList({
  items,
  locations,
  onDeleted,
  onError,
}: {
  items: Item[];
  locations: Location[];
  onDeleted: () => void;
  onError: (message: string) => void;
}): React.JSX.Element {
  const { t, i18n } = useTranslation();
  const [expanded, setExpanded] = useState<string | null>(null);

  if (items.length === 0) {
    return <p className="muted">{t("item.none")}</p>;
  }

  async function remove(item: Item): Promise<void> {
    try {
      await api.deleteItem(item.id, item.version);
      onDeleted();
    } catch (cause) {
      onError(cause instanceof ApiError ? cause.detail : t("item.deleteFailed"));
    }
  }

  return (
    <table className="items">
      <caption className="visually-hidden">{t("item.listCaption")}</caption>
      <thead>
        <tr>
          <th scope="col">{t("item.name")}</th>
          <th scope="col">{t("item.kind")}</th>
          <th scope="col">{t("item.location")}</th>
          <th scope="col" className="numeric">
            {t("item.quantity")}
          </th>
          <th scope="col">{t("item.created")}</th>
          <th scope="col">
            <span className="visually-hidden">{t("item.actions")}</span>
          </th>
        </tr>
      </thead>
      <tbody>
        {items.map((item) => (
          <Row
            key={item.id}
            item={item}
            locations={locations}
            expanded={expanded === item.id}
            language={i18n.resolvedLanguage ?? "en"}
            onToggle={() => setExpanded(expanded === item.id ? null : item.id)}
            onRemove={() => void remove(item)}
            onError={onError}
          />
        ))}
      </tbody>
    </table>
  );
}

/**
 * One item, and its photographs when it is expanded.
 *
 * @param props the row's item, the known locations, and what to do about it
 * @returns the two table rows this item occupies
 */
function Row({
  item,
  locations,
  expanded,
  language,
  onToggle,
  onRemove,
  onError,
}: {
  item: Item;
  locations: Location[];
  expanded: boolean;
  language: string;
  onToggle: () => void;
  onRemove: () => void;
  onError: (message: string) => void;
}): React.JSX.Element {
  const { t } = useTranslation();
  const location = locations.find((candidate) => candidate.id === item.locationId);

  return (
    <>
      <tr>
        <td>
          <span className="name">{item.name}</span>
          {item.description !== null && item.description !== "" && (
            <span className="muted description">{item.description}</span>
          )}
        </td>
        <td>{t(`item.kinds.${item.kind}`)}</td>
        <td className="muted">{location ? location.ancestors.join(" › ") : ""}</td>
        <td className="numeric">
          {item.quantity}
          {item.quantityUnit !== null ? ` ${item.quantityUnit}` : ""}
        </td>
        <td className="muted">
          <time dateTime={item.createdAt}>{formatTimestamp(item.createdAt, language)}</time>
        </td>
        <td className="row-actions">
          <button type="button" className="ghost" aria-expanded={expanded} onClick={onToggle}>
            {t("item.photos")}
          </button>
          <button type="button" className="ghost" onClick={onRemove}>
            {t("action.delete")}
          </button>
        </td>
      </tr>
      {expanded && (
        <tr className="expansion">
          <td colSpan={6}>
            <ItemPhotos itemId={item.id} itemName={item.name} onError={onError} />
          </td>
        </tr>
      )}
    </>
  );
}
