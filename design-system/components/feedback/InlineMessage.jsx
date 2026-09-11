import React from "react";
import { Icon } from "../foundation/Icon.jsx";
const ICON = { info: "info", success: "circle-check", warning: "triangle-alert", danger: "circle-alert", conflict: "git-merge", degraded: "gauge", offline: "cloud-off" };
export function InlineMessage({ tone = "info", title, icon, children, actions, className = "", ...rest }) {
  return (
    <div className={`hi-msg hi-msg--${tone} ${className}`} role={tone === "danger" ? "alert" : "status"} {...rest}>
      <Icon name={icon || ICON[tone]} size={16} />
      <div className="hi-msg__body">
        {title ? <div className="hi-msg__title">{title}</div> : null}
        {children ? <div>{children}</div> : null}
        {actions ? <div className="hi-msg__actions">{actions}</div> : null}
      </div>
    </div>
  );
}
