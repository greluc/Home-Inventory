import React from "react";
import { Icon } from "../foundation/Icon.jsx";
import { CodePlate } from "./CodePlate.jsx";

export function LabelPreview({
  media = { vendor: "Avery Zweckform", articleNumber: "3474", width: 70, height: 37, verified: true },
  scale = 1, lines = [], code = "7Q2-M4X-9KD", moduleMm = 0.5, showQuiet = true,
}) {
  const mm = 3.7795 * scale;
  const w = media.width * mm, h = media.height * mm;
  const qr = Math.min(h - 8 * scale, 28 * mm);
  const tooSmall = moduleMm < 0.33;
  return (
    <div className="hi-labelprev" style={{ ["--mm"]: mm + "px" }}>
      <div className="hi-paperstrap">
        <Icon name="printer" size={14} />
        <span>Druckvorschau · maßstabsgetreu · {media.width} × {media.height} mm</span>
      </div>
      <div className="hi-labelprev__sheet" style={{ width: w, height: h }}>
        <div className="hi-labelprev__el" style={{ left: 4 * scale, top: (h - qr) / 2, width: qr, height: qr }}>
          <CodePlate code={code} size={qr} caption={false} inline />
        </div>
        {showQuiet ? <span className="hi-labelprev__quiet" style={{ left: 4 * scale, top: (h - qr) / 2, width: qr, height: qr }} /> : null}
        <div className="hi-labelprev__el hi-labelprev__text" style={{ left: qr + 10 * scale, top: 5 * scale, right: 4 * scale }}>
          {lines.map((l, i) => (
            <div key={i} style={{ fontSize: (l.size || 3) * mm, fontWeight: l.bold ? 600 : 400, marginBottom: 1.2 * scale, whiteSpace: l.wrap ? "normal" : "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{l.text}</div>
          ))}
          <div style={{ fontFamily: "var(--font-mono)", fontSize: 2.6 * mm, letterSpacing: "var(--ls-code)", marginTop: 1.5 * scale }}>{code}</div>
        </div>
      </div>
      <div className="hi-labelprev__ruler">
        <Icon name="ruler" size={14} />
        <span>{media.vendor} {media.articleNumber} · Modulgröße {moduleMm.toFixed(2)} mm</span>
      </div>
      {!media.verified ? (
        <p className="hi-labelprev__warn"><Icon name="triangle-alert" size={14} />Maße nicht verifiziert — vor dem Seriendruck Kalibrierbogen drucken.</p>
      ) : null}
      {tooSmall ? (
        <p className="hi-labelprev__warn"><Icon name="triangle-alert" size={14} />Modulgröße unter 0,33 mm — mit Handykameras unzuverlässig.</p>
      ) : null}
    </div>
  );
}
