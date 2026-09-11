import React from "react";
import { Icon } from "../foundation/Icon.jsx";

export function ReferenceInput({ kind = "location", path = [], label, placeholder = "Select…", readOnly, disabled, onPick, onClear, clearLabel = "Remove link" }) {
  const icon = kind === "location" ? "map-pin" : "package";
  if (readOnly) {
    if (!label) return <div className="hi-readonly hi-readonly--empty">—</div>;
    return (
      <div className="hi-readonly hi-col" style={{ alignItems: "flex-start", gap: 0 }}>
        <span>{label}</span>
        {path.length ? <span className="hi-ref__path hi-truncate">{path.join(" › ")}</span> : null}
      </div>
    );
  }
  return (
    <div className="hi-row" style={{ gap: "var(--space-050)" }}>
      <button type="button" className="hi-ref" disabled={disabled} onClick={onPick}>
        <Icon name={icon} size={16} />
        {label ? (
          <span className="hi-col hi-truncate" style={{ gap: 0, alignItems: "flex-start" }}>
            <span className="hi-ref__leaf hi-truncate">{label}</span>
            {path.length ? <span className="hi-ref__path hi-truncate">{path.join(" › ")}</span> : null}
          </span>
        ) : <span className="hi-ref__empty">{placeholder}</span>}
        <Icon name="chevron-right" size={16} style={{ marginLeft: "auto", color: "var(--text-muted)" }} />
      </button>
      {label && !disabled ? (
        <button type="button" className="hi-iconbtn" aria-label={clearLabel} onClick={onClear}><Icon name="x" size={16} /></button>
      ) : null}
    </div>
  );
}
