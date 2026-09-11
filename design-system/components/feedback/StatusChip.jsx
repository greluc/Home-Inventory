import React from "react";
import { Icon } from "../foundation/Icon.jsx";

/** Icon and English default label per status. The `label` prop overrides it, which is how the
 *  client renders the translated text from its resource bundle (REQ-NFR-032). */
export const STATUS = {
  offline:    { icon: "cloud-off",     label: "Offline" },
  pending:    { icon: "cloud-upload",  label: "Upload pending" },
  conflict:   { icon: "git-merge",     label: "Conflict" },
  degraded:   { icon: "gauge",         label: "Degraded search" },
  restricted: { icon: "lock",          label: "No permission" },
  unassigned: { icon: "tag",           label: "Not yet assigned" },
  success:    { icon: "circle-check",  label: "In sync" },
  warning:    { icon: "triangle-alert",label: "Attention" },
  danger:     { icon: "circle-x",      label: "Error" },
  neutral:    { icon: "info",          label: "Notice" },
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
