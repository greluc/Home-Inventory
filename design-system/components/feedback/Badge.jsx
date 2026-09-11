import React from "react";
export function Badge({ tone = "neutral", count, children, className = "", ...rest }) {
  if (count != null) return <span className={"hi-badge hi-badge--count " + className} {...rest}>{count}</span>;
  return <span className={`hi-badge hi-badge--${tone} ${className}`} {...rest}>{children}</span>;
}
