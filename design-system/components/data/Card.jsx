import React from "react";
export function Card({ title, actions, flush = false, children, className = "", ...rest }) {
  return (
    <section className={"hi-card " + (flush ? "hi-card--flush " : "") + className} {...rest}>
      {title ? <header className="hi-card__head"><h3 className="hi-card__title">{title}</h3><span style={{ marginLeft: "auto" }}>{actions}</span></header> : null}
      <div className="hi-card__body">{children}</div>
    </section>
  );
}
