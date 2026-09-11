import React from "react";
import { Icon } from "../foundation/Icon.jsx";

export function Button({
  variant = "secondary", size = "md", icon, iconEnd, busy = false,
  full = false, disabled = false, as = "button", children, className = "", ...rest
}) {
  const Tag = as;
  const cls = [
    "hi-btn", `hi-btn--${variant}`,
    size === "lg" ? "hi-btn--lg" : "",
    full ? "hi-btn--full" : "", className,
  ].filter(Boolean).join(" ");
  const glyph = busy ? "loader-circle" : icon;
  return (
    <Tag className={cls} data-busy={busy || undefined} disabled={Tag === "button" ? disabled || busy : undefined}
         aria-disabled={Tag !== "button" && (disabled || busy) ? "true" : undefined} {...rest}>
      {glyph ? <Icon name={glyph} size={size === "lg" ? 20 : 16} /> : null}
      <span>{children}</span>
      {iconEnd && !busy ? <Icon name={iconEnd} size={size === "lg" ? 20 : 16} /> : null}
    </Tag>
  );
}
