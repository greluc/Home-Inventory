import React from "react";

/* A deterministic block pattern standing in for the real QR renderer. The
   product renders an actual QR; what matters here is the invariant — dark
   modules on a light ground, a quiet zone of 4 modules, and the human-readable
   code beneath, because a label must still work when the scan fails. */
function pattern(seed, n) {
  const cells = [];
  let h = 0;
  for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) >>> 0;
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
    const corner = (x < 3 && y < 3) || (x > n - 4 && y < 3) || (x < 3 && y > n - 4);
    h = (h * 1103515245 + 12345) >>> 0;
    cells.push(corner ? ((x === 0 || x === 2 || y === 0 || y === 2) ? 1 : (x === 1 && y === 1 ? 1 : 0)) : (h >>> 16) % 100 < 46 ? 1 : 0);
  }
  return cells;
}

export function CodePlate({ code = "7Q2-M4X-9KD", size = 128, modules = 21, caption = true, inline = false, symbology = "QR" }) {
  const cells = React.useMemo(() => pattern(code + symbology, modules), [code, symbology, modules]);
  const quiet = 4;
  const total = modules + quiet * 2;
  return (
    <div className={"hi-codeplate " + (inline ? "hi-codeplate--inline" : "")}>
      <svg className="hi-codeplate__glyph" width={size} height={size} viewBox={`0 0 ${total} ${total}`} role="img" aria-label={`${symbology}-Code ${code}`} shapeRendering="crispEdges">
        <rect width={total} height={total} fill="var(--code-quiet)" />
        {cells.map((v, i) => v ? <rect key={i} x={quiet + (i % modules)} y={quiet + Math.floor(i / modules)} width="1" height="1" fill="var(--code-ink)" /> : null)}
      </svg>
      {caption ? <span className="hi-codeplate__caption">{code}</span> : null}
    </div>
  );
}
