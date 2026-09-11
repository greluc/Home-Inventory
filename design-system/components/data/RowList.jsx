import React from "react";
import { Icon } from "../foundation/Icon.jsx";

export function RowList({ items = [], selected = [], selectable = false, onToggle, onOpen }) {
  return (
    <div className="hi-rowlist">
      {items.map((it) => (
        <button key={it.id} type="button" className="hi-rowlist__item"
                aria-selected={selected.includes(it.id) || undefined}
                onClick={() => (selectable ? onToggle && onToggle(it.id) : onOpen && onOpen(it.id))}>
          <span className={"hi-thumb " + (it.pendingPhoto ? "hi-thumb--pending" : "")}>
            {it.photo ? <img src={it.photo} alt="" /> : <Icon name={it.icon || "package"} size={16} />}
          </span>
          <span className="hi-rowlist__main">
            <span className="hi-truncate" style={{ fontWeight: "var(--fw-medium)" }}>{it.name}</span>
            <span className="hi-rowlist__meta">
              <span className="hi-truncate">{it.path}</span>
              {it.status ? <>{it.statusNode}</> : null}
            </span>
          </span>
          {it.trailing}
          <Icon name="chevron-right" size={16} style={{ color: "var(--text-muted)" }} />
        </button>
      ))}
    </div>
  );
}
