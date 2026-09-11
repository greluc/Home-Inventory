import React from "react";
import { Icon } from "../foundation/Icon.jsx";

function mark(label, q) {
  if (!q) return label;
  const i = label.toLowerCase().indexOf(q.toLowerCase());
  if (i < 0) return label;
  return [label.slice(0, i), <mark key="m">{label.slice(i, i + q.length)}</mark>, label.slice(i + q.length)];
}

export function ComboBox({ options = [], value, placeholder = "Search or choose", emptyText = "No match", onSelect, disabled, id }) {
  const [q, setQ] = React.useState("");
  const [open, setOpen] = React.useState(false);
  const [sel, setSel] = React.useState(value ?? "");
  const list = options.filter((o) => (o.label ?? o).toLowerCase().includes(q.toLowerCase()));
  return (
    <div style={{ position: "relative" }}>
      <div className="hi-combo">
        <span className="hi-combo__prefix"><Icon name="search" size={16} /></span>
        <input id={id} role="combobox" aria-expanded={open} aria-controls={id + "-lb"} autoComplete="off"
               disabled={disabled} placeholder={placeholder} value={open ? q : (sel || "")}
               onFocus={() => setOpen(true)} onBlur={() => setTimeout(() => setOpen(false), 120)}
               onChange={(e) => { setQ(e.target.value); setOpen(true); }} />
        <span className="hi-combo__unit" aria-hidden="true"><Icon name="chevrons-up-down" size={16} /></span>
      </div>
      {open ? (
        <div style={{ position: "absolute", insetInline: 0, top: "calc(100% + var(--space-025))", zIndex: "var(--z-popover)" }}>
          <ul className="hi-listbox" id={id + "-lb"} role="listbox">
            {list.length === 0 ? <li className="hi-listbox__empty">{emptyText}</li> : null}
            {list.map((o) => {
              const v = o.value ?? o, l = o.label ?? o;
              return (
                <li key={v} role="option" aria-selected={sel === l} className="hi-listbox__opt"
                    onMouseDown={() => { setSel(l); setQ(""); setOpen(false); onSelect && onSelect(v); }}>
                  {o.icon ? <Icon name={o.icon} size={16} /> : null}
                  <span className="hi-truncate">{mark(l, q)}</span>
                  {o.meta ? <span style={{ marginLeft: "auto", color: "var(--text-muted)", fontSize: "var(--fs-75)" }}>{o.meta}</span> : null}
                </li>
              );
            })}
          </ul>
        </div>
      ) : null}
    </div>
  );
}
