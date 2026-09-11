/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { useState } from "react";
import { ApiError, api } from "./api";

/**
 * Creating an item.
 *
 * Only the five fixed fields of stage 0 (REQ-CORE-002). There is no type picker
 * because there is no type system yet: the server fills in the tenant built-in
 * type, and a picker showing one option would be a control that teaches the wrong
 * thing about what comes next.
 *
 * A physical item needs a location and the server refuses one without (a 422 with
 * a field path). Stage 0 has no location picker in this form yet, so the kind
 * defaults to digital - which is honest about what this screen can currently do
 * rather than offering a choice that fails on submit.
 */
export function NewItemForm({
  onCreated,
  onError,
}: {
  onCreated: () => void;
  onError: (message: string) => void;
}): React.JSX.Element {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(event: React.FormEvent): Promise<void> {
    event.preventDefault();
    setBusy(true);
    try {
      // The key is omitted rather than set to undefined. With
      // `exactOptionalPropertyTypes` the two are different things, and the
      // distinction is real on the wire: an absent field and a null one mean
      // different things to a JSON API.
      await api.createItem({
        name,
        kind: "DIGITAL",
        ...(description === "" ? {} : { description }),
      });
      setName("");
      setDescription("");
      setOpen(false);
      onCreated();
    } catch (cause) {
      onError(cause instanceof ApiError ? cause.detail : "Anlegen fehlgeschlagen.");
    } finally {
      setBusy(false);
    }
  }

  if (!open) {
    return (
      <button type="button" className="primary" onClick={() => setOpen(true)}>
        Gegenstand anlegen
      </button>
    );
  }

  return (
    <form className="new-item" onSubmit={(event) => void submit(event)}>
      <label>
        Name
        <input
          value={name}
          onChange={(event) => setName(event.target.value)}
          maxLength={500}
          required
          autoFocus
        />
      </label>

      <label>
        Beschreibung
        <textarea
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          maxLength={20000}
          rows={2}
        />
      </label>

      <div className="row">
        <button type="submit" className="primary" disabled={busy}>
          Anlegen
        </button>
        <button type="button" className="ghost" onClick={() => setOpen(false)}>
          Abbrechen
        </button>
      </div>
    </form>
  );
}
