import React from "react";
import { Icon } from "../foundation/Icon.jsx";
import { STATUS } from "./StatusChip.jsx";
export function Toast({ tone = "neutral", title, detail, action, onDismiss, dismissLabel = "Dismiss" }) {
  const s = STATUS[tone] || STATUS.neutral;
  return (
    <div className={`hi-toast hi-toast--${tone}`} role="status" aria-live="polite">
      <Icon name={s.icon} size={16} />
      <div className="hi-toast__body">
        <span>{title}</span>
        {detail ? <span className="hi-toast__sub">{detail}</span> : null}
      </div>
      {action}
      {onDismiss ? <button type="button" className="hi-iconbtn" aria-label={dismissLabel} onClick={onDismiss}><Icon name="x" size={16} /></button> : null}
    </div>
  );
}
export function ToastStack({ children }) { return <div className="hi-toaststack">{children}</div>; }
