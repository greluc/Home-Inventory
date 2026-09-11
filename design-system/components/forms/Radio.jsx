import React from "react";

export function Radio({ label, hint, ...rest }) {
  return (
    <label className="hi-choice">
      <input type="radio" readOnly={rest.onChange === undefined} {...rest} />
      <span className="hi-choice__box hi-choice__box--radio" aria-hidden="true">
        <svg viewBox="0 0 16 16" width="8" height="8"><circle cx="8" cy="8" r="8" fill="currentColor" /></svg>
      </span>
      <span className="hi-choice__text">{label}{hint ? <span className="hi-choice__hint">{hint}</span> : null}</span>
    </label>
  );
}
export function RadioGroup({ legend, children, ...rest }) {
  return (
    <fieldset style={{ border: 0, padding: 0, margin: 0 }} {...rest}>
      <legend className="hi-field__label" style={{ padding: 0 }}>{legend}</legend>
      <div className="hi-col">{children}</div>
    </fieldset>
  );
}
