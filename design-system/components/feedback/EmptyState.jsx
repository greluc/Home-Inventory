import React from "react";
import { Icon } from "../foundation/Icon.jsx";
export function EmptyState({ icon = "inbox", title, children, action, inline = false }) {
  return (
    <div className={"hi-empty " + (inline ? "hi-empty--inline" : "")}>
      <Icon name={icon} size={32} className="hi-empty__icon" />
      <p className="hi-empty__title">{title}</p>
      {children ? <p className="hi-empty__body">{children}</p> : null}
      {action}
    </div>
  );
}
