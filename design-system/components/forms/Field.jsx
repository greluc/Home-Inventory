import React from "react";
import { Icon } from "../foundation/Icon.jsx";

export function Field({
  id, label, help, error, required = false, optional = false,
  readOnly = false, restricted = false, children, className = "",
  restrictedLabel = "No permission for this field", ...rest
}) {
  const describedBy = [help ? id + "-help" : null, error ? id + "-err" : null].filter(Boolean).join(" ") || undefined;
  const cls = ["hi-field", error ? "hi-field--invalid" : "", readOnly ? "hi-field--readonly" : "", className].filter(Boolean).join(" ");
  return (
    <div className={cls} {...rest}>
      <label className="hi-field__label" htmlFor={id}>
        <span>{label}</span>
        {required ? <span className="hi-field__req" aria-hidden="true">*</span> : null}
        {optional && !required ? <span className="hi-field__opt">optional</span> : null}
      </label>
      <div className="hi-field__control">
        {restricted ? (
          <div className="hi-restricted hi-hatch">
            <Icon name="lock" size={16} />
            <span>{restrictedLabel}</span>
          </div>
        ) : typeof children === "function" ? children({ id, describedBy, invalid: !!error }) : children}
      </div>
      {help && !error ? <p className="hi-field__help" id={id + "-help"}>{help}</p> : null}
      {error ? (
        <p className="hi-field__msg" id={id + "-err"} role="alert">
          <Icon name="circle-alert" size={14} />
          <span>{error}</span>
        </p>
      ) : null}
    </div>
  );
}
