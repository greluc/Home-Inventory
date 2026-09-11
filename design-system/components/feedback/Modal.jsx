import React from "react";
import { Icon } from "../foundation/Icon.jsx";
export function Modal({ title, subtitle, wide = false, onClose, footer, closeLabel = "Close", children }) {
  return (
    <>
      <div className="hi-scrim" onClick={onClose} />
      <div className={"hi-modal " + (wide ? "hi-modal--wide" : "")} role="dialog" aria-modal="true" aria-label={title}>
        <div className="hi-modal__head">
          <div className="hi-col" style={{ gap: "var(--space-025)", minWidth: 0 }}>
            <span className="hi-modal__title">{title}</span>
            {subtitle ? <span style={{ color: "var(--text-muted)", fontSize: "var(--fs-75)" }}>{subtitle}</span> : null}
          </div>
          <button type="button" className="hi-iconbtn" style={{ marginLeft: "auto" }} aria-label={closeLabel} onClick={onClose}><Icon name="x" size={20} /></button>
        </div>
        <div className="hi-modal__body">{children}</div>
        {footer ? <div className="hi-modal__foot">{footer}</div> : null}
      </div>
    </>
  );
}
