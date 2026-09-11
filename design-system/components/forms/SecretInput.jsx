import React from "react";
import { Icon } from "../foundation/Icon.jsx";

export function SecretInput({ value, revealed = false, storedLocally = false, onReveal, onHide, disabled }) {
  if (revealed) {
    return (
      <div className="hi-row" style={{ gap: "var(--space-050)" }}>
        <div className="hi-secret hi-secret--revealed"><span className="hi-truncate">{value}</span></div>
        <button type="button" className="hi-iconbtn" aria-label="Wieder verbergen" onClick={onHide}><Icon name="eye-off" size={16} /></button>
      </div>
    );
  }
  return (
    <div className="hi-row" style={{ gap: "var(--space-050)", flexWrap: "wrap" }}>
      <div className="hi-secret">
        <Icon name="key-round" size={16} />
        <span>Wert verborgen</span>
        {!storedLocally ? <span className="hi-secret__note">nicht auf diesem Gerät gespeichert</span> : null}
      </div>
      <button type="button" className="hi-btn hi-btn--secondary" onClick={onReveal} disabled={disabled}>
        <Icon name="eye" size={16} /><span>Anzeigen</span>
      </button>
    </div>
  );
}
