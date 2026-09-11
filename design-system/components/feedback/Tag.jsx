import React from "react";
import { Icon } from "../foundation/Icon.jsx";
export function Tag({ children, onRemove, removeLabel, className = "", ...rest }) {
  return (
    <span className={"hi-tag " + (onRemove ? "hi-tag--removable " : "") + className} {...rest}>
      <span className="hi-truncate">{children}</span>
      {onRemove ? <button type="button" className="hi-tag__x" aria-label={removeLabel || "Entfernen"} onClick={onRemove}><Icon name="x" size={14} /></button> : null}
    </span>
  );
}
