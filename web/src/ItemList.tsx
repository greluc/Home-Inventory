/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { ApiError, api, type Item } from "./api";

/**
 * The list of items.
 *
 * Dense on purpose: inventory means long lists, and a screen showing eight rows
 * where it could show twenty is a worse screen (the design brief says so in as
 * many words).
 *
 * Text is rendered as text and never as HTML. `dangerouslySetInnerHTML` is
 * forbidden here (REQ-SEC-033) and the Trusted Types policy installed in
 * `main.tsx` would throw if anything tried — so an item named `<img onerror=...>`
 * is a peculiar name and nothing more.
 */
export function ItemList({
  items,
  onDeleted,
  onError,
}: {
  items: Item[];
  onDeleted: () => void;
  onError: (message: string) => void;
}): React.JSX.Element {
  if (items.length === 0) {
    return <p className="muted">Nichts gefunden.</p>;
  }

  async function remove(item: Item): Promise<void> {
    try {
      await api.deleteItem(item.id);
      onDeleted();
    } catch (cause) {
      onError(cause instanceof ApiError ? cause.detail : "Löschen fehlgeschlagen.");
    }
  }

  return (
    <table className="items">
      <caption className="visually-hidden">Gefundene Gegenstände</caption>
      <thead>
        <tr>
          <th scope="col">Name</th>
          <th scope="col">Art</th>
          <th scope="col" className="numeric">Menge</th>
          <th scope="col"><span className="visually-hidden">Aktionen</span></th>
        </tr>
      </thead>
      <tbody>
        {items.map((item) => (
          <tr key={item.id}>
            <td>
              <span className="name">{item.name}</span>
              {item.description !== null && item.description !== "" && (
                <span className="muted description">{item.description}</span>
              )}
            </td>
            <td>{item.kind === "PHYSICAL" ? "Physisch" : "Digital"}</td>
            <td className="numeric">
              {item.quantity}
              {item.quantityUnit !== null ? ` ${item.quantityUnit}` : ""}
            </td>
            <td>
              <button type="button" className="ghost" onClick={() => void remove(item)}>
                Löschen
              </button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
