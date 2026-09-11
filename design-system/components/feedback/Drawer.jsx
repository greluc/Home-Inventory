import React from "react";
import { Icon } from "../foundation/Icon.jsx";
export function Drawer({ title, onClose, footer, scrim = true, children }) {
  return (
    <>
      {scrim ? <div className="hi-scrim" onClick={onClose} /> : null}
      <aside className="hi-drawer" role="dialog" aria-label={title}>
        <div className="hi-drawer__grip" />
        <div className="hi-drawer__head">
          <span className="hi-modal__title">{title}</span>
          <button type="button" className="hi-iconbtn" style={{ marginLeft: "auto" }} aria-label="Schließen" onClick={onClose}><Icon name="x" size={20} /></button>
        </div>
        <div className="hi-drawer__body">{children}</div>
        {footer ? <div className="hi-drawer__foot">{footer}</div> : null}
      </aside>
    </>
  );
}
