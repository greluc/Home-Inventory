import React from "react";
import { Icon } from "../foundation/Icon.jsx";
export function Breadcrumb({ path = [], collapseFrom = 4, onNavigate, label = "Path", expandLabel = "Show hidden levels" }) {
  const long = path.length > collapseFrom;
  const shown = long ? [path[0], { id: "…", name: "…", ellipsis: true }, ...path.slice(-2)] : path;
  return (
    <nav className="hi-crumbs" aria-label={label}>
      {shown.map((seg, i) => {
        const last = i === shown.length - 1;
        return (
          <React.Fragment key={seg.id + i}>
            {i > 0 ? <Icon name="chevron-right" size={14} className="hi-crumbs__sep" /> : null}
            {last ? <span className="hi-crumbs__cur" aria-current="page">{seg.name}</span>
                  : seg.ellipsis ? <button type="button" aria-label={expandLabel}>…</button>
                  : <button type="button" onClick={() => onNavigate && onNavigate(seg.id)}>{seg.name}</button>}
          </React.Fragment>
        );
      })}
    </nav>
  );
}
