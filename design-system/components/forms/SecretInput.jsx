import React from "react";
import { Icon } from "../foundation/Icon.jsx";

export function SecretInput({
  value, revealed = false, storedLocally = false, onReveal, onHide, disabled,
  hideLabel = "Hide again", revealLabel = "Reveal",
  hiddenLabel = "Value hidden", notStoredLabel = "not stored on this device",
}) {
  if (revealed) {
    return (
      <div className="hi-row" style={{ gap: "var(--space-050)" }}>
        <div className="hi-secret hi-secret--revealed"><span className="hi-truncate">{value}</span></div>
        <button type="button" className="hi-iconbtn" aria-label={hideLabel} onClick={onHide}><Icon name="eye-off" size={16} /></button>
      </div>
    );
  }
  return (
    <div className="hi-row" style={{ gap: "var(--space-050)", flexWrap: "wrap" }}>
      <div className="hi-secret">
        <Icon name="key-round" size={16} />
        <span>{hiddenLabel}</span>
        {!storedLocally ? <span className="hi-secret__note">{notStoredLabel}</span> : null}
      </div>
      <button type="button" className="hi-btn hi-btn--secondary" onClick={onReveal} disabled={disabled}>
        <Icon name="eye" size={16} /><span>{revealLabel}</span>
      </button>
    </div>
  );
}
