import React from "react";
import { Icon } from "../foundation/Icon.jsx";

const RESULT_ICON = {
  success: "circle-check", duplicate: "check-check",
  unknown: "circle-x", unassigned: "tag",
};

const MODE_KEYS = ["LOOKUP", "ASSIGN", "MOVE", "STOCKTAKE", "CAPTURE"];

/** English defaults. Every one is overridable through `labels`, because the client renders the
 *  translated text from its resource bundle — no display text is hard-coded here (REQ-NFR-032). */
export const SCAN_LABELS = {
  close: "Close scanner",
  ready: "Ready",
  captured: "captured",
  hint: "Hold the code inside the frame · torch for dark corners",
  modeGroup: "Scan mode",
  torchOn: "Torch on", torchOff: "Torch off",
  continuous: "Scan continuously", sound: "Sound", haptics: "Vibration",
  result: {
    success: "Captured", duplicate: "Already captured",
    unknown: "Unknown code", unassigned: "Label not yet assigned",
  },
  mode: {
    LOOKUP: "Look up", ASSIGN: "Assign", MOVE: "Move",
    STOCKTAKE: "Stocktake", CAPTURE: "Capture",
  },
};

export function ScanOverlay({
  mode = "LOOKUP", onMode, result, code, detail, torch = false, onTorch,
  continuous = true, onContinuous, sound = true, haptics = true, tally,
  onClose, primaryAction, labels,
}) {
  const t = {
    ...SCAN_LABELS, ...labels,
    result: { ...SCAN_LABELS.result, ...(labels && labels.result) },
    mode: { ...SCAN_LABELS.mode, ...(labels && labels.mode) },
  };
  return (
    <div className="hi-scan" data-result={result || undefined}>
      <div className="hi-scan__feed" aria-hidden="true" />
      <div className="hi-scan__top">
        <button type="button" className="hi-scan__ctl" onClick={onClose} aria-label={t.close}><Icon name="x" size={24} /></button>
        <span className="hi-scan__tally">
          <Icon name="check-check" size={16} />
          {tally != null ? <span>{tally}<em style={{ fontStyle: "normal" }}> {t.captured}</em></span> : <span>{t.ready}</span>}
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
        {result ? (
          <div className="hi-scan__result" data-result={result} role="status" aria-live="assertive">
            <Icon name={RESULT_ICON[result]} size={32} />
            <span>
              {t.result[result]}
              <small>{code ? <span className="hi-scan__code">{code}</span> : null}{code && detail ? " · " : ""}{detail}</small>
            </span>
          </div>
        ) : (
          <p className="hi-scan__hint">{t.hint}</p>
        )}

        <div className="hi-scan__modes" role="group" aria-label={t.modeGroup}>
          {MODE_KEYS.map((k) => (
            <button key={k} type="button" className="hi-scan__mode" aria-pressed={mode === k} onClick={() => onMode && onMode(k)}>{t.mode[k]}</button>
          ))}
        </div>

        <div className="hi-scan__controls">
          <button type="button" className="hi-scan__ctl" aria-pressed={torch} onClick={onTorch} aria-label={torch ? t.torchOff : t.torchOn}>
            <Icon name={torch ? "flashlight-off" : "flashlight"} size={24} />
          </button>
          <button type="button" className="hi-scan__ctl" aria-pressed={continuous} onClick={onContinuous} aria-label={t.continuous}>
            <Icon name="refresh-cw" size={24} />
          </button>
          <button type="button" className="hi-scan__ctl" aria-pressed={sound} aria-label={t.sound}>
            <Icon name="volume-2" size={24} />
          </button>
          <button type="button" className="hi-scan__ctl" aria-pressed={haptics} aria-label={t.haptics}>
            <Icon name="vibrate" size={24} />
          </button>
          {primaryAction}
        </div>
      </div>
    </div>
  );
}
