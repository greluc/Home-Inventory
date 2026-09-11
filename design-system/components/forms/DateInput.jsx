import React from "react";

export function DateInput({ type = "date", value, readOnly, locale = "de-DE", ...rest }) {
  if (readOnly) {
    const d = value ? new Date(value) : null;
    const fmt = type === "datetime" ? { dateStyle: "medium", timeStyle: "short" } : { dateStyle: "medium" };
    return (
      <div className={"hi-readonly " + (d ? "" : "hi-readonly--empty")} style={{ fontVariantNumeric: "tabular-nums" }}>
        {d ? new Intl.DateTimeFormat(locale, fmt).format(d) : "—"}
      </div>
    );
  }
  return <input type={type === "datetime" ? "datetime-local" : "date"} className="hi-input hi-input--num" style={{ textAlign: "left" }} defaultValue={value} {...rest} />;
}
