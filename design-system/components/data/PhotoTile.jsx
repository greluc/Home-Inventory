import React from "react";
import { Icon } from "../foundation/Icon.jsx";
import { Checkbox } from "../forms/Checkbox.jsx";

export function PhotoTile({
  item, selectable, selected, onToggle, onOpen,
  selectLabel = (name) => "Select " + name,
}) {
  return (
    <div className="hi-tile" aria-selected={selected || undefined} onClick={() => onOpen && onOpen(item.id)}>
      <div className="hi-tile__img">
        {item.photo ? <img src={item.photo} alt="" /> : <Icon name={item.icon || "image"} size={32} />}
        {selectable ? (
          <span className="hi-tile__sel" onClick={(e) => { e.stopPropagation(); onToggle && onToggle(item.id); }}>
            <Checkbox label={<span className="hi-sr">{selectLabel(item.name)}</span>} checked={!!selected} />
          </span>
        ) : null}
        {item.badges ? <span className="hi-tile__badges">{item.badges}</span> : null}
      </div>
      <div className="hi-tile__body">
        <span className="hi-tile__name hi-clamp-2">{item.name}</span>
        <span className="hi-tile__path hi-truncate">{item.path}</span>
      </div>
    </div>
  );
}
export function PhotoGrid({ children }) { return <div className="hi-grid">{children}</div>; }
