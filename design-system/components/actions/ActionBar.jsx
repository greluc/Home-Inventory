import React from "react";

export function ActionBar({ sticky = false, stack = false, children, className = "", ...rest }) {
  const cls = ["hi-actionbar", sticky ? "hi-actionbar--sticky" : "", stack ? "hi-actionbar--stack" : "", className].filter(Boolean).join(" ");
  return <div className={cls} {...rest}>{children}</div>;
}
export function ActionBarSpacer() { return <div className="hi-actionbar__spacer" />; }
