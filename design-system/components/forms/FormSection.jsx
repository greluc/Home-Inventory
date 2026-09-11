import React from "react";
import { Icon } from "../foundation/Icon.jsx";

export function FormSection({ title, count, defaultOpen = true, collapsible = true, children, ...rest }) {
  const [open, setOpen] = React.useState(defaultOpen);
  const isOpen = collapsible ? open : true;
  return (
    <section className="hi-section" {...rest}>
      <button type="button" className="hi-section__head" aria-expanded={isOpen}
              onClick={() => collapsible && setOpen((o) => !o)}>
        {collapsible ? <Icon name="chevron-right" size={16} className="hi-section__chev" /> : null}
        <span>{title}</span>
        {count != null ? <span className="hi-section__count">{count}</span> : null}
      </button>
      {isOpen ? <div className="hi-section__body">{children}</div> : null}
    </section>
  );
}
