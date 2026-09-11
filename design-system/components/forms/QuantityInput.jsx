import React from "react";

export function QuantityInput({ value, unit = "pcs", units = ["pcs", "m", "kg", "l", "pkg"], readOnly, disabled, unitLabel = "Unit", ...rest }) {
  if (readOnly) {
    return (
      <div className={"hi-readonly " + (value == null || value === "" ? "hi-readonly--empty" : "")} style={{ fontVariantNumeric: "tabular-nums" }}>
        {value == null || value === "" ? "—" : `${value} ${unit}`}
      </div>
    );
  }
  return (
    <div className="hi-combo" data-disabled={disabled || undefined}>
      <input type="text" inputMode="decimal" className="hi-combo__num" defaultValue={value} disabled={disabled} {...rest} />
      <span className="hi-combo__unit">
        <select defaultValue={unit} disabled={disabled} aria-label={unitLabel}>
          {units.map((u) => <option key={u} value={u}>{u}</option>)}
        </select>
      </span>
    </div>
  );
}
