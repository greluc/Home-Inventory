import React from "react";
import { Icon } from "../foundation/Icon.jsx";
export function BottomNav({ items = [], value, onChange, label = "Hauptnavigation" }) {
  return (
    <nav className="hi-bottomnav" aria-label={label}>
      {items.map((it) => it.action ? (
        <button key={it.key} type="button" className="hi-bottomnav__item" onClick={() => onChange && onChange(it.key)}>
          <span className="hi-bottomnav__action"><Icon name={it.icon} size={24} /></span>
          <span className="hi-sr">{it.label}</span>
        </button>
      ) : (
        <button key={it.key} type="button" className="hi-bottomnav__item" aria-current={value === it.key ? "page" : undefined} onClick={() => onChange && onChange(it.key)}>
          <Icon name={it.icon} size={20} />
          <span>{it.label}</span>
        </button>
      ))}
    </nav>
  );
}
export function NavRail({ items = [], value, onChange, label = "Hauptnavigation" }) {
  return (
    <nav className="hi-rail" aria-label={label}>
      {items.filter((i) => !i.action).map((it) => (
        <button key={it.key} type="button" className="hi-rail__item" aria-current={value === it.key ? "page" : undefined} onClick={() => onChange && onChange(it.key)}>
          <Icon name={it.icon} size={20} />
          <span>{it.label}</span>
        </button>
      ))}
    </nav>
  );
}
