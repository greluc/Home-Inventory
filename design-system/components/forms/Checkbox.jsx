import React from "react";
import { Icon } from "../foundation/Icon.jsx";

export function Checkbox({ label, hint, checked, indeterminate = false, disabled, ...rest }) {
  const ref = React.useRef(null);
  React.useEffect(() => { if (ref.current) ref.current.indeterminate = indeterminate; }, [indeterminate]);
  return (
    <label className="hi-choice">
      <input ref={ref} type="checkbox" checked={checked} disabled={disabled} readOnly={rest.onChange === undefined} {...rest} />
      <span className="hi-choice__box" aria-hidden="true"><Icon name={indeterminate ? "minus" : "check"} size={16} /></span>
      <span className="hi-choice__text">{label}{hint ? <span className="hi-choice__hint">{hint}</span> : null}</span>
    </label>
  );
}
