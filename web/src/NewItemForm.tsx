/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ApiError, api, type Location } from "./api";

/**
 * Creating an item.
 *
 * Only the five fixed fields of stage 0 (REQ-CORE-002). There is no type picker
 * because there is no type system yet: the server fills in the tenant's built-in
 * type, and a picker showing one option would be a control that teaches the wrong
 * thing about what comes next.
 *
 * A physical item resides in exactly one location (REQ-CORE-003), so the location
 * picker appears when the kind is physical and the form refuses to submit without
 * one. The kind defaults to physical, because a household inventory is mostly
 * things — it defaulted to digital until there was a location picker to offer,
 * which was honest about the screen and wrong about the product.
 */
export function NewItemForm({
  locations,
  onCreated,
  onError,
}: {
  locations: Location[];
  onCreated: () => void;
  onError: (message: string) => void;
}): React.JSX.Element {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [kind, setKind] = useState<"PHYSICAL" | "DIGITAL">("PHYSICAL");
  const [locationId, setLocationId] = useState("");
  const [quantity, setQuantity] = useState("1");
  const [quantityUnit, setQuantityUnit] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(event: React.FormEvent): Promise<void> {
    event.preventDefault();
    if (kind === "PHYSICAL" && locationId === "") {
      onError(t("item.locationRequired"));
      return;
    }

    setBusy(true);
    try {
      // Keys are omitted rather than set to undefined. With
      // `exactOptionalPropertyTypes` the two are different things, and the
      // distinction is real on the wire: an absent field and a null one mean
      // different things to a JSON API.
      await api.createItem({
        name,
        kind,
        ...(description === "" ? {} : { description }),
        ...(kind === "PHYSICAL" ? { locationId } : {}),
        ...(quantity === "" ? {} : { quantity }),
        ...(quantityUnit === "" ? {} : { quantityUnit }),
      });
      setName("");
      setDescription("");
      setQuantityUnit("");
      setQuantity("1");
      setOpen(false);
      onCreated();
    } catch (cause) {
      onError(cause instanceof ApiError ? cause.detail : t("item.createFailed"));
    } finally {
      setBusy(false);
    }
  }

  if (!open) {
    return (
      <button type="button" className="primary" onClick={() => setOpen(true)}>
        {t("item.new")}
      </button>
    );
  }

  return (
    <form className="new-item" onSubmit={(event) => void submit(event)}>
      <label>
        {t("item.name")}
        <input
          value={name}
          onChange={(event) => setName(event.target.value)}
          maxLength={500}
          required
          autoFocus
        />
      </label>

      <label>
        {t("item.description")}
        <textarea
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          maxLength={20000}
          rows={2}
        />
      </label>

      <label>
        {t("item.kind")}
        <select
          value={kind}
          onChange={(event) => setKind(event.target.value === "DIGITAL" ? "DIGITAL" : "PHYSICAL")}
        >
          <option value="PHYSICAL">{t("item.kinds.PHYSICAL")}</option>
          <option value="DIGITAL">{t("item.kinds.DIGITAL")}</option>
        </select>
      </label>

      {kind === "PHYSICAL" && (
        <label>
          {t("item.location")}
          <select
            value={locationId}
            onChange={(event) => setLocationId(event.target.value)}
            required
          >
            <option value="" disabled>
              —
            </option>
            {locations.map((location) => (
              <option key={location.id} value={location.id}>
                {location.ancestors.join(" › ")}
              </option>
            ))}
          </select>
        </label>
      )}

      <div className="row">
        <label className="narrow">
          {t("item.quantity")}
          <input
            type="number"
            min="0"
            step="any"
            value={quantity}
            onChange={(event) => setQuantity(event.target.value)}
          />
        </label>

        <label className="narrow">
          {t("item.unit")}
          <input
            value={quantityUnit}
            onChange={(event) => setQuantityUnit(event.target.value)}
            maxLength={30}
          />
        </label>
      </div>

      <div className="row">
        <button type="submit" className="primary" disabled={busy}>
          {t("action.create")}
        </button>
        <button type="button" className="ghost" onClick={() => setOpen(false)}>
          {t("action.cancel")}
        </button>
      </div>
    </form>
  );
}
