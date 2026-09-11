import React from "react";
import { Icon } from "../foundation/Icon.jsx";

const KIND_ICON = { building: "building", room: "house", shelf: "rows-3", box: "box", compartment: "package", area: "warehouse" };

function Node({ node, depth, openIds, currentId, onToggle, onSelect }) {
  const open = openIds.includes(node.id);
  const kids = node.children || [];
  return (
    <li>
      <button type="button" className="hi-tree__row" aria-current={currentId === node.id || undefined}
              style={{ paddingLeft: `calc(var(--space-100) + ${depth} * var(--space-200))` }}
              onClick={() => onSelect && onSelect(node.id)}>
        {kids.length ? (
          <span className="hi-tree__twisty" data-open={open} onClick={(e) => { e.stopPropagation(); onToggle && onToggle(node.id); }}>
            <Icon name="chevron-right" size={16} />
          </span>
        ) : <span className="hi-tree__spacer" />}
        <Icon name={KIND_ICON[node.kind] || "folder"} size={16} />
        <span className="hi-tree__label">{node.name}</span>
        {node.count != null ? <span className="hi-tree__count">{node.count}</span> : null}
        {node.trailing}
      </button>
      {open && kids.length ? (
        <ul>{kids.map((c) => <Node key={c.id} node={c} depth={depth + 1} openIds={openIds} currentId={currentId} onToggle={onToggle} onSelect={onSelect} />)}</ul>
      ) : null}
    </li>
  );
}

export function Tree({ nodes = [], openIds = [], currentId, onToggle, onSelect, label = "Lagerorte" }) {
  return (
    <nav className="hi-tree" aria-label={label}>
      <ul role="tree">{nodes.map((n) => <Node key={n.id} node={n} depth={0} openIds={openIds} currentId={currentId} onToggle={onToggle} onSelect={onSelect} />)}</ul>
    </nav>
  );
}
