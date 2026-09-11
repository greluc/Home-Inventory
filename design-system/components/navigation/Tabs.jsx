import React from "react";
import { Icon } from "../foundation/Icon.jsx";
export function Tabs({ tabs = [], value, onChange, label = "Ansicht" }) {
  return (
    <div className="hi-tabs" role="tablist" aria-label={label}>
      {tabs.map((t) => (
        <button key={t.key} type="button" role="tab" className="hi-tab" aria-selected={value === t.key}
                onClick={() => onChange && onChange(t.key)}>
          {t.icon ? <Icon name={t.icon} size={16} /> : null}
          <span>{t.label}</span>
          {t.count != null ? <span style={{ fontVariantNumeric: "tabular-nums", opacity: .7 }}>{t.count}</span> : null}
        </button>
      ))}
    </div>
  );
}
