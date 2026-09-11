import React from "react";
import { Icon } from "../foundation/Icon.jsx";

export function Select({ options = [], value, placeholder = "Please choose", readOnly, disabled, className = "", ...rest }) {
  if (readOnly) {
    const hit = options.find((o) => (o.value ?? o) === value);
    const label = hit ? (hit.label ?? hit) : null;
    return <div className={"hi-readonly " + (label ? "" : "hi-readonly--empty")}>{label ?? "—"}</div>;
  }
  return (
    <span className="hi-selectwrap">
      <select className={"hi-input hi-select " + className} defaultValue={value ?? ""} disabled={disabled} {...rest}>
        <option value="" disabled>{placeholder}</option>
        {options.map((o) => {
          const v = o.value ?? o, l = o.label ?? o;
          return <option key={v} value={v}>{l}</option>;
        })}
      </select>
      <Icon name="chevron-down" size={16} />
    </span>
  );
}
