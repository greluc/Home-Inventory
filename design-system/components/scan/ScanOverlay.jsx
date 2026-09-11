import React from "react";
import { Icon } from "../foundation/Icon.jsx";

const RESULT = {
  success:    { icon: "circle-check",  title: "Erfasst" },
  duplicate:  { icon: "check-check",   title: "Bereits erfasst" },
  unknown:    { icon: "circle-x",      title: "Code unbekannt" },
  unassigned: { icon: "tag",           title: "Etikett noch nicht zugeordnet" },
};

const MODES = [
  { key: "LOOKUP", label: "Nachschlagen" }, { key: "ASSIGN", label: "Zuordnen" },
  { key: "MOVE", label: "Umlagern" }, { key: "STOCKTAKE", label: "Inventur" },
  { key: "CAPTURE", label: "Erfassen" },
];

export function ScanOverlay({
  mode = "LOOKUP", onMode, result, code, detail, torch = false, onTorch,
  continuous = true, onContinuous, sound = true, haptics = true, tally,
  onClose, primaryAction,
}) {
  const r = result ? RESULT[result] : null;
  return (
    <div className="hi-scan" data-result={result || undefined}>
      <div className="hi-scan__feed" aria-hidden="true" />
      <div className="hi-scan__top">
        <button type="button" className="hi-scan__ctl" onClick={onClose} aria-label="Scanner schließen"><Icon name="x" size={24} /></button>
        <span className="hi-scan__tally">
          <Icon name="check-check" size={16} />
          {tally != null ? <span>{tally}<em style={{ fontStyle: "normal" }}> erfasst</em></span> : <span>Bereit</span>}
        </span>
      </div>

      <div className="hi-scan__mid">
        <div className="hi-scan__frame">
          <span className="hi-scan__corner" /><span className="hi-scan__corner" />
          <span className="hi-scan__corner" /><span className="hi-scan__corner" />
          {!result ? <span className="hi-scan__sweep" /> : null}
        </div>
      </div>

      <div className="hi-scan__bottom">
        {r ? (
          <div className="hi-scan__result" data-result={result} role="status" aria-live="assertive">
            <Icon name={r.icon} size={32} />
            <span>
              {r.title}
              <small>{code ? <span className="hi-scan__code">{code}</span> : null}{code && detail ? " · " : ""}{detail}</small>
            </span>
          </div>
        ) : (
          <p className="hi-scan__hint">Code in den Rahmen halten · Torch für dunkle Ecken</p>
        )}

        <div className="hi-scan__modes" role="group" aria-label="Scan-Modus">
          {MODES.map((m) => (
            <button key={m.key} type="button" className="hi-scan__mode" aria-pressed={mode === m.key} onClick={() => onMode && onMode(m.key)}>{m.label}</button>
          ))}
        </div>

        <div className="hi-scan__controls">
          <button type="button" className="hi-scan__ctl" aria-pressed={torch} onClick={onTorch} aria-label={torch ? "Licht aus" : "Licht an"}>
            <Icon name={torch ? "flashlight-off" : "flashlight"} size={24} />
          </button>
          <button type="button" className="hi-scan__ctl" aria-pressed={continuous} onClick={onContinuous} aria-label="Fortlaufend scannen">
            <Icon name="refresh-cw" size={24} />
          </button>
          <button type="button" className="hi-scan__ctl" aria-pressed={sound} aria-label="Ton">
            <Icon name="volume-2" size={24} />
          </button>
          <button type="button" className="hi-scan__ctl" aria-pressed={haptics} aria-label="Vibration">
            <Icon name="vibrate" size={24} />
          </button>
          {primaryAction}
        </div>
      </div>
    </div>
  );
}
