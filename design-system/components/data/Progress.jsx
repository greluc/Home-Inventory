import React from "react";
export function Progress({ value, max = 100, label, detail, indeterminate = false }) {
  const pct = indeterminate ? null : Math.round((value / max) * 100);
  return (
    <div className="hi-progress">
      {label || detail ? (
        <div className="hi-progress__meta"><span>{label}</span><span>{detail ?? (pct != null ? pct + " %" : "")}</span></div>
      ) : null}
      <div className="hi-progress__track" role="progressbar" aria-valuenow={indeterminate ? undefined : value} aria-valuemin={0} aria-valuemax={max} aria-label={label}>
        <div className={"hi-progress__fill " + (indeterminate ? "hi-progress__fill--indeterminate" : "")} style={indeterminate ? undefined : { width: pct + "%" }} />
      </div>
    </div>
  );
}
