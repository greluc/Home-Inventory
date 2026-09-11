import React from "react";
import { Icon } from "../foundation/Icon.jsx";
import { Checkbox } from "../forms/Checkbox.jsx";

export function Table({ columns = [], rows = [], sort, onSort, selectable = false, selected = [], onToggle, rowKey = (r) => r.id, caption }) {
  const isSel = (r) => selected.includes(rowKey(r));
  return (
    <div className="hi-tablewrap hi-scroll-thin">
      <table className="hi-table">
        {caption ? <caption className="hi-sr">{caption}</caption> : null}
        <thead>
          <tr>
            {selectable ? <th className="hi-cell-sel" scope="col"><span className="hi-sr">Auswahl</span></th> : null}
            {columns.map((c) => (
              <th key={c.key} scope="col" className={c.numeric ? "hi-num" : ""} style={c.width ? { width: c.width } : undefined}>
                {c.sortable === false ? c.label : (
                  <button type="button" className="hi-table__sort" aria-sort={sort?.key === c.key ? sort.dir : "none"} onClick={() => onSort && onSort(c.key)}>
                    <span>{c.label}</span>
                    <Icon name={sort?.key === c.key ? (sort.dir === "ascending" ? "arrow-up" : "arrow-down") : "arrow-up-down"} size={14} />
                  </button>
                )}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={rowKey(r)} aria-selected={isSel(r) || undefined}>
              {selectable ? (
                <td className="hi-cell-sel">
                  <Checkbox label={<span className="hi-sr">{r.name}</span>} checked={isSel(r)} onChange={() => onToggle && onToggle(rowKey(r))} />
                </td>
              ) : null}
              {columns.map((c) => (
                <td key={c.key} className={[c.numeric ? "hi-num" : "", c.mono ? "hi-code" : "", c.key === "name" ? "hi-table__namecol" : ""].filter(Boolean).join(" ")}>
                  {c.render ? c.render(r) : <span className="hi-truncate" style={{ display: "block" }}>{r[c.key]}</span>}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
