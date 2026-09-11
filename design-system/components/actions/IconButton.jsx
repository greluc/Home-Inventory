import React from "react";
import { Icon } from "../foundation/Icon.jsx";

export function IconButton({ icon, label, tone = "default", outlined = false, pressed, size = 20, className = "", ...rest }) {
  const cls = ["hi-iconbtn", outlined ? "hi-iconbtn--outlined" : "", tone === "danger" ? "hi-iconbtn--danger" : "", className].filter(Boolean).join(" ");
  return (
    <button type="button" className={cls} aria-pressed={pressed === undefined ? undefined : pressed} title={label} {...rest}>
      <Icon name={icon} size={size} />
      <span className="hi-sr">{label}</span>
    </button>
  );
}
