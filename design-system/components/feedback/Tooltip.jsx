import React from "react";
export function Tooltip({ content, children, placement = "top" }) {
  const [open, setOpen] = React.useState(false);
  return (
    <span style={{ position: "relative", display: "inline-flex" }}
          onMouseEnter={() => setOpen(true)} onMouseLeave={() => setOpen(false)}
          onFocus={() => setOpen(true)} onBlur={() => setOpen(false)}>
      {children}
      {open ? (
        <span className="hi-tooltip" role="tooltip"
              style={placement === "top" ? { bottom: "calc(100% + var(--space-075))", left: 0 } : { top: "calc(100% + var(--space-075))", left: 0 }}>
          {content}
        </span>
      ) : null}
    </span>
  );
}
