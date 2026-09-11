import React from "react";
import { Icon } from "../foundation/Icon.jsx";

export function TagInput({ values = [], placeholder = "Add…", readOnly, disabled, onRemove, id }) {
  if (readOnly) {
    if (!values.length) return <div className="hi-readonly hi-readonly--empty">—</div>;
    return (
      <div className="hi-readonly" style={{ flexWrap: "wrap", gap: "var(--space-050)" }}>
        {values.map((v) => <span key={v} className="hi-tag">{v}</span>)}
      </div>
    );
  }
  return (
    <div className="hi-taginput">
      {values.map((v) => (
        <span key={v} className="hi-tag hi-tag--removable">
          <span className="hi-truncate">{v}</span>
          <button type="button" className="hi-tag__x" aria-label={`${v} entfernen`} onClick={() => onRemove && onRemove(v)} disabled={disabled}>
            <Icon name="x" size={14} />
          </button>
        </span>
      ))}
      <input id={id} placeholder={values.length ? "" : placeholder} disabled={disabled} />
    </div>
  );
}
