import React from "react";
import { Icon } from "../foundation/Icon.jsx";

export const STATUS = {
  offline:    { icon: "cloud-off",     label: "Offline" },
  pending:    { icon: "cloud-upload",  label: "Upload ausstehend" },
  conflict:   { icon: "git-merge",     label: "Konflikt" },
  degraded:   { icon: "gauge",         label: "Eingeschränkte Suche" },
  restricted: { icon: "lock",          label: "Keine Berechtigung" },
  unassigned: { icon: "tag",           label: "Noch nicht zugeordnet" },
  success:    { icon: "circle-check",  label: "Synchron" },
  warning:    { icon: "triangle-alert",label: "Achtung" },
  danger:     { icon: "circle-x",      label: "Fehler" },
  neutral:    { icon: "info",          label: "Hinweis" },
};

export function StatusChip({ status = "neutral", label, count, size = 14, className = "", ...rest }) {
  const s = STATUS[status] || STATUS.neutral;
  return (
    <span className={`hi-chip hi-chip--${status} ${className}`} {...rest}>
      <Icon name={s.icon} size={size} />
      <span>{label ?? s.label}</span>
      {count != null ? <span style={{ fontVariantNumeric: "tabular-nums", opacity: .85 }}>· {count}</span> : null}
    </span>
  );
}
