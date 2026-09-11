import React from "react";

export function Switch({ label, onLabel = "An", offLabel = "Aus", checked, disabled, ...rest }) {
  return (
    <label className="hi-switch">
      <span className="hi-choice__text">{label}</span>
      <span className="hi-row">
        <span className="hi-switch__state">{checked ? onLabel : offLabel}</span>
        <input type="checkbox" role="switch" checked={checked} disabled={disabled} readOnly={rest.onChange === undefined} {...rest} />
        <span className="hi-switch__track"><span className="hi-switch__knob" /></span>
      </span>
    </label>
  );
}
