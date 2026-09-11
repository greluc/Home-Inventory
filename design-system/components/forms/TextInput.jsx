import React from "react";
import { Icon } from "../foundation/Icon.jsx";

const NUMERIC = { integer: { inputMode: "numeric", step: 1 }, decimal: { inputMode: "decimal", step: "0.01" } };

export function TextInput({ type = "text", value, readOnly, emptyText = "—", mono = false, rows = 3, className = "", ...rest }) {
  if (readOnly) {
    const shown = value === "" || value == null ? null : String(value);
    return (
      <div className={"hi-readonly " + (shown ? "" : "hi-readonly--empty ") + (mono ? "hi-mono " : "") + className}>
        {shown ?? emptyText}
      </div>
    );
  }
  if (type === "multiline") {
    return <textarea className={"hi-input " + className} rows={rows} defaultValue={value} {...rest} />;
  }
  const numeric = NUMERIC[type];
  const htmlType = type === "integer" || type === "decimal" ? "number" : type === "url" ? "url" : type === "email" ? "email" : "text";
  const cls = ["hi-input", numeric ? "hi-input--num" : "", mono ? "hi-input--mono" : "", className].filter(Boolean).join(" ");
  if (type === "url" || type === "email") {
    return (
      <div className="hi-combo">
        <span className="hi-combo__prefix"><Icon name={type === "url" ? "link" : "mail"} size={16} /></span>
        <input type={htmlType} defaultValue={value} {...rest} />
      </div>
    );
  }
  return <input type={htmlType} className={cls} defaultValue={value} {...(numeric || {})} {...rest} />;
}
