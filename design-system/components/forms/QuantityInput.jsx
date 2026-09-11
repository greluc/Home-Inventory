import React from "react";

export function QuantityInput({ value, unit = "Stk", units = ["Stk", "m", "kg", "l", "Pkg"], readOnly, disabled, ...rest }) {
  if (readOnly) {
    return (
      <div className={"hi-readonly " + (value == null || value === "" ? "hi-readonly--empty" : "")} style={{ fontVariantNumeric: "tabular-nums" }}>
        {value == null || value === "" ? "—" : `${value} ${unit}`}
      </div>
    );
  }
  return (
    <div className="hi-combo" data-disabled={disabled || undefined}>
      <input type="text" inputMode="decimal" className="hi-combo__num" defaultValue={value} disabled={disabled} placeholder="0" {...rest} />
      <span className="hi-combo__unit">
        <select defaultValue={unit} disabled={disabled} aria-label="Einheit">
          {units.map((u) => <option key={u} value={u}>{u}</option>)}
        </select>
      </span>
    </div>
  );
}
