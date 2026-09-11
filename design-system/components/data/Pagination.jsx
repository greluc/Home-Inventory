import React from "react";
import { Icon } from "../foundation/Icon.jsx";
export function Pagination({ page = 1, pages = 1, total, onChange }) {
  const win = [];
  for (let p = Math.max(1, page - 2); p <= Math.min(pages, page + 2); p++) win.push(p);
  return (
    <nav className="hi-pager" aria-label="Seitennavigation">
      <button className="hi-pager__n" disabled={page <= 1} onClick={() => onChange && onChange(page - 1)} aria-label="Vorherige Seite"><Icon name="chevron-left" size={16} /></button>
      {win[0] > 1 ? <><button className="hi-pager__n" onClick={() => onChange && onChange(1)}>1</button><span style={{ color: "var(--text-disabled)" }}>…</span></> : null}
      {win.map((p) => <button key={p} className="hi-pager__n" aria-current={p === page ? "page" : undefined} onClick={() => onChange && onChange(p)}>{p}</button>)}
      {win[win.length - 1] < pages ? <><span style={{ color: "var(--text-disabled)" }}>…</span><button className="hi-pager__n" onClick={() => onChange && onChange(pages)}>{pages}</button></> : null}
      <button className="hi-pager__n" disabled={page >= pages} onClick={() => onChange && onChange(page + 1)} aria-label="Nächste Seite"><Icon name="chevron-right" size={16} /></button>
      {total != null ? <span className="hi-pager__total">{total.toLocaleString("de-DE")} Artikel</span> : null}
    </nav>
  );
}
