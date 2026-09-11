import React from "react";
import { Icon } from "../foundation/Icon.jsx";
export function FilterBar({ facets = [], onToggle, trailing }) {
  return (
    <div className="hi-filterbar">
      {facets.map((fc) => (
        <button key={fc.key} type="button" className="hi-facet" data-active={fc.active || undefined} onClick={() => onToggle && onToggle(fc.key)}>
          {fc.icon ? <Icon name={fc.icon} size={14} /> : null}
          <span>{fc.label}</span>
          {fc.count != null ? <span className="hi-facet__n">{fc.count}</span> : null}
          {fc.active ? <Icon name="x" size={14} /> : null}
        </button>
      ))}
      {trailing}
    </div>
  );
}
export function BulkBar({ count, children }) {
  return (
    <div className="hi-bulkbar">
      <span className="hi-bulkbar__n">{count} ausgewählt</span>
      <span style={{ marginLeft: "auto", display: "flex", gap: "var(--space-100)", flexWrap: "wrap" }}>{children}</span>
    </div>
  );
}
